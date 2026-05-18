package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            BConstants.BillingMode.UNIT_BASED,
            BConstants.BillingMode.CONTINUOUS
    );

    private final NaturalTimeUnitBasedCalculator unitBasedCalculator = new NaturalTimeUnitBasedCalculator();
    private final NaturalTimeContinuousCalculator continuousCalculator = new NaturalTimeContinuousCalculator();

    @Override
    public Class<NaturalTimeConfig> configClass() {
        return NaturalTimeConfig.class;
    }

    @Override
    public String getRuleType() {
        return "naturalTime";
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

        BConstants.BillingMode mode = context.getBillingMode();
        if (mode == BConstants.BillingMode.UNIT_BASED) {
            return unitBasedCalculator.calculate(this, context, config, promotionAggregate);
        } else {
            return continuousCalculator.calculate(this, context, config, promotionAggregate);
        }
    }

    // ==================== 内部计算方法（供 Calculator 调用） ====================

    BillingSegmentResult calculateUnitBasedInternal(BillingContext context,
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

        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null
                && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges() : List.of();

        List<BillingUnit> billingUnits = new ArrayList<>();
        LocalDateTime current = calcBegin;
        BigDecimal cycleAccumulated = BigDecimal.ZERO;

        // 计算周期索引
        int cycleIndex = (int) Duration.between(calcBegin, current).toMinutes() / MINUTES_PER_DAY;

        while (current.isBefore(calcEnd)) {
            LocalDateTime unitEnd = current.plusMinutes(unitMinutes);
            if (unitEnd.isAfter(calcEnd)) {
                unitEnd = calcEnd;
            }

            int duration = (int) Duration.between(current, unitEnd).toMinutes();

            // 计算单元价格
            BigDecimal unitPrice = priceResolver.calculateUnitPrice(current, unitEnd, periods, crossPeriodMode);

            // 检查免费时段覆盖
            boolean isFree = isFullyCoveredByFreeRange(current, unitEnd, freeTimeRanges);
            String freePromotionId = isFree ? findCoveringPromotionId(current, unitEnd, freeTimeRanges) : null;

            BigDecimal chargedAmount = isFree ? BigDecimal.ZERO : unitPrice;

            // 周期封顶检查
            if (maxCharge != null && !isFree) {
                BigDecimal newAccumulated = cycleAccumulated.add(chargedAmount);
                if (newAccumulated.compareTo(maxCharge) >= 0) {
                    chargedAmount = maxCharge.subtract(cycleAccumulated).setScale(2, RoundingMode.HALF_UP);
                    if (chargedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                        isFree = true;
                        freePromotionId = "DAILY_CAP";
                        chargedAmount = BigDecimal.ZERO;
                    }
                    cycleAccumulated = maxCharge;
                } else {
                    cycleAccumulated = newAccumulated;
                }
            } else if (!isFree) {
                cycleAccumulated = cycleAccumulated.add(chargedAmount);
            }

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(current)
                    .endTime(unitEnd)
                    .durationMinutes(duration)
                    .unitPrice(unitPrice)
                    .originalAmount(unitPrice)
                    .free(isFree)
                    .freePromotionId(freePromotionId)
                    .chargedAmount(chargedAmount)
                    .ruleData(cycleIndex)
                    .build();

            billingUnits.add(unit);
            current = unitEnd;

            // 跨周期处理
            if (current.equals(calcBegin.plusMinutes((long) (cycleIndex + 1) * MINUTES_PER_DAY))) {
                cycleIndex++;
                cycleAccumulated = BigDecimal.ZERO;
            }
        }

        // 汇总结果
        BigDecimal totalAmount = billingUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 计算累计金额
        BigDecimal accumulatedAmount = context.getPreviousAccumulatedAmount();
        if (accumulatedAmount == null) {
            accumulatedAmount = BigDecimal.ZERO;
        }

        BigDecimal truncatedUnitChargedAmount = context.getTruncatedUnitChargedAmount();
        if (truncatedUnitChargedAmount != null && !billingUnits.isEmpty()) {
            accumulatedAmount = accumulatedAmount.subtract(truncatedUnitChargedAmount);
            if (accumulatedAmount.compareTo(BigDecimal.ZERO) < 0) {
                accumulatedAmount = BigDecimal.ZERO;
            }
        }

        for (BillingUnit unit : billingUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        // 标记截断单元
        if (!billingUnits.isEmpty()) {
            BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(billingUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(billingUnits, freeTimeRanges, calcBegin, calcEnd);

        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = initializeState(calcBegin);
        }
        cycleStateManager.updateStateAfterUnitBased(billingUnits, state);
        Map<String, Object> ruleOutputState = buildRuleOutputState(state);

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
                .ruleOutputState(ruleOutputState)
                .build();
    }

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

        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null
                && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges() : List.of();

        // 按时段边界和免费时段切分时间轴
        List<TimeFragment> fragments = splitTimeAxis(calcBegin, calcEnd, periods, freeTimeRanges, periodResolver);

        List<BillingUnit> billingUnits = new ArrayList<>();
        BigDecimal cycleAccumulated = BigDecimal.ZERO;
        int cycleCount = 0;

        for (TimeFragment fragment : fragments) {
            if (fragment.free) {
                // 免费片段直接生成免费单元
                BillingUnit freeUnit = BillingUnit.builder()
                        .beginTime(fragment.beginTime)
                        .endTime(fragment.endTime)
                        .durationMinutes((int) Duration.between(fragment.beginTime, fragment.endTime).toMinutes())
                        .unitPrice(BigDecimal.ZERO)
                        .originalAmount(BigDecimal.ZERO)
                        .free(true)
                        .freePromotionId(fragment.freePromotionId)
                        .chargedAmount(BigDecimal.ZERO)
                        .build();
                billingUnits.add(freeUnit);
            } else {
                // 计费片段按单元时长划分
                LocalDateTime current = fragment.beginTime;
                while (current.isBefore(fragment.endTime)) {
                    LocalDateTime unitEnd = current.plusMinutes(unitMinutes);
                    if (unitEnd.isAfter(fragment.endTime)) {
                        unitEnd = fragment.endTime;
                    }

                    BigDecimal unitPrice = priceResolver.calculateUnitPrice(current, unitEnd, periods, crossPeriodMode);
                    int duration = (int) Duration.between(current, unitEnd).toMinutes();

                    // 封顶处理
                    BigDecimal chargedAmount = unitPrice;
                    if (maxCharge != null) {
                        BigDecimal newAccumulated = cycleAccumulated.add(chargedAmount);
                        if (newAccumulated.compareTo(maxCharge) >= 0) {
                            chargedAmount = maxCharge.subtract(cycleAccumulated).setScale(2, RoundingMode.HALF_UP);
                            cycleAccumulated = maxCharge;
                        } else {
                            cycleAccumulated = newAccumulated;
                        }
                    } else {
                        cycleAccumulated = cycleAccumulated.add(chargedAmount);
                    }

                    BillingUnit unit = BillingUnit.builder()
                            .beginTime(current)
                            .endTime(unitEnd)
                            .durationMinutes(duration)
                            .unitPrice(unitPrice)
                            .originalAmount(unitPrice)
                            .free(false)
                            .chargedAmount(chargedAmount)
                            .build();
                    billingUnits.add(unit);
                    current = unitEnd;
                }
            }

            // 检查周期边界
            if (fragment.endTime.equals(calcBegin.plusMinutes((long) (cycleCount + 1) * MINUTES_PER_DAY))) {
                cycleCount++;
                cycleAccumulated = BigDecimal.ZERO;
            }
        }

        BigDecimal totalAmount = billingUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal accumulatedAmount = context.getPreviousAccumulatedAmount();
        if (accumulatedAmount == null) {
            accumulatedAmount = BigDecimal.ZERO;
        }

        for (BillingUnit unit : billingUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(billingUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(billingUnits, freeTimeRanges, calcBegin, calcEnd);

        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = initializeState(calcBegin);
        }
        cycleStateManager.updateStateAfterContinuous(Math.max(1, cycleCount), state, cycleAccumulated);
        Map<String, Object> ruleOutputState = buildRuleOutputState(state);

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
                .ruleOutputState(ruleOutputState)
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

    private boolean isFullyCoveredByFreeRange(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
                return true;
            }
        }
        return false;
    }

    private String findCoveringPromotionId(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
                return range.getId();
            }
        }
        return null;
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