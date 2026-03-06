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
