package cn.shang.charging.promotion.rules;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.promotion.pojo.PromotionGrant;

import java.util.List;

public interface PromotionRule<T extends PromotionRuleConfig> {

    String getType();

    Class<T> getConfigClass();

    List<PromotionGrant> grant(BillingContext billingContext, T config);
}
