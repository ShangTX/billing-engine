package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class BillingCalculator {


    private final BillingRuleRegistry ruleRegistry;

    /**
     * 计算
     */
    public BillingSegmentResult calculate(BillingContext context, PromotionAggregate promotionAggregate) {

        var ruleConfig = context.getChargingRule();
        BillingRule<?> billingRule = ruleRegistry.get(ruleConfig.getType());

        if (billingRule == null) {
            throw new RuntimeException("No billing rule found for type: " + ruleConfig.getType());
        }

        // 校验计费模式支持
        if (!billingRule.supportedModes().contains(context.getBillingMode())) {
            throw new IllegalStateException(
                    "Rule " + billingRule.getClass().getSimpleName() +
                    " (type=" + ruleConfig.getType() + ") does not support billing mode: " +
                    context.getBillingMode()
            );
        }

        // 校验时长计费模式支持：指定了时长模式但规则不支持时抛异常（不静默降级）
        cn.shang.charging.billing.pojo.BConstants.DurationMode durationMode = context.getDurationMode();
        if (durationMode != null && durationMode != cn.shang.charging.billing.pojo.BConstants.DurationMode.NONE) {
            if (!billingRule.supportedDurationModes().contains(durationMode)) {
                throw new IllegalStateException(
                        "Rule " + billingRule.getClass().getSimpleName() +
                        " (type=" + ruleConfig.getType() + ") does not support duration mode: " +
                        durationMode
                );
            }
        }

        return calculateInternal(context, billingRule, ruleConfig, promotionAggregate);
    }

    /**
     * 使用规则计算费用
     */
    private <C extends RuleConfig> BillingSegmentResult calculateInternal(
            BillingContext context,
            BillingRule<C> rule,
            RuleConfig rawConfig,
            PromotionAggregate promotionAggregate) {

        if (!rule.configClass().isInstance(rawConfig)) {
            throw new IllegalStateException(
                    "RuleConfig mismatch, rule="
                            + rule.getClass().getSimpleName()
                            + ", config=" + rawConfig.getClass().getSimpleName()
            );
        }

        C config = rule.configClass().cast(rawConfig);
        return rule.calculate(context, config, promotionAggregate);
    }
}
