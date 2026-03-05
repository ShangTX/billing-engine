package cn.shang.charging.promotion.rules.minutes;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRule;

import java.util.List;

/**
 * 免费分钟数优惠规则
 */
public class FreeMinutesPromotionRule implements PromotionRule<FreeMinutesPromotionConfig> {


    @Override
    public String getType() {
        return BConstants.PromotionRuleType.FREE_MINUTES;
    }

    @Override
    public Class<FreeMinutesPromotionConfig> getConfigClass() {
        return FreeMinutesPromotionConfig.class;
    }

    @Override
    public List<PromotionGrant> grant(BillingContext billingContext, FreeMinutesPromotionConfig config) {
        var promotionGrant = PromotionGrant.builder()
                .freeMinutes(config.getMinutes())
                .type(BConstants.PromotionType.FREE_MINUTES)
                .source(BConstants.PromotionSource.RULE)
                .priority(config.getPriority())
                .id(config.getId())
                .build();
        return List.of(promotionGrant);
    }
}
