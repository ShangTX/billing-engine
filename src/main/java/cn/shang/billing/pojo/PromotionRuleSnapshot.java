package cn.shang.billing.pojo;

import cn.shang.promotion.pojo.PromotionContext;
import cn.shang.promotion.pojo.PromotionContribution;
import lombok.Data;

import java.util.List;

/**
 * 优惠规则
 */
@Data
public abstract class PromotionRuleSnapshot {
    Long id;
    Integer type;

    public abstract List<PromotionContribution> grant(BillingContext ctx, CalculationWindow window);
}
