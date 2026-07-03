package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.value.UnitValueSpec;
import cn.shang.charging.charge.rules.AbstractTimeBasedRule;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * `dayNight` 规则在 UNIT_BASED 模式下的策略实现。
 * <p>
 * 承载 UNIT_BASED 语义：
 * <ul>
 *   <li>固定单元对齐：单元边界由计费起点 + unitMinutes 决定，不被免费时段打断</li>
 *   <li>完整覆盖才免费：免费时段必须完整覆盖一个单元才使其免费，部分覆盖不算</li>
 *   <li>支持每日封顶 maxChargeOneDay、CONTINUE 续算</li>
 *   <li>不使用边界驱动公共循环（UNIT_BASED 语义与边界驱动切断模型冲突）</li>
 * </ul>
 * 由 {@link DayNightRule} 门面按 BillingMode=UNIT_BASED 分派调用，不独立注册。
 * 从 {@code DayNightUnitBasedRule} 重构而来（TODO-20260702-002）。
 */
final class DayNightUnitBasedStrategy {

    private static final int MINUTES_PER_DAY = 1440;

    private final DayNightPriceResolver priceResolver = new DayNightPriceResolver();
    private final DayNightValueSpecFactory valueSpecFactory = new DayNightValueSpecFactory();

    BillingSegmentResult calculate(BillingContext context,
                                   DayNightConfig config,
                                   PromotionAggregate promotionAggregate) {
        validateConfig(config);

        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();
        int unitMinutes = config.getUnitMinutes();
        BigDecimal maxCharge = config.getMaxChargeOneDay();

        List<FreeTimeRange> freeTimeRanges = promotionAggregate != null && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges() : List.of();

        // 恢复周期状态（CONTINUE 模式）
        int cycleIndex = 0;
        BigDecimal cycleAccumulated = BigDecimal.ZERO;
        LocalDateTime cycleBoundary = calcBegin.plusMinutes(MINUTES_PER_DAY);
        // TODO: CONTINUE 状态恢复需从 BillingContext.ruleState 解析，当前先按 FROM_SCRATCH

        List<BillingUnit> billingUnits = new ArrayList<>();
        LocalDateTime current = calcBegin;

        while (current.isBefore(calcEnd)) {
            // 固定单元对齐：从 calcBegin 起按 unitMinutes 步进，不被免费时段打断
            LocalDateTime unitEnd = current.plusMinutes(unitMinutes);
            if (unitEnd.isAfter(calcEnd)) {
                unitEnd = calcEnd;
            }
            int duration = (int) Duration.between(current, unitEnd).toMinutes();

            // 定价（不足单元也收全额，用 current+unitMinutes 作为 pricingEnd）
            LocalDateTime pricingEnd = current.plusMinutes(unitMinutes);
            BigDecimal unitPrice = priceResolver.determineUnitPriceForContinuous(current, pricingEnd, config);
            BigDecimal originalAmount = unitPrice;

            // 完整覆盖才免费：免费时段必须覆盖整个单元 [current, unitEnd]
            String freePromotionId = findCoveringFreePromotionId(current, unitEnd, freeTimeRanges);
            boolean isFree = freePromotionId != null;

            BigDecimal chargedAmount = isFree ? BigDecimal.ZERO : originalAmount;

            // 不足单元按模式计费
            boolean isTruncated = duration < unitMinutes && unitEnd.equals(calcEnd);
            if (isTruncated && !isFree) {
                boolean incompleteFree = AbstractTimeBasedRule.isIncompleteFree(duration, unitMinutes, config.getIncompleteUnitChargeMode(),
                        config.getThresholdMinutes(), config.getThresholdRatio());
                if (incompleteFree) {
                    chargedAmount = BigDecimal.ZERO;
                    isFree = true;
                    freePromotionId = "INCOMPLETE_FREE";
                } else {
                    chargedAmount = AbstractTimeBasedRule.computeIncompleteCharge(unitPrice, duration, unitMinutes,
                            config.getIncompleteUnitChargeMode(),
                            config.getThresholdMinutes(), config.getThresholdRatio());
                }
            }

            // 每日封顶
            boolean dailyCapped = false;
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
                    dailyCapped = true;
                } else {
                    cycleAccumulated = newAccumulated;
                }
            } else if (!isFree) {
                cycleAccumulated = cycleAccumulated.add(chargedAmount);
            }

            // valueSpec：封顶时用封顶值，不足单元按模式，否则按时段类型生成
            UnitValueSpec valueSpec;
            if (dailyCapped) {
                valueSpec = valueSpecFactory.createCappedSpec(null, current, unitEnd, chargedAmount);
            } else if (isTruncated && !isFree) {
                valueSpec = AbstractTimeBasedRule.computeIncompleteValueSpec(unitPrice, duration, unitMinutes,
                        config.getIncompleteUnitChargeMode(),
                        config.getThresholdMinutes(), config.getThresholdRatio());
            } else if (isFree) {
                valueSpec = valueSpecFactory.createFreeSpec();
            } else {
                DayNightPeriodType periodType = priceResolver.determinePeriodType(current, pricingEnd, config);
                valueSpec = valueSpecFactory.createRegularSpec(periodType, current, pricingEnd, config, originalAmount);
            }

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(current)
                    .endTime(unitEnd)
                    .durationMinutes(duration)
                    .unitPrice(unitPrice)
                    .originalAmount(originalAmount)
                    .free(isFree)
                    .freePromotionId(freePromotionId)
                    .chargedAmount(chargedAmount)
                    .valueSpec(valueSpec)
                    .ruleData(cycleIndex)
                    .build();
            billingUnits.add(unit);

            current = unitEnd;

            // 跨日（24h）周期切换
            if (current.equals(calcBegin.plusMinutes((long) (cycleIndex + 1) * MINUTES_PER_DAY))) {
                cycleIndex++;
                cycleAccumulated = BigDecimal.ZERO;
            }
        }

        // 计算累计金额（CONTINUE 支持）
        BigDecimal accumulatedAmount = context.getPreviousAccumulatedAmount();
        if (accumulatedAmount == null) accumulatedAmount = BigDecimal.ZERO;
        BigDecimal truncatedDeduction = context.getTruncatedUnitChargedAmount();
        if (truncatedDeduction != null && !billingUnits.isEmpty()) {
            accumulatedAmount = accumulatedAmount.subtract(truncatedDeduction);
            if (accumulatedAmount.compareTo(BigDecimal.ZERO) < 0) accumulatedAmount = BigDecimal.ZERO;
        }
        for (BillingUnit unit : billingUnits) {
            accumulatedAmount = accumulatedAmount.add(unit.getChargedAmount());
            unit.setAccumulatedAmount(accumulatedAmount);
        }

        // 截断标记
        if (!billingUnits.isEmpty()) {
            BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
            if (lastUnit.getDurationMinutes() < unitMinutes && lastUnit.getEndTime().equals(calcEnd)) {
                lastUnit.setIsTruncated(true);
            }
        }

        BigDecimal totalAmount = billingUnits.stream()
                .map(BillingUnit::getChargedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime feeEffectiveStart = calculateEffectiveFrom(billingUnits);
        LocalDateTime feeEffectiveEnd = calculateEffectiveTo(billingUnits, freeTimeRanges, calcBegin, calcEnd);

        // 输出状态（CONTINUE 用）
        Map<String, Object> ruleOutputState = Map.of(
                "dayNight", Map.of(
                        "cycleIndex", cycleIndex,
                        "cycleAccumulated", cycleAccumulated,
                        "cycleBoundary", cycleBoundary
                )
        );

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(totalAmount)
                .billingUnits(billingUnits)
                .promotionUsages(new ArrayList<>())
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(feeEffectiveStart)
                .feeEffectiveEnd(feeEffectiveEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }

    private void validateConfig(DayNightConfig config) {
        if (config.getMaxChargeOneDay() == null || config.getMaxChargeOneDay().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("maxChargeOneDay must be positive");
        }
        if (config.getUnitMinutes() == null || config.getUnitMinutes() <= 0) {
            throw new IllegalArgumentException("unitMinutes must be positive");
        }
        if (config.getDayUnitPrice() == null || config.getDayUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dayUnitPrice must be non-negative");
        }
        if (config.getNightUnitPrice() == null || config.getNightUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("nightUnitPrice must be non-negative");
        }
    }

    /**
     * 查找完整覆盖 [begin, end] 的免费时段，返回其优惠 ID。部分覆盖返回 null。
     */
    private String findCoveringFreePromotionId(LocalDateTime begin, LocalDateTime end, List<FreeTimeRange> freeTimeRanges) {
        for (FreeTimeRange range : freeTimeRanges) {
            if (!range.getBeginTime().isAfter(begin) && !range.getEndTime().isBefore(end)) {
                return range.getId();
            }
        }
        return null;
    }

    private LocalDateTime calculateEffectiveFrom(List<BillingUnit> billingUnits) {
        if (billingUnits == null || billingUnits.isEmpty()) return null;
        return billingUnits.get(billingUnits.size() - 1).getBeginTime();
    }

    private LocalDateTime calculateEffectiveTo(List<BillingUnit> billingUnits,
                                                List<FreeTimeRange> freeTimeRanges,
                                                LocalDateTime calcBegin,
                                                LocalDateTime calcEnd) {
        if (billingUnits == null || billingUnits.isEmpty()) return null;
        BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
        LocalDateTime effectiveEnd = lastUnit.getEndTime();
        if (lastUnit.isFree() && freeTimeRanges != null) {
            for (FreeTimeRange range : freeTimeRanges) {
                if (range.getEndTime().isAfter(effectiveEnd)) {
                    effectiveEnd = range.getEndTime();
                }
            }
        }
        LocalDateTime nextCycleBoundary = calcBegin.plusMinutes(MINUTES_PER_DAY);
        if (nextCycleBoundary.isBefore(effectiveEnd)) effectiveEnd = nextCycleBoundary;
        if (calcEnd.isBefore(effectiveEnd)) effectiveEnd = calcEnd;
        return effectiveEnd;
    }
}
