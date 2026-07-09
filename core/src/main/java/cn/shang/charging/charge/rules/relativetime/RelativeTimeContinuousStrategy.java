package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BoundaryDrivenLoop;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.ContinuousStrategy;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.RuleSupport;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * `relativeTime` 规则在 CONTINUOUS 模式下的策略实现。
 * <p>
 * 承载 CONTINUOUS 语义：边界驱动切断 + 24h 周期封顶 + 简化计算（预留）。
 * 复用不足单元计费等公共基础设施（{@link ContinuousStrategy}）与 FREE_MINUTES 时段化（{@link RuleSupport}），
 * 不再继承旧基类 {@code AbstractTimeBasedRule}（TODO-20260706-002 阶段7 废弃）。
 * <p>
 * 由 {@link RelativeTimeRule} 门面按 calculationMode=CONTINUOUS 分派调用，不独立注册。
 * 从 {@code RelativeTimeRule} 的 CONTINUOUS 逻辑迁移而来（TODO-20260706-002 阶段2a）。
 */
final class RelativeTimeContinuousStrategy implements BillingRule<RelativeTimeConfig> {

    private static final int MINUTES_PER_CYCLE = 1440; // 24小时

    private final RelativeTimePeriodResolver periodResolver = new RelativeTimePeriodResolver();
    private final RelativeTimeSemantics relativeTimeSemantics = new RelativeTimeSemantics();

    @Override
    public Class<RelativeTimeConfig> configClass() {
        return RelativeTimeConfig.class;
    }

    /**
     * 本策略仅承载 CONTINUOUS 模式；门面 {@link RelativeTimeRule} 声明完整的 supportedCalculationModes。
     * 此方法为接口契约所需，不被 Calculator 直接调用（Calculator 校验门面的 supportedCalculationModes）。
     */
    @Override
    public Set<BConstants.CalculationMode> supportedCalculationModes() {
        return EnumSet.of(BConstants.CalculationMode.CONTINUOUS);
    }

    /**
     * CONTINUOUS 模式计算
     * 在免费时段边界切分时间轴，每个片段从片段起点重新按单元划分
     */
    @Override
    public BillingSegmentResult calculate(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        validateConfig(config);

        CalculationWindow window = context.getWindow();
        LocalDateTime calcBegin = window.getCalculationBegin();
        LocalDateTime calcEnd = window.getCalculationEnd();
        LocalDateTime cycleOriginBegin = context.getBeginTime();

        // 初始化单次计算周期跟踪状态

        // 时段化 FREE_MINUTES（TODO-20260702-004：从 PromotionEngine 下放到策略侧）
        FreeMinuteAllocationResult materialized = RuleSupport.materializeFreeMinutes(promotionAggregate, window);
        final List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();
        // CONTINUOUS 模式不支持 BUBBLE 免费时段（bubble 需 effective 周期，CONTINUOUS 未消费）
        ContinuousStrategy.assertNoBubbleSupported(freeTimeRanges);
        List<RelativeTimePeriod> periods = config.getPeriods();

        // 边界来源：周期结束（24h）+ 时段结束 + 免费时段起止 + 单元对齐 + calcEnd
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add(BoundaryProviders.cycleEnd(cycleOriginBegin, MINUTES_PER_CYCLE));
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
        providers.add(BoundaryProviders.calcEnd(calcEnd));

        // 边界驱动循环
        List<HomogeneousSegment> segments = BoundaryDrivenLoop.run(calcBegin, calcEnd, providers, (current, next) -> {
            // 检查是否被免费时段完全覆盖
            for (FreeTimeRange range : freeTimeRanges) {
                if (!range.getBeginTime().isAfter(current) && !range.getEndTime().isBefore(next)) {
                    return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                            true, range.getId(), null);
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
                    false, null, null);
        });

        // 应用封顶 + 累计 + 截断标记（自然日 24h 周期内统计）
        List<BillingUnit> allUnits = ContinuousStrategy.applyCapAndAccumulate(segments, relativeTimeSemantics,
                context, config, cycleOriginBegin, calcBegin, null);

        // 简化计算模式：边界驱动已直接产出 compact 单元，简化路径暂不再叠加
        // （简化与 compact 正交，后续可在 compact 基础上进一步合并无优惠周期）
        BigDecimal cycleCapAmount = relativeTimeSemantics.cycleCap(config);
        boolean simplificationEnabled = context.getBillingConfigResolver() != null
            && ContinuousStrategy.isSimplificationEnabled(config, context.getBillingConfigResolver(), context, cycleCapAmount);
        int threshold = context.getBillingConfigResolver() != null
            ? context.getBillingConfigResolver().getSimplifiedCycleThreshold()
            : 0;
        if (simplificationEnabled && threshold > 0) {
            // 预留：简化路径已由 compact 合并替代，此处保留判断供后续扩展
        }

        BigDecimal totalAmount = allUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);


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

        // 计算累计金额（每段从 ZERO 开始累加）
        BigDecimal accumulatedAmount = BigDecimal.ZERO;
        for (BillingUnit unit : allUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        // 产出 FREE_RANGE 的 PromotionUsage（CONTINUOUS 免费单元 originalAmount=0）
        List<PromotionUsage> freeRangeUsages = PromotionAggregateUtil.buildFreeRangeUsages(
                freeTimeRanges, calcBegin, calcEnd);
        List<PromotionUsage> allUsages = new ArrayList<>(freeRangeUsages);
        if (materialized.getPromotionUsages() != null) {
            allUsages.addAll(materialized.getPromotionUsages());
        }

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(allUnits)
                .promotionUsages(allUsages)
                .promotionAggregate(promotionAggregate)
                .build();
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

    /**
     * 为一个周期生成计费单元
     */
}
