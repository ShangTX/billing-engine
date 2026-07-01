package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.HomogeneousSegmentCalculator;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.billing.value.UnitValueSpec;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按相对时间段计费规则
 * <p>
 * 核心逻辑：
 * 1. 从计费起点开始，按 24 小时划分周期
 * 2. 每个周期内按配置的时间段划分，每个时间段可有不同的单元长度和单价
 * 3. 计费单元在时间段边界会被截断，不足一个单元长度的部分收全额
 * 4. 每个周期独立封顶，超出时从最后一个单元开始削减
 * 5. 免费时段完全覆盖计费单元才免费
 */
public class RelativeTimeRule extends AbstractTimeBasedRule<RelativeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440; // 24小时 = 1440分钟

    // 规则类型标识（用于 ruleState Map 的 key）
    private static final String RULE_TYPE = "relativeTime";
    private final RelativeTimeContinuousCalculator continuousCalculator = new RelativeTimeContinuousCalculator();
    private final RelativeTimePeriodResolver periodResolver = new RelativeTimePeriodResolver();
    private final RelativeTimeContinuousCapHandler continuousCapHandler = new RelativeTimeContinuousCapHandler();
    private final RelativeTimeSimplifiedCycleStateManager simplifiedCycleStateManager = new RelativeTimeSimplifiedCycleStateManager();

    @Override
    protected String getRuleType() {
        return RULE_TYPE;
    }

    @Override
    protected boolean hasComplexFeatures(RelativeTimeConfig config) {
        // RelativeTimeRule 无时间段封顶等复杂特性
        return false;
    }

    @Override
    protected boolean isSimplifiedSupported(RelativeTimeConfig config) {
        // RelativeTimeRule 支持简化计算
        return true;
    }

    @Override
    protected BigDecimal getCycleCapAmount(RelativeTimeConfig config) {
        return config.getMaxChargeOneCycle();
    }

    @Override
    public Class<RelativeTimeConfig> configClass() {
        return RelativeTimeConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return EnumSet.of(BConstants.BillingMode.CONTINUOUS);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        return continuousCalculator.calculate(this, context, config, promotionAggregate);
    }

    /**
     * 验证配置有效性
     */
    private void validateConfig(RelativeTimeConfig config) {
        // 检查封顶金额（必填）
        if (config.getMaxChargeOneCycle() == null) {
            throw new IllegalArgumentException("maxChargeOneCycle is required");
        }
        if (config.getMaxChargeOneCycle().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneCycle must be positive");
        }

        List<RelativeTimePeriod> periods = config.getPeriods();
        if (periods == null || periods.isEmpty()) {
            throw new IllegalArgumentException("Periods cannot be empty");
        }

        // 检查首时间段
        if (periods.get(0).getBeginMinute() != 0) {
            throw new IllegalArgumentException("First period must start at minute 0");
        }

        // 检查末时间段
        if (periods.get(periods.size() - 1).getEndMinute() != MINUTES_PER_CYCLE) {
            throw new IllegalArgumentException("Last period must end at minute 1440");
        }

        // 检查相邻时间段首尾相连
        for (int i = 0; i < periods.size() - 1; i++) {
            if (periods.get(i).getEndMinute() != periods.get(i + 1).getBeginMinute()) {
                throw new IllegalArgumentException("Periods must be contiguous: period " + i + " ends at " +
                        periods.get(i).getEndMinute() + " but period " + (i + 1) + " starts at " +
                        periods.get(i + 1).getBeginMinute());
            }
        }

        // 检查每个时间段的有效性
        for (int i = 0; i < periods.size(); i++) {
            RelativeTimePeriod period = periods.get(i);
            if (period.getBeginMinute() >= period.getEndMinute()) {
                throw new IllegalArgumentException("Invalid period " + i + ": beginMinute must be less than endMinute");
            }
            if (period.getUnitMinutes() <= 0) {
                throw new IllegalArgumentException("Invalid unitMinutes in period " + i);
            }
            if (period.getUnitPrice() == null || period.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Invalid unitPrice in period " + i);
            }
        }
    }

    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> billingUnits) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }
        return billingUnits.get(billingUnits.size() - 1).getBeginTime();
    }

    /**
     * 计算费用稳定结束时间
     */
    private LocalDateTime calculateEffectiveTo(List<BillingUnit> billingUnits,
                                                List<FreeTimeRange> freeTimeRanges,
                                                LocalDateTime calcBegin,
                                                LocalDateTime calcEnd) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }

        BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
        LocalDateTime effectiveTo = lastUnit.getEndTime();

        // 如果最后一个单元在免费时段内，延伸到免费时段结束
        if (lastUnit.isFree() && lastUnit.getFreePromotionId() != null) {
            FreeTimeRange coveringRange = findFreeTimeRangeById(lastUnit.getFreePromotionId(), freeTimeRanges);
            if (coveringRange != null && coveringRange.getEndTime().isAfter(effectiveTo)) {
                effectiveTo = coveringRange.getEndTime();
            }
        }

        // 检查下一个周期边界
        LocalDateTime currentCycleEnd = calcBegin;
        while (!currentCycleEnd.isAfter(effectiveTo)) {
            LocalDateTime nextCycleEnd = currentCycleEnd.plusMinutes(MINUTES_PER_CYCLE);
            if (nextCycleEnd.isAfter(effectiveTo)) {
                effectiveTo = nextCycleEnd.isBefore(effectiveTo) ? nextCycleEnd : effectiveTo;
                break;
            }
            currentCycleEnd = nextCycleEnd;
        }

        // 不超过分段结束时间
        if (calcEnd != null && effectiveTo.isAfter(calcEnd)) {
            effectiveTo = calcEnd;
        }

        return effectiveTo;
    }

    /**
     * 根据ID查找免费时段
     */
    private FreeTimeRange findFreeTimeRangeById(String id, List<FreeTimeRange> freeTimeRanges) {
        if (freeTimeRanges == null || id == null) {
            return null;
        }
        for (FreeTimeRange range : freeTimeRanges) {
            if (id.equals(range.getId())) {
                return range;
            }
        }
        return null;
    }

    /**
     * CONTINUOUS 模式计算
     * 在免费时段边界切分时间轴，每个片段从片段起点重新按单元划分
     */
    BillingSegmentResult calculateContinuousInternal(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        validateConfig(config);

        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();
        LocalDateTime cycleOriginBegin = context.getBeginTime();

        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = initializeState(calcBegin);
        } else {
            while (state.getCycleBoundary() != null && !calcBegin.isBefore(state.getCycleBoundary())) {
                state.setCycleIndex(state.getCycleIndex() + 1);
                state.setCycleAccumulated(BigDecimal.ZERO);
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(getCycleMinutes()));
            }
        }

        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges()
                : List.of();
        BigDecimal maxCharge = config.getMaxChargeOneCycle();
        List<RelativeTimePeriod> periods = config.getPeriods();

        // 边界来源：周期结束（24h）+ 时段结束 + 免费时段起止 + 单元对齐 + calcEnd
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(BoundaryProviders.cycleEnd(cycleOriginBegin, getCycleMinutes()));
        // 周期内 period 结束：从当前位置算到下一个 period.endMinute
        providers.add((current, end) -> {
            List<LocalDateTime> result = new ArrayList<>();
            long minutesFromOrigin = Duration.between(cycleOriginBegin, current).toMinutes();
            long positionInCycle = ((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE;
            long cycleCount = minutesFromOrigin / MINUTES_PER_CYCLE;
            if (minutesFromOrigin < 0 && minutesFromOrigin % MINUTES_PER_CYCLE != 0) cycleCount--;
            LocalDateTime cycleStart = cycleOriginBegin.plusMinutes(cycleCount * MINUTES_PER_CYCLE);
            for (RelativeTimePeriod period : periods) {
                long periodEndMinute = period.getEndMinute();
                if (periodEndMinute > positionInCycle) {
                    LocalDateTime boundary = cycleStart.plusMinutes(periodEndMinute);
                    if (boundary.isAfter(current) && !boundary.isAfter(end)) {
                        result.add(boundary);
                    }
                    break;
                }
            }
            return result;
        });
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        // 单元对齐：每个 unitMinutes 步长产生一个边界
        providers.add((current, end) -> {
            List<LocalDateTime> result = new ArrayList<>();
            // 找到当前点的 unitMinutes 网格：向下对齐到单元起点
            long minutesFromOrigin = Duration.between(cycleOriginBegin, current).toMinutes();
            RelativeTimePeriod period = periodResolver.findPeriodForMinute(
                    (int) (((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE),
                    periods);
            int unitMinutes = period.getUnitMinutes();
            LocalDateTime next = current.plusMinutes(unitMinutes);
            while (!next.isAfter(end)) {
                result.add(next);
                next = next.plusMinutes(unitMinutes);
            }
            return result;
        });
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        // 边界驱动循环
        List<HomogeneousSegment> segments = runBoundaryDrivenLoop(calcBegin, calcEnd, providers, (current, next) -> {
            // 检查是否被免费时段完全覆盖
            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                    return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                            true, range.getId(), null, null);
                }
            }
            // 计费段：根据当前位置找 period
            long minutesFromOrigin = Duration.between(cycleOriginBegin, current).toMinutes();
            int positionInCycle = (int) (((minutesFromOrigin % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE);
            RelativeTimePeriod period = periodResolver.findPeriodForMinute(positionInCycle, periods);
            int unitMinutes = period.getUnitMinutes();
            BigDecimal unitPrice = period.getUnitPrice();
            // 不足单元也收全额：segment 时长 = next - current（由 boundary-driven 已对齐到 unit grid 或 boundary）
            return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                    false, null, null, null);
        });

        // 应用封顶 + 累计 + 截断标记（自然日 24h 周期内统计）
        ContinuousResult capResult = applyCapAndAccumulate(segments, maxCharge, state.getCycleAccumulated(),
                context, periods, periodResolver, cycleOriginBegin, calcBegin, config);
        List<BillingUnit> allUnits = capResult.units;
        BigDecimal lastCycleAccumulated = capResult.lastCycleAccumulated;

        // 简化计算模式：边界驱动已直接产出 compact 单元，简化路径暂不再叠加
        // （简化与 compact 正交，后续可在 compact 基础上进一步合并无优惠周期）
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && isSimplificationEnabled(config, context.getBillingConfigResolver(), context);
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;
        if (simplificationEnabled && threshold > 0) {
            // 预留：简化路径已由 compact 合并替代，此处保留判断供后续扩展
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(allUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(allUnits, freeTimeRanges, calcBegin, calcEnd);

        // 标记最后一个单元是否被截断
        if (!allUnits.isEmpty()) {
            BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
            int minutesFromCycleStart = (int) Duration.between(cycleOriginBegin, lastUnit.getBeginTime()).toMinutes();
            RelativeTimePeriod period = periodResolver.findPeriodForMinute(
                    ((minutesFromCycleStart % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE,
                    periods);
            int unitMinutes = period.getUnitMinutes();
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        // 计算累计金额
        BigDecimal accumulatedAmount = context.getPreviousAccumulatedAmount();
        if (accumulatedAmount == null) {
            accumulatedAmount = BigDecimal.ZERO;
        }
        BigDecimal truncatedUnitChargedAmount = context.getTruncatedUnitChargedAmount();
        if (truncatedUnitChargedAmount != null && !allUnits.isEmpty()) {
            accumulatedAmount = accumulatedAmount.subtract(truncatedUnitChargedAmount);
            if (accumulatedAmount.compareTo(BigDecimal.ZERO) < 0) {
                accumulatedAmount = BigDecimal.ZERO;
            }
        }
        for (BillingUnit unit : allUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        // 更新状态
        if (!allUnits.isEmpty()) {
            // 周期索引由 cycleBoundary 推进逻辑维护，此处仅同步最后周期累计
            state.setCycleAccumulated(lastCycleAccumulated);
            int bubbleExtension = calculateBubbleExtension(freeTimeRanges, calcBegin, calcEnd);
            if (state.getCycleBoundary() != null) {
                state.setCycleBoundary(state.getCycleBoundary().plusMinutes(bubbleExtension));
            } else {
                state.setCycleBoundary(cycleOriginBegin.plusMinutes(
                        (long) ((Duration.between(cycleOriginBegin, calcEnd).toMinutes() / MINUTES_PER_CYCLE) + 1) * MINUTES_PER_CYCLE
                ).plusMinutes(bubbleExtension));
            }
        }

        Map<String, Object> ruleOutputState = buildRuleOutputState(state);

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(allUnits)
                .promotionUsages(new ArrayList<>())
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(feeEffectiveStart)
                .feeEffectiveEnd(feeEffectiveEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }

    /**
     * 把同质段列表转换为 BillingUnit 列表，并应用封顶逻辑、累计金额、截断标记。
     * <p>
     * 封顶：按 24h 周期内累计，达到 maxCharge 后剩余段变为免费（CYCLE_CAP）。
     * 累计：基于 previousAccumulatedAmount + 截断单元已扣金额。
     * 截断：最后一个段的 duration &lt; 单元长度且 endTime == calcEnd 时标记 isTruncated。
     */
    private ContinuousResult applyCapAndAccumulate(List<HomogeneousSegment> segments,
                                                    BigDecimal maxCharge,
                                                    BigDecimal carryOverAccumulated,
                                                    BillingContext context,
                                                    List<RelativeTimePeriod> periods,
                                                    RelativeTimePeriodResolver resolver,
                                                    LocalDateTime cycleOriginBegin,
                                                    LocalDateTime calcBegin,
                                                    RelativeTimeConfig config) {
        List<BillingUnit> units = new ArrayList<>();
        if (segments.isEmpty()) {
            return new ContinuousResult(units, carryOverAccumulated != null ? carryOverAccumulated : BigDecimal.ZERO);
        }

        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        BigDecimal cycleAccumulated = carryOverAccumulated != null ? carryOverAccumulated : BigDecimal.ZERO;
        long calcBeginOffset = Duration.between(cycleOriginBegin, calcBegin).toMinutes();
        long nextCycleBoundaryOffset = ((calcBeginOffset / MINUTES_PER_CYCLE) + 1) * MINUTES_PER_CYCLE;
        BigDecimal lastCycleAccumulated = cycleAccumulated;

        BigDecimal accumulated = context.getPreviousAccumulatedAmount();
        if (accumulated == null) accumulated = BigDecimal.ZERO;
        BigDecimal truncatedDeduction = context.getTruncatedUnitChargedAmount();

        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);

            // 截断判定提前
            int segMinutes = seg.durationMinutes();
            int positionInCycle = (int) (((Duration.between(cycleOriginBegin, seg.getBeginTime()).toMinutes() % MINUTES_PER_CYCLE) + MINUTES_PER_CYCLE) % MINUTES_PER_CYCLE);
            RelativeTimePeriod period = resolver.findPeriodForMinute(positionInCycle, periods);
            int unitMinutes = period.getUnitMinutes();
            int subCount = unitMinutes > 0 ? segMinutes / unitMinutes : 1;
            if (subCount < 1) subCount = 1;

            boolean isTruncated = isLast
                    && unitMinutes > 0
                    && segMinutes < unitMinutes
                    && seg.getEndTime().equals(calcEnd);

            boolean cycleCapped = false;
            if (maxCharge != null && maxCharge.compareTo(BigDecimal.ZERO) > 0
                    && !seg.isFree() && cycleAccumulated.compareTo(maxCharge) >= 0) {
                cycleCapped = true;
            }

            // 不足单元是否按模式免费
            boolean incompleteFree = isTruncated && !seg.isFree() && !cycleCapped
                    && isIncompleteFree(segMinutes, unitMinutes, config.getIncompleteUnitChargeMode(),
                            config.getThresholdMinutes(), config.getThresholdRatio());

            BigDecimal originalPerSub = seg.getOriginalAmount() != null
                    ? seg.getOriginalAmount() : BigDecimal.ZERO;
            BigDecimal unitPrice = seg.getUnitPrice() != null ? seg.getUnitPrice() : BigDecimal.ZERO;

            BigDecimal charged;
            if (seg.isFree() || cycleCapped || incompleteFree) {
                charged = BigDecimal.ZERO;
            } else if (isTruncated) {
                charged = computeIncompleteCharge(unitPrice, segMinutes, unitMinutes,
                        config.getIncompleteUnitChargeMode(),
                        config.getThresholdMinutes(), config.getThresholdRatio());
            } else {
                BigDecimal budget = maxCharge != null
                        ? maxCharge.subtract(cycleAccumulated)
                        : null;
                if (budget != null && budget.signum() < 0) budget = BigDecimal.ZERO;
                BigDecimal fullTotal = originalPerSub.multiply(BigDecimal.valueOf(subCount));
                if (budget != null && fullTotal.compareTo(budget) > 0) {
                    charged = budget.setScale(2, RoundingMode.HALF_UP);
                } else {
                    charged = fullTotal;
                }
            }

            if (truncatedDeduction != null && i == 0) {
                BigDecimal adjusted = charged.subtract(truncatedDeduction);
                if (adjusted.signum() < 0) adjusted = BigDecimal.ZERO;
                charged = adjusted;
            }

            accumulated = accumulated.add(charged);
            if (!seg.isFree() && !cycleCapped && !incompleteFree) {
                cycleAccumulated = cycleAccumulated.add(charged);
            }
            lastCycleAccumulated = cycleAccumulated;

            boolean isCompact = !isTruncated && subCount > 1;

            // valueSpec
            UnitValueSpec spec;
            if (isTruncated && !seg.isFree() && !cycleCapped && !incompleteFree) {
                spec = computeIncompleteValueSpec(unitPrice, segMinutes, unitMinutes,
                        config.getIncompleteUnitChargeMode(),
                        config.getThresholdMinutes(), config.getThresholdRatio());
            } else {
                spec = seg.getValueSpec() instanceof UnitValueSpec us ? us
                        : new FixedValueSpec(seg.isFree() || incompleteFree ? BigDecimal.ZERO : unitPrice);
            }
            boolean cappedOrReduced = cycleCapped
                    || (charged.compareTo(originalPerSub.multiply(BigDecimal.valueOf(subCount))) < 0
                        && !seg.isFree() && !isTruncated);
            if (cappedOrReduced) {
                spec = new FixedValueSpec(charged);
            }

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(seg.getBeginTime())
                    .endTime(seg.getEndTime())
                    .durationMinutes(segMinutes)
                    .unitPrice(unitPrice)
                    .originalAmount(originalPerSub.multiply(BigDecimal.valueOf(subCount)))
                    .free(seg.isFree() || cycleCapped || incompleteFree)
                    .freePromotionId(cycleCapped ? "CYCLE_CAP"
                            : (incompleteFree ? "INCOMPLETE_FREE" : seg.getFreePromotionId()))
                    .chargedAmount(charged)
                    .accumulatedAmount(accumulated)
                    .valueSpec(spec)
                    .ruleData(seg.getRuleData())
                    .compact(isCompact)
                    .count(isCompact ? subCount : 1)
                    .isTruncated(isTruncated)
                    .build();
            units.add(unit);

            // 24h 周期切换：段终点越过下一个周期边界时，进入新周期，重置累计
            long offsetFromOrigin = Duration.between(cycleOriginBegin, seg.getEndTime()).toMinutes();
            if (offsetFromOrigin >= nextCycleBoundaryOffset) {
                nextCycleBoundaryOffset = ((offsetFromOrigin / MINUTES_PER_CYCLE) + 1) * MINUTES_PER_CYCLE;
                cycleAccumulated = BigDecimal.ZERO;
                // 周期切换后，lastCycleAccumulated 将由后续段更新；若无后续段，保持为 0（新周期无单元）
                lastCycleAccumulated = BigDecimal.ZERO;
            }
        }
        return new ContinuousResult(units, lastCycleAccumulated);
    }

    /**
     * CONTINUOUS 边界驱动结果：单元列表 + 最后一个周期的累计金额（用于状态输出）。
     */
    private static final class ContinuousResult {
        final List<BillingUnit> units;
        final BigDecimal lastCycleAccumulated;

        ContinuousResult(List<BillingUnit> units, BigDecimal lastCycleAccumulated) {
            this.units = units;
            this.lastCycleAccumulated = lastCycleAccumulated;
        }
    }

    /**
     * 为一个周期生成计费单元
     */
    private List<BillingUnit> generateUnitsForCycle(CycleFragments cycle, RelativeTimeConfig config) {
        List<BillingUnit> units = new ArrayList<>();

        for (TimeFragment fragment : cycle.fragments) {
            if (fragment.isFree) {
                BillingUnit unit = BillingUnit.builder()
                        .beginTime(fragment.beginTime)
                        .endTime(fragment.endTime)
                        .durationMinutes((int) Duration.between(fragment.beginTime, fragment.endTime).toMinutes())
                        .unitPrice(BigDecimal.ZERO)
                        .originalAmount(BigDecimal.ZERO)
                        .free(true)
                        .freePromotionId(fragment.freePromotionId)
                        .chargedAmount(BigDecimal.ZERO)
                        .build();
                units.add(unit);
            } else {
                // 根据片段开始时间查找对应的 period
                units.addAll(generateUnitsForFragment(fragment, cycle.cycleStart, config));
            }
        }

        return units;
    }

    /**
     * 为一个片段生成计费单元
     */
    private List<BillingUnit> generateUnitsForFragment(TimeFragment fragment, LocalDateTime cycleStart, RelativeTimeConfig config) {
        List<BillingUnit> units = new ArrayList<>();

        LocalDateTime current = fragment.beginTime;

        while (current.isBefore(fragment.endTime)) {
            // 找到当前时间点对应的 period
            int minutesFromCycleStart = (int) Duration.between(cycleStart, current).toMinutes();
            RelativeTimePeriod period = periodResolver.findPeriodForMinute(minutesFromCycleStart, config.getPeriods());

            int unitMinutes = period.getUnitMinutes();
            BigDecimal unitPrice = period.getUnitPrice();

            LocalDateTime unitEnd = current.plusMinutes(unitMinutes);

            // 截断到片段边界
            if (unitEnd.isAfter(fragment.endTime)) {
                unitEnd = fragment.endTime;
            }

            // 截断到 period 边界
            int periodEndMinute = period.getEndMinute();
            LocalDateTime periodEnd = periodResolver.resolvePeriodEnd(cycleStart, period);
            if (unitEnd.isAfter(periodEnd)) {
                unitEnd = periodEnd;
            }

            int duration = (int) Duration.between(current, unitEnd).toMinutes();

            // 不足单元也收全额
            BigDecimal originalAmount = unitPrice;

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(current)
                    .endTime(unitEnd)
                    .durationMinutes(duration)
                    .unitPrice(unitPrice)
                    .originalAmount(originalAmount)
                    .free(false)
                    .chargedAmount(originalAmount)
                    .build();

            units.add(unit);
            current = unitEnd;
        }

        return units;
    }

}
