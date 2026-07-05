package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.BoundaryProvider;
import cn.shang.charging.charge.rules.BoundaryProviders;
import cn.shang.charging.charge.rules.HomogeneousSegment;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 多自然时段计费规则
 * <p>
 * 核心逻辑：
 * 1. 24 小时自然周期，按自然时段划分
 * 2. 每个时段有独立价格，统一单元时长
 * 3. 跨时段处理可配置（复用 CrossPeriodMode）
 * 4. 支持每日封顶
 */
public class NaturalTimeRule extends AbstractTimeBasedRule<NaturalTimeConfig> {

    private static final int MINUTES_PER_DAY = 1440;
    private static final Set<BConstants.BillingMode> SUPPORTED_MODES = Set.of(
            BConstants.BillingMode.CONTINUOUS
    );

    private final NaturalTimeContinuousCalculator continuousCalculator = new NaturalTimeContinuousCalculator();

    @Override
    public Class<NaturalTimeConfig> configClass() {
        return NaturalTimeConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return SUPPORTED_MODES;
    }

    @Override
    public boolean hasComplexFeatures(NaturalTimeConfig config) {
        return config.getMaxChargeOneDay() != null;
    }

    @Override
    protected boolean isSimplifiedSupported(NaturalTimeConfig config) {
        return config.getSimplifiedSupported() == null || config.getSimplifiedSupported();
    }

    @Override
    protected BigDecimal getCycleCapAmount(NaturalTimeConfig config) {
        return config.getMaxChargeOneDay();
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          NaturalTimeConfig config,
                                          PromotionAggregate promotionAggregate) {
        validateConfig(config);
        return continuousCalculator.calculate(this, context, config, promotionAggregate);
    }

    // ==================== 内部计算方法（供 Calculator 调用） ====================

    BillingSegmentResult calculateContinuousInternal(BillingContext context,
                                                     NaturalTimeConfig config,
                                                     PromotionAggregate promotionAggregate,
                                                     NaturalTimePeriodResolver periodResolver,
                                                     NaturalTimeCrossPeriodPriceResolver priceResolver,
                                                     NaturalTimeCycleStateManager cycleStateManager) {
        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        int unitMinutes = config.getUnitMinutes();
        List<NaturalPeriod> periods = config.getPeriods();
        CrossPeriodMode crossPeriodMode = config.getCrossPeriodMode();
        BigDecimal maxCharge = config.getMaxChargeOneDay();

        // 时段化 FREE_MINUTES（TODO-20260702-004：从 PromotionEngine 下放到策略侧）
        FreeMinuteAllocationResult materialized = materializeFreeMinutes(promotionAggregate, context.getWindow());
        List<FreeTimeRange> freeTimeRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();

        // 边界来源：自然时段边界 + 免费时段起止 + 计费单元对齐 + calcEnd
        // 周期对齐在跨周期封顶处由 cycleAccumulated 单独处理
        List<BoundaryProvider> providers = new ArrayList<>();
        providers.add((current, end) -> {
            int currentMinute = current.getHour() * 60 + current.getMinute();
            int periodEnd = periodResolver.findNextPeriodBoundary(currentMinute, periods);
            LocalDateTime periodBoundary = current.plusMinutes(periodEnd - currentMinute);
            if (periodBoundary.isAfter(current) && !periodBoundary.isAfter(end)) {
                return List.of(periodBoundary);
            }
            return List.of();
        });
        providers.add(BoundaryProviders.freeRangeEdges(freeTimeRanges));
        // 单元对齐：每个 unitMinutes 步长产生一个边界
        providers.add((current, end) -> {
            List<LocalDateTime> result = new ArrayList<>();
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
            // 检查是否在免费时段内
            FreeTimeRange covering = findCoveringRange(current, freeTimeRanges);
            if (covering != null) {
                return new HomogeneousSegment(current, next, BigDecimal.ZERO, BigDecimal.ZERO,
                        true, covering.getId(), null);
            }
            // 计费段
            BigDecimal unitPrice = priceResolver.calculateUnitPrice(current, next, periods, crossPeriodMode);
            return new HomogeneousSegment(current, next, unitPrice, unitPrice,
                    false, null, null);
        });

        // 转换为 BillingUnit（compact 合并 + 累计金额 + 封顶处理）
        List<BillingUnit> billingUnits = applyCapAndAccumulate(segments, maxCharge, context, unitMinutes, config);

        BigDecimal totalAmount = billingUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(billingUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(billingUnits, freeTimeRanges, calcBegin, calcEnd);

        // 状态续算已下线（CONTINUE 移除），单次计算周期跟踪从 calcBegin 初始化

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(billingUnits)
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(feeEffectiveStart)
                .feeEffectiveEnd(feeEffectiveEnd)
                .build();
    }

    // ==================== 辅助方法 ====================

    private void validateConfig(NaturalTimeConfig config) {
        if (config.getPeriods() == null || config.getPeriods().isEmpty()) {
            throw new IllegalArgumentException("periods 不能为空");
        }

        if (config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes 必须大于 0");
        }

        validatePeriodsCoverage(config.getPeriods());
    }

    private void validatePeriodsCoverage(List<NaturalPeriod> periods) {
        int totalCovered = 0;
        int prevEnd = 0;

        for (int i = 0; i < periods.size(); i++) {
            NaturalPeriod period = periods.get(i);

            if (i > 0 && period.getBeginMinute() != prevEnd) {
                throw new IllegalArgumentException("时段不连续");
            }

            if (period.getBeginMinute() < period.getEndMinute()) {
                totalCovered += period.getEndMinute() - period.getBeginMinute();
            } else {
                totalCovered += (MINUTES_PER_DAY - period.getBeginMinute()) + period.getEndMinute();
            }

            prevEnd = period.getEndMinute();
        }

        if (totalCovered != MINUTES_PER_DAY) {
            throw new IllegalArgumentException("时段未覆盖全天");
        }
    }

    /**
     * 查找在 {@code at} 时刻覆盖当前点的免费时段。
     */
    private FreeTimeRange findCoveringRange(LocalDateTime at, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!at.isBefore(range.getBeginTime()) && at.isBefore(range.getEndTime())) {
                return range;
            }
        }
        return null;
    }

    /**
     * 把同质段列表转换为 BillingUnit 列表，并应用封顶逻辑、累计金额、截断标记。
     * <p>
     * 封顶：按自然日（24h）统计 dayAccumulated，达到 maxCharge 后剩余段变为免费（CYCLE_CAP）。
     * 累计：从零开始逐单元累加。
     * 截断：最后一个段的 duration &lt; unitMinutes 且 endTime == calcEnd 时标记 isTruncated。
     */
    private List<BillingUnit> applyCapAndAccumulate(List<HomogeneousSegment> segments,
                                                     BigDecimal maxCharge,
                                                     BillingContext context,
                                                     int unitMinutes,
                                                     NaturalTimeConfig config) {
        List<BillingUnit> units = new ArrayList<>();
        if (segments.isEmpty()) return units;

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        LocalDateTime dayStart = calcBegin;
        BigDecimal dayAccumulated = BigDecimal.ZERO;

        BigDecimal accumulated = BigDecimal.ZERO;

        for (int i = 0; i < segments.size(); i++) {
            HomogeneousSegment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);

            // 截断判定提前：不足单元按 IncompleteUnitChargeMode 计费
            boolean isTruncated = isLast
                    && unitMinutes > 0
                    && seg.durationMinutes() < unitMinutes
                    && seg.getEndTime().equals(calcEnd);

            // 周期（24h）封顶判断
            boolean cycleCapped = false;
            if (maxCharge != null && !seg.isFree() && dayAccumulated.compareTo(maxCharge) >= 0) {
                cycleCapped = true;
            }

            int segMinutes = seg.durationMinutes();
            int subCount = unitMinutes > 0 ? segMinutes / unitMinutes : 1;
            if (subCount < 1) subCount = 1;

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
                // 不足单元按模式计费（非免费的档位）
                charged = computeIncompleteCharge(unitPrice, segMinutes, unitMinutes,
                        config.getIncompleteUnitChargeMode(),
                        config.getThresholdMinutes(), config.getThresholdRatio());
            } else {
                BigDecimal budget = maxCharge != null
                        ? maxCharge.subtract(dayAccumulated)
                        : null;
                if (budget != null && budget.signum() < 0) budget = BigDecimal.ZERO;
                BigDecimal fullTotal = originalPerSub.multiply(BigDecimal.valueOf(subCount));
                if (budget != null && fullTotal.compareTo(budget) > 0) {
                    charged = budget.setScale(2, RoundingMode.HALF_UP);
                } else {
                    charged = fullTotal;
                }
            }

            // 截断扣减已下线（CONTINUE 移除），单次计算从 ZERO 累加
            accumulated = accumulated.add(charged);
            if (!seg.isFree() && !cycleCapped && !incompleteFree) {
                dayAccumulated = dayAccumulated.add(charged);
            }

            // 截断单元永不 compact
            boolean isCompact = !isTruncated && subCount > 1;

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
                    .ruleData(seg.getRuleData())
                    .compact(isCompact)
                    .count(isCompact ? subCount : 1)
                    .isTruncated(isTruncated)
                    .build();
            units.add(unit);

            // 24h 周期切换
            if (!seg.getEndTime().isBefore(dayStart.plusMinutes(MINUTES_PER_DAY))) {
                dayStart = seg.getEndTime();
                dayAccumulated = BigDecimal.ZERO;
            }
        }
        return units;
    }

    private List<TimeFragment> splitTimeAxis(LocalDateTime calcBegin, LocalDateTime calcEnd,
                                             List<NaturalPeriod> periods,
                                             List<FreeTimeRange> freeTimeRanges,
                                             NaturalTimePeriodResolver periodResolver) {
        List<TimeFragment> fragments = new ArrayList<>();
        LocalDateTime current = calcBegin;

        while (current.isBefore(calcEnd)) {
            LocalDateTime nextBoundary = findNextBoundary(current, calcEnd, periods, freeTimeRanges, periodResolver);

            boolean isFree = isInFreeRange(current, freeTimeRanges);
            String freePromotionId = isFree ? findPromotionIdForTime(current, freeTimeRanges) : null;

            fragments.add(new TimeFragment(current, nextBoundary, isFree, freePromotionId));
            current = nextBoundary;
        }

        return fragments;
    }

    private LocalDateTime findNextBoundary(LocalDateTime current, LocalDateTime calcEnd,
                                           List<NaturalPeriod> periods,
                                           List<FreeTimeRange> freeTimeRanges,
                                           NaturalTimePeriodResolver periodResolver) {
        LocalDateTime nextBoundary = calcEnd;

        // 时段边界
        int currentMinute = current.getHour() * 60 + current.getMinute();
        int periodEnd = periodResolver.findNextPeriodBoundary(currentMinute, periods);
        LocalDateTime periodBoundary = current.plusMinutes(periodEnd - currentMinute);
        if (periodBoundary.isBefore(nextBoundary)) {
            nextBoundary = periodBoundary;
        }

        // 免费时段边界
        for (FreeTimeRange range : freeTimeRanges) {
            if (range.getBeginTime().isAfter(current) && range.getBeginTime().isBefore(nextBoundary)) {
                nextBoundary = range.getBeginTime();
            }
            if (range.getEndTime().isAfter(current) && range.getEndTime().isBefore(nextBoundary)) {
                nextBoundary = range.getEndTime();
            }
        }

        return nextBoundary;
    }

    private boolean isInFreeRange(LocalDateTime time, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!time.isBefore(range.getBeginTime()) && time.isBefore(range.getEndTime())) {
                return true;
            }
        }
        return false;
    }

    private String findPromotionIdForTime(LocalDateTime time, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!time.isBefore(range.getBeginTime()) && time.isBefore(range.getEndTime())) {
                return range.getId();
            }
        }
        return null;
    }

    // ==================== 费用稳定时间窗口计算 ====================

    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> billingUnits) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }
        return billingUnits.get(billingUnits.size() - 1).getBeginTime();
    }

    private LocalDateTime calculateEffectiveTo(List<BillingUnit> billingUnits,
                                                List<FreeTimeRange> freeTimeRanges,
                                                LocalDateTime calcBegin,
                                                LocalDateTime calcEnd) {
        if (billingUnits == null || billingUnits.isEmpty()) {
            return null;
        }

        BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
        LocalDateTime effectiveEnd = lastUnit.getEndTime();

        // 如果最后单元在免费时段内，延伸到免费时段结束
        if (lastUnit.isFree() && freeTimeRanges != null) {
            for (FreeTimeRange range : freeTimeRanges) {
                if (range.getEndTime().isAfter(effectiveEnd)) {
                    effectiveEnd = range.getEndTime();
                }
            }
        }

        // 下一个周期边界
        LocalDateTime nextCycleBoundary = calcBegin.plusMinutes(MINUTES_PER_DAY);
        if (nextCycleBoundary.isBefore(effectiveEnd)) {
            effectiveEnd = nextCycleBoundary;
        }

        // 不能超过分段结束时间
        if (calcEnd.isBefore(effectiveEnd)) {
            effectiveEnd = calcEnd;
        }

        return effectiveEnd;
    }

    // ==================== 内部类 ====================

    private static class TimeFragment {
        final LocalDateTime beginTime;
        final LocalDateTime endTime;
        final boolean free;
        final String freePromotionId;

        TimeFragment(LocalDateTime beginTime, LocalDateTime endTime, boolean free, String freePromotionId) {
            this.beginTime = beginTime;
            this.endTime = endTime;
            this.free = free;
            this.freePromotionId = freePromotionId;
        }
    }
}
