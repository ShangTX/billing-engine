package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.util.Set;

public interface BillingRule<C extends RuleConfig> {

    /**
     * 计算费用
     */
    BillingSegmentResult calculate(BillingContext context,
                                   C ruleConfig,
                                   PromotionAggregate promotionAggregate);

    Class<C> configClass();

    /**
     * 返回规则支持的计费模式
     */
    Set<BConstants.BillingMode> supportedModes();

}