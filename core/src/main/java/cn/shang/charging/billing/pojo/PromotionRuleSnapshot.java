package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.PromotionGrant;
import lombok.Data;

import java.util.List;

/**
 * 优惠规则
 */
@Data
public abstract class PromotionRuleSnapshot {
    Long id;
    Integer type;

    public abstract List<PromotionGrant> grant(BillingContext ctx, CalculationWindow window);
}
