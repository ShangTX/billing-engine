package cn.shang.charging.promotion.rules.ranges;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRule;

import java.util.List;

public class FreeTimeRangePromotionRule implements PromotionRule<FreeTimeRangePromotionConfig> {

    @Override
    public String getType() {
        return BConstants.PromotionRuleType.FREE_TIME_RANGE;
    }

    @Override
    public Class<FreeTimeRangePromotionConfig> getConfigClass() {
        return FreeTimeRangePromotionConfig.class;
    }

    @Override
    public List<PromotionGrant> grant(BillingContext billingContext, FreeTimeRangePromotionConfig config) {
        return List.of();
    }
}
