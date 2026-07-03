package cn.shang.charging.promotion;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.pojo.*;
import cn.shang.charging.promotion.rules.PromotionRule;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠计算engine
 * <p>
 * 支持四种优惠类型：
 * - FREE_RANGE: 免费时间段
 * - FREE_MINUTES: 免费分钟数
 * - AMOUNT: 金额减免
 * - DISCOUNT: 折扣优惠
 * <p>
 * 优惠叠加顺序：先折扣后减免，多个 AMOUNT 总和扣除，多个 DISCOUNT 取最优折扣。
 * <p>
 * TODO-20260702-004：FREE_MINUTES 时段化下放到策略侧。本引擎只产出规范中间形式
 * （合并后的 FREE_RANGE 时段 + 未时段化的 FREE_MINUTES 列表 + AMOUNT/DISCOUNT 标量），
 * 不再集中时段化。CONTINUOUS/UNIT_BASED/PERIOD 策略自行时段化，GLOBAL 按分钟扣减。
 * PromotionUsage 与 PromotionCarryOver 由策略侧产出。
 */
@AllArgsConstructor
public class PromotionEngine {

    private BillingConfigResolver billingConfigResolver;
    private FreeTimeRangeMerger freeTimeRangeMerger;
    private PromotionRuleRegistry promotionRuleRegistry;

    public PromotionAggregate evaluate(BillingContext context) {
        // 1️⃣ 确定本次 promotion 计算的时间窗口
        CalculationWindow window = context.getWindow();
        List<FreeTimeRange> timeRangePromotions = new ArrayList<>();
        List<FreeMinutes> freeMinutesPromotions = new ArrayList<>();
        List<PromotionAggregate.AmountDiscount> amountDiscounts = new ArrayList<>();

        // 2.1 来自优惠规则（按方案 + 时间段）
        for (PromotionRuleConfig ruleConfig : context.getPromotionRules()) {
            List<PromotionGrant> grants = grant(context, ruleConfig);
            grants.forEach(grant -> {
                if (grant.getType() == BConstants.PromotionType.FREE_RANGE) {
                    timeRangePromotions.add(convertTimeRangeFromRule(grant));
                }
                if (grant.getType() == BConstants.PromotionType.FREE_MINUTES) {
                    freeMinutesPromotions.add(convertMinutesFromRule(grant));
                }
                if (grant.getType() == BConstants.PromotionType.AMOUNT) {
                    amountDiscounts.add(convertAmountFromGrant(grant));
                }
                if (grant.getType() == BConstants.PromotionType.DISCOUNT) {
                    amountDiscounts.add(convertDiscountFromGrant(grant));
                }
            });
        }

        // 2️⃣ 来自外部优惠
        if (context.getExternalPromotions() != null) {
            for (PromotionGrant externalPromotion : context.getExternalPromotions()) {
                if (externalPromotion.getType() == BConstants.PromotionType.FREE_RANGE) {
                    timeRangePromotions.add(convertTimeRangeFromRule(externalPromotion));
                }
                if (externalPromotion.getType() == BConstants.PromotionType.FREE_MINUTES) {
                    freeMinutesPromotions.add(convertMinutesFromRule(externalPromotion));
                }
                if (externalPromotion.getType() == BConstants.PromotionType.AMOUNT) {
                    amountDiscounts.add(convertAmountFromGrant(externalPromotion));
                }
                if (externalPromotion.getType() == BConstants.PromotionType.DISCOUNT) {
                    amountDiscounts.add(convertDiscountFromGrant(externalPromotion));
                }
            }
        }

        // 3️⃣ 应用优惠结转状态（CONTINUE 模式）
        Map<String, Integer> remainingMinutes = null;
        List<FreeTimeRange> usedFreeRanges = null;
        if (context.getPromotionCarryOver() != null) {
            remainingMinutes = context.getPromotionCarryOver().getRemainingMinutesConverted();
            usedFreeRanges = context.getPromotionCarryOver().getUsedFreeRanges();
        }

        // 4️⃣ 应用剩余免费分钟数
        if (remainingMinutes != null && !remainingMinutes.isEmpty()) {
            applyRemainingMinutes(freeMinutesPromotions, remainingMinutes);
        }

        // 5️⃣ 处理已使用的免费时段（排除已用部分）
        List<FreeTimeRange> filteredTimeRangePromotions = timeRangePromotions;
        if (usedFreeRanges != null && !usedFreeRanges.isEmpty()) {
            filteredTimeRangePromotions = filterUsedFreeRanges(timeRangePromotions, usedFreeRanges, window);
        }

        // 6️⃣ 合并显式免费时间段（FREE_RANGE；FREE_MINUTES 不在此处时段化）
        TimeRangeMergeResult rangeMergeResult = freeTimeRangeMerger.merge(
                filteredTimeRangePromotions,
                context.getBeginTime(),
                context.getEndTime());
        List<FreeTimeRange> explicitFreeRanges = rangeMergeResult.getMergedRanges();

        // 计算总免费分钟数（用于简化计算判断）
        long totalFreeMinutes = freeMinutesPromotions.stream()
                .mapToLong(fm -> fm.getMinutes())
                .sum();

        // 7️⃣ 计算 AMOUNT/DISCOUNT 优惠汇总
        BigDecimal totalAmountDiscount = calculateTotalAmountDiscount(amountDiscounts);
        BigDecimal bestDiscountRate = calculateBestDiscountRate(amountDiscounts);

        // 产出规范中间形式：FREE_RANGE 时段 + 未时段化 FREE_MINUTES 列表 + AMOUNT/DISCOUNT 标量。
        // FREE_MINUTES 时段化、PromotionUsage、PromotionCarryOver 由策略侧产出（TODO-20260702-004）。
        return PromotionAggregate.builder()
                .freeTimeRanges(explicitFreeRanges)
                .freeMinutes(totalFreeMinutes)
                .freeMinutesList(freeMinutesPromotions)
                .usages(null)
                .promotionCarryOver(null)
                .amountDiscounts(amountDiscounts.isEmpty() ? null : amountDiscounts)
                .totalAmountDiscount(totalAmountDiscount)
                .bestDiscountRate(bestDiscountRate)
                .build();
    }

    /**
     * 应用剩余免费分钟数
     * 将结转的剩余分钟数更新到免费分钟数列表中
     */
    private void applyRemainingMinutes(List<FreeMinutes> freeMinutesPromotions, Map<String, Integer> remainingMinutes) {
        for (FreeMinutes fm : freeMinutesPromotions) {
            Integer remaining = remainingMinutes.get(fm.getId());
            if (remaining != null && remaining > 0) {
                // 使用剩余分钟数替换原始分钟数
                fm.setMinutes(remaining);
            } else if (remaining != null && remaining <= 0) {
                // 已用完，标记为 0
                fm.setMinutes(0);
            }
        }
    }

    /**
     * 过滤已使用的免费时段
     * 从可用时段中排除已使用的部分
     */
    private List<FreeTimeRange> filterUsedFreeRanges(List<FreeTimeRange> timeRangePromotions,
                                                      List<FreeTimeRange> usedFreeRanges,
                                                      CalculationWindow window) {
        List<FreeTimeRange> result = new ArrayList<>();

        for (FreeTimeRange range : timeRangePromotions) {
            List<FreeTimeRange> remaining = List.of(range);

            // 对每个已使用时段，从剩余时段中减去
            for (FreeTimeRange used : usedFreeRanges) {
                if (used.getId() != null && used.getId().equals(range.getId())) {
                    remaining = subtractFreeRanges(remaining, used);
                }
            }

            result.addAll(remaining);
        }

        return result;
    }

    /**
     * 从时段列表中减去一个已使用时段
     */
    private List<FreeTimeRange> subtractFreeRanges(List<FreeTimeRange> ranges, FreeTimeRange used) {
        List<FreeTimeRange> result = new ArrayList<>();

        for (FreeTimeRange range : ranges) {
            // 无交集，保留原时段
            if (!range.overlaps(used)) {
                result.add(range);
                continue;
            }

            // 有交集，分割时段
            // 前半段：range.beginTime ~ used.beginTime
            if (range.getBeginTime().isBefore(used.getBeginTime())) {
                result.add(FreeTimeRange.builder()
                        .id(range.getId())
                        .beginTime(range.getBeginTime())
                        .endTime(used.getBeginTime())
                        .priority(range.getPriority())
                        .promotionType(range.getPromotionType())
                        .rangeType(range.getRangeType())
                        .build());
            }

            // 后半段：used.endTime ~ range.endTime
            if (used.getEndTime().isBefore(range.getEndTime())) {
                result.add(FreeTimeRange.builder()
                        .id(range.getId())
                        .beginTime(used.getEndTime())
                        .endTime(range.getEndTime())
                        .priority(range.getPriority())
                        .promotionType(range.getPromotionType())
                        .rangeType(range.getRangeType())
                        .build());
            }
        }

        return result;
    }

    /**
     * 计算有效优惠
     */
    private List<PromotionGrant> grant(BillingContext context, PromotionRuleConfig ruleConfig) {
        var rule = promotionRuleRegistry.get(ruleConfig.getType());
        if (!rule.getType().equals(ruleConfig.getType())) {
            throw new IllegalStateException("PromotionRuleConfig mismatch");
        }
        return invokeRule(rule, context, ruleConfig);

    }
    private <C extends PromotionRuleConfig> List<PromotionGrant> invokeRule(
            PromotionRule<C> rule,
            BillingContext context,
            PromotionRuleConfig rawConfig) {

        if (!rule.getConfigClass().isInstance(rawConfig)) {
            throw new IllegalStateException("PromotionRuleConfig mismatch");
        }
        C config = rule.getConfigClass().cast(rawConfig);
        return rule.grant(context, config);
    }

    /**
     * 将优惠规则中的优惠时间段转为计算的免费时间段
     */
    private FreeTimeRange convertTimeRangeFromRule(PromotionGrant grant) {
        return FreeTimeRange.builder()
                .id(grant.getId())
                .promotionType(grant.getType())
                .beginTime(grant.getBeginTime())
                .endTime(grant.getEndTime())
                .priority(grant.getPriority())
                .rangeType(grant.getRangeType())
                .source(grant.getSource())
                .conditional(Boolean.TRUE.equals(grant.getConditional()))
                .conditionalUntil(grant.getConditionalUntil())
                .build();
    }

    /**
     * 将优惠规则中的免费时间段转化未计算的免费分钟数
     */
    private FreeMinutes convertMinutesFromRule(PromotionGrant grant) {
        return FreeMinutes.builder()
                .id(grant.getId())
                .minutes(grant.getFreeMinutes())
                .priority(grant.getPriority())
                .build();
    }

    /**
     * 将金额减免优惠转换为 AmountDiscount
     */
    private PromotionAggregate.AmountDiscount convertAmountFromGrant(PromotionGrant grant) {
        return PromotionAggregate.AmountDiscount.builder()
                .id(grant.getId())
                .type(BConstants.PromotionType.AMOUNT)
                .amount(grant.getAmount())
                .priority(grant.getPriority())
                .build();
    }

    /**
     * 将折扣优惠转换为 AmountDiscount
     */
    private PromotionAggregate.AmountDiscount convertDiscountFromGrant(PromotionGrant grant) {
        return PromotionAggregate.AmountDiscount.builder()
                .id(grant.getId())
                .type(BConstants.PromotionType.DISCOUNT)
                .discountRate(grant.getDiscountRate())
                .priority(grant.getPriority())
                .build();
    }

    /**
     * 计算总金额减免（所有 AMOUNT 优惠的总和）
     */
    private BigDecimal calculateTotalAmountDiscount(List<PromotionAggregate.AmountDiscount> amountDiscounts) {
        return amountDiscounts.stream()
                .filter(ad -> ad.getType() == BConstants.PromotionType.AMOUNT)
                .filter(ad -> ad.getAmount() != null)
                .map(PromotionAggregate.AmountDiscount::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 计算最优折扣率（所有 DISCOUNT 中折扣力度最大的 = 最小值）
     * 如 0.8（8折）比 0.9（9折）更优
     */
    private BigDecimal calculateBestDiscountRate(List<PromotionAggregate.AmountDiscount> amountDiscounts) {
        return amountDiscounts.stream()
                .filter(ad -> ad.getType() == BConstants.PromotionType.DISCOUNT)
                .filter(ad -> ad.getDiscountRate() != null)
                .map(PromotionAggregate.AmountDiscount::getDiscountRate)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ONE);
    }

}
