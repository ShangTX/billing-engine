package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

/**
 * 日夜计费规则
 */
public class DayNightRule implements BillingRule<DayNightConfig> {

    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig ruleConfig, PromotionAggregate promotionAggregate) {
        // 校验类型
        if (!ruleConfig.getClass().isInstance(configClass())) {
            throw new IllegalArgumentException("RuleConfig type mismatch");
        }

        return null;
    }

    @Override
    public Class<DayNightConfig> configClass() {
        return DayNightConfig.class;
    }

}
