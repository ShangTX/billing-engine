package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CONTINUOUS 模式通用策略（层 2）：持有唯一一份 applyCapAndAccumulate，消除 4 规则族重复。
 * <p>
 * 周期切换通过 {@link RuleSemantics#isCycleBoundary} / {@link RuleSemantics#nextCycleBoundary} 注入
 * （含 NaturalTime 滑动窗口）；periodCap 通过 {@link RuleSemantics#hasPeriodCap} /
 * {@link RuleSemantics#periodCap} / {@link RuleSemantics#periodKey} 注入（仅 CompositeTime）；
 * cap 标记通过 {@link RuleSemantics#cycleCapLabel} 注入；unitMinutes 通过
 * {@link RuleSemantics#unitMinutes} 注入（全局/按 period）。
 * <p>
 * 同时承载 CONTINUOUS 策略族共享的不足单元计费（{@link #computeIncompleteCharge} /
 * {@link #isIncompleteFree}）与简化单元构建（{@link #buildSimplifiedUnit} /
 * {@link #getCycleBoundary} / {@link #isSimplificationEnabled}）。
 * <p>
 * TODO-20260706-002 阶段3：4 份 applyCapAndAccumulate 合并为 1 份。
 * TODO-20260706-002 阶段7：从 AbstractTimeBasedRule 搬入不足单元计费 + 简化单元构建，废弃旧基类。
 */
public final class ContinuousStrategy {

    private ContinuousStrategy() {
    }

    /**
     * 把同质段列表转换为 BillingUnit 列表，并应用周期封顶、时段独立封顶、累计金额、compact 合并、截断标记。
     * <p>
     * 通用流程（原 4 份 applyCapAndAccumulate 的公共骨架）：
     * <ol>
     *   <li>subCount / isTruncated / cycleCapped / incompleteFree 判定</li>
     *   <li>periodCap：时段切换时对前一 period 应用独立封顶（仅 hasPeriodCap）</li>
     *   <li>charged 计算（免费/截断/预算封顶）</li>
     *   <li>累计 + 周期切换（isCycleBoundary → 重置 cycleAccumulated + 推进 currentCycleBoundary）</li>
     * </ol>
     * 末尾对最后一个 period 应用 periodCap + 重算累计（hasPeriodCap 时）。
     *
     * @param segments             同质段列表（边界驱动产出）
     * @param semantics            规则族语义（周期切换/periodCap/unitMinutes 等注入）
     * @param context              计费上下文（提供 calcEnd 等）
     * @param config               规则配置
     * @param cycleOrigin          周期起点（周期边界锚定）
     * @param calcBegin            计算窗口起点（子区间起点，简化路径头尾片段用）
     * @param carryOverAccumulated 周期累计结转（null=0；简化路径每子区间独立起算传 null）
     * @return BillingUnit 列表（含 compact 合并标记、截断标记、累计金额）
     */
    public static <C extends RuleConfig> List<BillingUnit> applyCapAndAccumulate(
            List<HomogeneousSegment> segments,
            RuleSemantics<C> semantics,
            BillingContext context,
            C config,
            LocalDateTime cycleOrigin,
            LocalDateTime calcBegin,
            BigDecimal carryOverAccumulated) {

        List<BillingUnit> units = new ArrayList<>();
        if (segments.isEmpty()) {
            return units;
        }

        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        BigDecimal maxCharge = semantics.cycleCap(config);
        String capLabel = semantics.cycleCapLabel();
        boolean hasPeriodCap = semantics.hasPeriodCap(config);

        BigDecimal cycleAccumulated = carryOverAccumulated != null ? carryOverAccumulated : BigDecimal.ZERO;
        LocalDateTime currentCycleBoundary = semantics.initialCycleBoundary(cycleOrigin, calcBegin);

        // periodCap 跟踪：当前 period key、封顶金额及 period 内累计（budget-based，与 cycleCap 统一在段处理时削减）
        String currentPeriodKey = null;
        BigDecimal currentPeriodCap = null;
        BigDecimal periodAccumulated = BigDecimal.ZERO;

        BigDecimal accumulated = BigDecimal.ZERO;

        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);
            int segMinutes = seg.durationMinutes();

            // 段内单元数与余数：移除单元对齐后段可跨多单元，拆为 compact（整单元）+ truncated（余数）
            int unitMinutes = semantics.unitMinutes(seg.getBeginTime(), config, cycleOrigin);
            int subCount = unitMinutes > 0 ? segMinutes / unitMinutes : 1;
            int remainder = unitMinutes > 0 ? segMinutes % unitMinutes : 0;
            if (unitMinutes <= 0) {
                subCount = 1;
                remainder = 0;
            }

            // periodCap：时段切换时重置 period 累计（budget-based，封顶在段处理时按预算削减）
            if (hasPeriodCap) {
                String periodKey = semantics.periodKey(seg.getBeginTime(), config, cycleOrigin);
                BigDecimal periodCap = semantics.periodCap(seg.getBeginTime(), config, cycleOrigin);
                if (currentPeriodKey == null) {
                    currentPeriodKey = periodKey;
                    currentPeriodCap = periodCap;
                    periodAccumulated = BigDecimal.ZERO;
                } else if (!periodKey.equals(currentPeriodKey)) {
                    currentPeriodKey = periodKey;
                    currentPeriodCap = periodCap;
                    periodAccumulated = BigDecimal.ZERO;
                }
            }

            // 周期封顶已触发：非免费段 + 周期累计 >= maxCharge -> 本段免费（标记 capLabel）
            boolean cycleCapped = false;
            if (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0
                    && !seg.isFree() && cycleAccumulated.compareTo(maxCharge) >= 0) {
                cycleCapped = true;
            }
            // 时段封顶已触发：非免费段 + period 累计 >= periodCap -> 本段免费（标记 PERIOD_CAP）
            boolean periodCapped = false;
            if (hasPeriodCap && currentPeriodCap != null && currentPeriodCap.compareTo(BigDecimal.ZERO) > 0
                    && !seg.isFree() && periodAccumulated.compareTo(currentPeriodCap) >= 0) {
                periodCapped = true;
            }

            // 末段不足单元按不足单元计费模式判定是否免费（FREE/THRESHOLD_MINUTES/THRESHOLD_RATIO 可能免费）
            // 仅末段可因不足而免费（与原有行为一致）；非末段不足段仅按比例收费（PROPORTIONAL 修复）
            boolean isLastShort = isLast && subCount == 0 && remainder > 0 && seg.getEndTime().equals(calcEnd);
            boolean incompleteFree = isLastShort && !seg.isFree() && !cycleCapped && !periodCapped
                    && ContinuousStrategy.isIncompleteFree(segMinutes, unitMinutes,
                            semantics.incompleteMode(config),
                            semantics.thresholdMinutes(config),
                            semantics.thresholdRatio(config));

            BigDecimal originalPerSub = seg.getOriginalAmount() != null
                    ? seg.getOriginalAmount() : BigDecimal.ZERO;
            BigDecimal unitPrice = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;

            // 免费段/封顶/末段不足免费：整段免费（不拆分），产出 1 个免费 BillingUnit
            if (seg.isFree() || cycleCapped || periodCapped || incompleteFree) {
                units.add(BillingUnit.builder()
                        .beginTime(seg.getBeginTime())
                        .endTime(seg.getEndTime())
                        .durationMinutes(segMinutes)
                        .unitPrice(unitPrice)
                        .originalAmount(BigDecimal.ZERO)
                        .free(true)
                        .freePromotionId(cycleCapped ? capLabel
                                : (periodCapped ? "PERIOD_CAP"
                                : (incompleteFree ? "INCOMPLETE_FREE" : seg.getFreePromotionId())))
                        .chargedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                        .accumulatedAmount(accumulated)
                        .ruleData(seg.getRuleData())
                        .compact(false)
                        .count(1)
                        .isTruncated(subCount == 0 && remainder > 0)
                        .build());
                // 免费不累计 cycleAccumulated/periodAccumulated；周期切换仍判定
                if (semantics.isCycleBoundary(seg, currentCycleBoundary, cycleOrigin)) {
                    currentCycleBoundary = semantics.nextCycleBoundary(seg.getEndTime(), currentCycleBoundary, cycleOrigin);
                    cycleAccumulated = BigDecimal.ZERO;
                }
                continue;
            }

            // combined 预算：cycleCap 与 periodCap 取最严约束，封顶时 compact 拆为收费 + 免费部分
            BigDecimal cycleBudget = (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0)
                    ? maxCharge.subtract(cycleAccumulated) : null;
            if (cycleBudget != null && cycleBudget.signum() < 0) cycleBudget = BigDecimal.ZERO;
            BigDecimal periodBudget = (hasPeriodCap && currentPeriodCap != null && currentPeriodCap.compareTo(BigDecimal.ZERO) > 0)
                    ? currentPeriodCap.subtract(periodAccumulated) : null;
            if (periodBudget != null && periodBudget.signum() < 0) periodBudget = BigDecimal.ZERO;

            // compact 部分：subCount 个整单元，同质段内单价一致；封顶时拆为收费整单元 + 部分削减单元 + 免费单元
            if (subCount > 0) {
                BigDecimal fullTotal = originalPerSub.multiply(BigDecimal.valueOf(subCount));
                // 取最严预算及对应 cap 标签
                BigDecimal budget = cycleBudget;
                String capHit = capLabel;
                if (periodBudget != null && (budget == null || periodBudget.compareTo(budget) < 0)) {
                    budget = periodBudget;
                    capHit = "PERIOD_CAP";
                }
                BigDecimal compactCharged = budget != null ? fullTotal.min(budget) : fullTotal;

                if (compactCharged.compareTo(fullTotal) < 0 && originalPerSub.compareTo(BigDecimal.ZERO) > 0) {
                    // 封顶削减：按 unitPrice 拆分
                    int chargedCount = compactCharged.divide(originalPerSub, 0, RoundingMode.DOWN).intValue();
                    BigDecimal partialCharged = compactCharged.subtract(originalPerSub.multiply(BigDecimal.valueOf(chargedCount)));
                    int partialUsed = partialCharged.compareTo(BigDecimal.ZERO) > 0 ? 1 : 0;
                    int freeCount = subCount - chargedCount - partialUsed;
                    LocalDateTime cursor = seg.getBeginTime();
                    LocalDateTime compactEnd = seg.getBeginTime().plusMinutes((long) subCount * unitMinutes);
                    if (chargedCount > 0) {
                        BigDecimal chargedTotal = originalPerSub.multiply(BigDecimal.valueOf(chargedCount));
                        accumulated = accumulated.add(chargedTotal);
                        cycleAccumulated = cycleAccumulated.add(chargedTotal);
                        periodAccumulated = periodAccumulated.add(chargedTotal);
                        units.add(BillingUnit.builder()
                                .beginTime(cursor)
                                .endTime(cursor.plusMinutes((long) chargedCount * unitMinutes))
                                .durationMinutes(chargedCount * unitMinutes)
                                .unitPrice(unitPrice)
                                .originalAmount(chargedTotal)
                                .free(false)
                                .chargedAmount(chargedTotal.setScale(2, RoundingMode.HALF_UP))
                                .accumulatedAmount(accumulated)
                                .ruleData(seg.getRuleData())
                                .compact(chargedCount > 1)
                                .count(chargedCount)
                                .isTruncated(false)
                                .build());
                        cursor = cursor.plusMinutes((long) chargedCount * unitMinutes);
                    }
                    if (partialUsed > 0) {
                        accumulated = accumulated.add(partialCharged);
                        cycleAccumulated = cycleAccumulated.add(partialCharged);
                        periodAccumulated = periodAccumulated.add(partialCharged);
                        units.add(BillingUnit.builder()
                                .beginTime(cursor)
                                .endTime(cursor.plusMinutes(unitMinutes))
                                .durationMinutes(unitMinutes)
                                .unitPrice(unitPrice)
                                .originalAmount(originalPerSub)
                                .free(false)
                                .freePromotionId(capHit)
                                .chargedAmount(partialCharged.setScale(2, RoundingMode.HALF_UP))
                                .accumulatedAmount(accumulated)
                                .ruleData(seg.getRuleData())
                                .compact(false)
                                .count(1)
                                .isTruncated(false)
                                .build());
                        cursor = cursor.plusMinutes(unitMinutes);
                    }
                    if (freeCount > 0) {
                        units.add(BillingUnit.builder()
                                .beginTime(cursor)
                                .endTime(compactEnd)
                                .durationMinutes(freeCount * unitMinutes)
                                .unitPrice(unitPrice)
                                .originalAmount(BigDecimal.ZERO)
                                .free(true)
                                .freePromotionId(capHit)
                                .chargedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                                .accumulatedAmount(accumulated)
                                .ruleData(seg.getRuleData())
                                .compact(freeCount > 1)
                                .count(freeCount)
                                .isTruncated(false)
                                .build());
                    }
                } else {
                    compactCharged = compactCharged.setScale(2, RoundingMode.HALF_UP);
                    accumulated = accumulated.add(compactCharged);
                    cycleAccumulated = cycleAccumulated.add(compactCharged);
                    periodAccumulated = periodAccumulated.add(compactCharged);
                    LocalDateTime compactEnd = seg.getBeginTime().plusMinutes((long) subCount * unitMinutes);
                    units.add(BillingUnit.builder()
                            .beginTime(seg.getBeginTime())
                            .endTime(compactEnd)
                            .durationMinutes(subCount * unitMinutes)
                            .unitPrice(unitPrice)
                            .originalAmount(fullTotal)
                            .free(false)
                            .chargedAmount(compactCharged)
                            .accumulatedAmount(accumulated)
                            .ruleData(seg.getRuleData())
                            .compact(subCount > 1)
                            .count(subCount)
                            .isTruncated(false)
                            .build());
                }
            }

            // truncated 部分：不足一个整单元的余数，按不足单元计费模式处理（isTruncated=true），受 combined 预算限制
            if (remainder > 0) {
                BigDecimal truncOrig = ContinuousStrategy.computeIncompleteCharge(unitPrice, remainder, unitMinutes,
                        semantics.incompleteMode(config),
                        semantics.thresholdMinutes(config),
                        semantics.thresholdRatio(config));
                BigDecimal truncCycleBudget = (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0)
                        ? maxCharge.subtract(cycleAccumulated) : null;
                if (truncCycleBudget != null && truncCycleBudget.signum() < 0) truncCycleBudget = BigDecimal.ZERO;
                BigDecimal truncPeriodBudget = (hasPeriodCap && currentPeriodCap != null && currentPeriodCap.compareTo(BigDecimal.ZERO) > 0)
                        ? currentPeriodCap.subtract(periodAccumulated) : null;
                if (truncPeriodBudget != null && truncPeriodBudget.signum() < 0) truncPeriodBudget = BigDecimal.ZERO;
                BigDecimal truncBudget = truncCycleBudget;
                String truncCapHit = capLabel;
                if (truncPeriodBudget != null && (truncBudget == null || truncPeriodBudget.compareTo(truncBudget) < 0)) {
                    truncBudget = truncPeriodBudget;
                    truncCapHit = "PERIOD_CAP";
                }
                BigDecimal truncCharged = truncBudget != null ? truncOrig.min(truncBudget) : truncOrig;
                truncCharged = truncCharged.setScale(2, RoundingMode.HALF_UP);
                boolean truncModeFree = ContinuousStrategy.isIncompleteFree(remainder, unitMinutes,
                        semantics.incompleteMode(config),
                        semantics.thresholdMinutes(config),
                        semantics.thresholdRatio(config));
                boolean truncFree = truncModeFree || truncCharged.compareTo(BigDecimal.ZERO) == 0;
                LocalDateTime truncBegin = seg.getBeginTime().plusMinutes((long) subCount * unitMinutes);
                accumulated = accumulated.add(truncCharged);
                if (!truncFree) {
                    cycleAccumulated = cycleAccumulated.add(truncCharged);
                    periodAccumulated = periodAccumulated.add(truncCharged);
                }
                units.add(BillingUnit.builder()
                        .beginTime(truncBegin)
                        .endTime(seg.getEndTime())
                        .durationMinutes(remainder)
                        .unitPrice(unitPrice)
                        .originalAmount(truncOrig)
                        .free(truncFree)
                        .freePromotionId(truncFree ? (truncModeFree ? "INCOMPLETE_FREE" : truncCapHit) : null)
                        .chargedAmount(truncCharged)
                        .accumulatedAmount(accumulated)
                        .ruleData(seg.getRuleData())
                        .compact(false)
                        .count(1)
                        .isTruncated(true)
                        .build());
            }

            // 周期切换：seg 越过当前周期边界时重置累计 + 推进边界
            if (semantics.isCycleBoundary(seg, currentCycleBoundary, cycleOrigin)) {
                currentCycleBoundary = semantics.nextCycleBoundary(seg.getEndTime(), currentCycleBoundary, cycleOrigin);
                cycleAccumulated = BigDecimal.ZERO;
            }
        }

        return units;
    }

    /**
     * 计算时间点相对周期起点的偏移分钟数（供 Semantics 实现复用）。
     *
     * @param cycleOrigin 周期起点
     * @param time        目标时间点
     * @return 偏移分钟数（time - cycleOrigin）
     */
    public static long minutesFromOrigin(LocalDateTime cycleOrigin, LocalDateTime time) {
        return Duration.between(cycleOrigin, time).toMinutes();
    }

    // ==================== BUBBLE 校验（CONTINUOUS 模式约束） ====================

    /**
     * 校验 CONTINUOUS 模式不支持 BUBBLE 免费时段。
     * <p>
     * CONTINUOUS 的周期切换/cap 逻辑分散在各规则族 Semantics + applyCapAndAccumulate，
     * 未消费 bubble 语义（bubble 段会被当作普通免费段，占用周期，违背 bubble 设计）。
     * 故 CONTINUOUS 模式遇 BUBBLE 免费段直接报错，引导改用 DURATION_PERIOD/DURATION_GLOBAL
     * （两者已支持 bubble：PERIOD 按 effective 周期切，GLOBAL 按 cycleCount 减 bubble 时长）。
     *
     * @param freeTimeRanges 免费段列表
     * @throws IllegalArgumentException 存在 BUBBLE 段时抛出
     */
    public static void assertNoBubbleSupported(List<FreeTimeRange> freeTimeRanges) {
        if (freeTimeRanges == null || freeTimeRanges.isEmpty()) return;
        for (FreeTimeRange range : freeTimeRanges) {
            if (range.getRangeType() == FreeTimeRangeType.BUBBLE) {
                throw new IllegalArgumentException(
                        "CONTINUOUS 模式不支持 BUBBLE 免费时段，请改用 DURATION_PERIOD 或 DURATION_GLOBAL 模式");
            }
        }
    }

    // ==================== 简化计算框架 ====================

    /**
     * 检查简化计算是否启用。
     * <p>
     * 从 {@code AbstractTimeBasedRule} 搬入；{@code cycleCapAmount} 由调用方通过
     * {@link RuleSemantics#cycleCap} 解析后传入，避免对 {@code getCycleCapAmount} 的继承依赖。
     *
     * @param config         规则配置（getSimplifiedSupported 可显式禁用）
     * @param configResolver 配置解析器（getSimplifiedCycleThreshold 阈值，0=禁用）
     * @param context        计费上下文（disableSimplification 可禁用）
     * @param cycleCapAmount 周期封顶金额（必须 >0 才简化）
     * @return true 启用简化
     */
    public static <C extends RuleConfig> boolean isSimplificationEnabled(C config,
                                                                          BillingConfigResolver configResolver,
                                                                          BillingContext context,
                                                                          BigDecimal cycleCapAmount) {
        if (context != null && Boolean.TRUE.equals(context.getDisableSimplification())) {
            return false;
        }
        // 配置明确禁用
        if (config.getSimplifiedSupported() != null && !config.getSimplifiedSupported()) {
            return false;
        }
        // 阈值为 0 表示禁用
        int threshold = configResolver.getSimplifiedCycleThreshold();
        if (threshold <= 0) {
            return false;
        }
        // 封顶金额必须有效
        return cycleCapAmount != null && cycleCapAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 计算周期边界时间。
     *
     * @param cycleIndex    周期索引（0-based）
     * @param calcBegin     计算起点
     * @param cycleMinutes  周期长度（分钟），由调用方通过 {@link RuleSemantics#cycleMinutes} 传入
     * @return 该周期的起始时间
     */
    public static LocalDateTime getCycleBoundary(int cycleIndex, LocalDateTime calcBegin, int cycleMinutes) {
        return calcBegin.plusMinutes((long) cycleIndex * cycleMinutes);
    }

    /**
     * 构建简化单元。
     * <p>
     * 从 {@code AbstractTimeBasedRule} 搬入；{@code cycleMinutes} 由调用方传入，
     * 避免对 {@code getCycleMinutes} 的继承依赖。
     *
     * @param beginCycleIndex 起始周期索引（0-based）
     * @param cycleCount      周期数（连续无优惠完整周期）
     * @param cycleCapAmount  周期封顶金额（每周期 chargedAmount）
     * @param calcBegin       计算起点（周期边界锚定）
     * @param cycleMinutes    周期长度（分钟）
     * @return 简化 BillingUnit（chargedAmount = cycleCapAmount × cycleCount，ruleData.isSimplified=true）
     */
    public static BillingUnit buildSimplifiedUnit(int beginCycleIndex,
                                                   int cycleCount,
                                                   BigDecimal cycleCapAmount,
                                                   LocalDateTime calcBegin,
                                                   int cycleMinutes) {

        LocalDateTime beginTime = getCycleBoundary(beginCycleIndex, calcBegin, cycleMinutes);
        LocalDateTime endTime = getCycleBoundary(beginCycleIndex + cycleCount, calcBegin, cycleMinutes);
        BigDecimal totalAmount = cycleCapAmount.multiply(BigDecimal.valueOf(cycleCount));

        // ruleData 标记简化单元（供结果识别：isSimplified=true，含周期索引/数量/封顶金额）
        Map<String, Object> ruleData = new HashMap<>();
        ruleData.put("cycleIndex", beginCycleIndex);
        ruleData.put("simplifiedCycleCount", cycleCount);
        ruleData.put("simplifiedCycleAmount", cycleCapAmount);
        ruleData.put("isSimplified", true);

        return BillingUnit.builder()
                .beginTime(beginTime)
                .endTime(endTime)
                .durationMinutes((int) Duration.between(beginTime, endTime).toMinutes())
                .unitPrice(cycleCapAmount)
                .originalAmount(totalAmount)
                .chargedAmount(totalAmount)
                .ruleData(ruleData)
                .build();
    }

    // ==================== 不足单元计费（公共工具） ====================

    /**
     * 按不足单元计费模式计算截断单元的实际收费金额。
     * <p>
     * 仅用于 isTruncated=true 的单元（segMinutes &lt; unitMinutes）。
     * <ul>
     *   <li>FULL_CHARGE：unitPrice（不足也收全额）</li>
     *   <li>PROPORTIONAL：unitPrice × segMinutes / unitMinutes</li>
     *   <li>FREE：0</li>
     *   <li>THRESHOLD_MINUTES：segMinutes ≥ thresholdMinutes ? unitPrice : 0</li>
     *   <li>THRESHOLD_RATIO：ratio = segMinutes/unitMinutes ≥ thresholdRatio ? unitPrice × ratio : 0</li>
     * </ul>
     *
     * @param unitPrice        完整单元单价
     * @param segMinutes       截断单元实际时长
     * @param unitMinutes      完整单元时长
     * @param mode             不足单元计费模式（null 视为 FULL_CHARGE）
     * @param thresholdMinutes THRESHOLD_MINUTES 阈值（null 视为 0）
     * @param thresholdRatio   THRESHOLD_RATIO 阈值（null 视为 0，即总是按比例）
     * @return 截断单元实际收费金额（scale=2, HALF_UP）
     */
    public static BigDecimal computeIncompleteCharge(BigDecimal unitPrice,
                                                       int segMinutes,
                                                       int unitMinutes,
                                                       BConstants.IncompleteUnitChargeMode mode,
                                                       Integer thresholdMinutes,
                                                       BigDecimal thresholdRatio) {
        if (unitPrice == null) unitPrice = BigDecimal.ZERO;
        if (mode == null) mode = BConstants.IncompleteUnitChargeMode.FULL_CHARGE;
        if (segMinutes >= unitMinutes || unitMinutes <= 0) {
            return unitPrice.setScale(2, RoundingMode.HALF_UP);
        }

        switch (mode) {
            case FULL_CHARGE:
                return unitPrice.setScale(2, RoundingMode.HALF_UP);
            case PROPORTIONAL:
                return unitPrice.multiply(BigDecimal.valueOf(segMinutes))
                        .divide(BigDecimal.valueOf(unitMinutes), 2, RoundingMode.HALF_UP);
            case FREE:
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            case THRESHOLD_MINUTES: {
                int threshold = thresholdMinutes != null ? thresholdMinutes : 0;
                return segMinutes >= threshold
                        ? unitPrice.setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            case THRESHOLD_RATIO: {
                BigDecimal ratio = BigDecimal.valueOf(segMinutes)
                        .divide(BigDecimal.valueOf(unitMinutes), 6, RoundingMode.HALF_UP);
                BigDecimal threshold = thresholdRatio != null ? thresholdRatio : BigDecimal.ZERO;
                if (ratio.compareTo(threshold) >= 0) {
                    // 达到阈值：按比例收（非全额）
                    return unitPrice.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                }
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            default:
                return unitPrice.setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * 判定不足单元在该模式下是否免费（用于设置 free/freePromotionId）。
     * <p>
     * FREE：总是免费；THRESHOLD_MINUTES：segMinutes &lt; thresholdMinutes 免费；
     * THRESHOLD_RATIO：ratio &lt; thresholdRatio 免费；FULL_CHARGE/PROPORTIONAL：不免费。
     *
     * @param segMinutes       截断单元实际时长
     * @param unitMinutes      完整单元时长
     * @param mode             不足单元计费模式
     * @param thresholdMinutes THRESHOLD_MINUTES 阈值（null 视为 0）
     * @param thresholdRatio   THRESHOLD_RATIO 阈值（null 视为 0）
     * @return true 表示该截断单元免费
     */
    public static boolean isIncompleteFree(int segMinutes,
                                            int unitMinutes,
                                            BConstants.IncompleteUnitChargeMode mode,
                                            Integer thresholdMinutes,
                                            BigDecimal thresholdRatio) {
        if (mode == null) return false;
        if (segMinutes >= unitMinutes || unitMinutes <= 0) return false;
        switch (mode) {
            case FREE:
                return true;
            case THRESHOLD_MINUTES:
                return segMinutes < (thresholdMinutes != null ? thresholdMinutes : 0);
            case THRESHOLD_RATIO: {
                BigDecimal ratio = BigDecimal.valueOf(segMinutes)
                        .divide(BigDecimal.valueOf(unitMinutes), 6, RoundingMode.HALF_UP);
                BigDecimal threshold = thresholdRatio != null ? thresholdRatio : BigDecimal.ZERO;
                return ratio.compareTo(threshold) < 0;
            }
            default:
                return false;
        }
    }
}
