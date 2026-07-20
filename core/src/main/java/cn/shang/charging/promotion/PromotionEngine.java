package cn.shang.charging.promotion;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.pojo.*;
import cn.shang.charging.promotion.rules.PromotionRule;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 优惠计算engine
 * <p>
 * 支持两种免费类优惠类型：
 * - FREE_RANGE: 免费时间段
 * - FREE_MINUTES: 免费分钟数（通过 allocationMode 控制分配策略）
 * <p>
 * AMOUNT/DISCOUNT（金额减免/折扣）已移出引擎，由业务系统在最终金额上自行结算。
 * <p>
 * TODO-20260702-004：FREE_MINUTES 时段化下放到策略侧。本引擎只产出规范中间形式
 * （合并后的 FREE_RANGE 时段 + 未时段化的 FREE_MINUTES 列表），
 * 不再集中时段化，策略侧按 allocationMode 消费 FREE_MINUTES。
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
            }
        }

        // 3️⃣ 合并显式免费时间段（FREE_RANGE；FREE_MINUTES 不在此处时段化）
        TimeRangeMergeResult rangeMergeResult = freeTimeRangeMerger.merge(
                timeRangePromotions,
                context.getBeginTime(),
                context.getEndTime());
        List<FreeTimeRange> explicitFreeRanges = rangeMergeResult.getMergedRanges();

        long totalFreeMinutes = freeMinutesPromotions.stream()
                .mapToLong(fm -> fm.getMinutes())
                .sum();

        // 产出规范中间形式：FREE_RANGE 时段 + 未时段化 FREE_MINUTES 列表。
        // FREE_MINUTES 分配和 PromotionUsage 由策略侧产出。
        return PromotionAggregate.builder()
                .freeTimeRanges(explicitFreeRanges)
                .freeMinutes(totalFreeMinutes)
                .freeMinutesList(freeMinutesPromotions)
                .build();
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
                .activationMode(grant.getActivationMode())
                .build();
    }

    /**
     * 将优惠规则中的免费时间段转化未计算的免费分钟数
     */
    private FreeMinutes convertMinutesFromRule(PromotionGrant grant) {
        return FreeMinutes.builder()
                .id(grant.getId())
                .minutes(grant.getFreeMinutes())
                .allocationMode(grant.getAllocationMode())
                .priority(grant.getPriority())
                .source(grant.getSource())
                .activationMode(grant.getActivationMode())
                .build();
    }

}
