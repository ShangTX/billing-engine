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

        // 校验计算模式支持
        cn.shang.charging.billing.pojo.BConstants.CalculationMode calculationMode = context.getCalculationMode();
        if (calculationMode != null && !billingRule.supportedCalculationModes().contains(calculationMode)) {
            throw new IllegalStateException(
                    "Rule " + billingRule.getClass().getSimpleName() +
                    " (type=" + ruleConfig.getType() + ") does not support calculation mode: " +
                    calculationMode
            );
        }

        // SMART_FREE_MINUTES 仅 DURATION_GLOBAL 消费（TODO-20260706-002 阶段5）：
        // 优先高价分配依赖 RuleSemantics.priceAt，复杂度锁定在 GLOBAL 模式内，其余模式报错。
        if (promotionAggregate != null
                && calculationMode != cn.shang.charging.billing.pojo.BConstants.CalculationMode.DURATION_GLOBAL
                && promotionAggregate.getSmartFreeMinutesList() != null
                && !promotionAggregate.getSmartFreeMinutesList().isEmpty()) {
            throw new IllegalStateException(
                    "SMART_FREE_MINUTES is only supported in DURATION_GLOBAL mode, but current mode is: "
                            + calculationMode
            );
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
