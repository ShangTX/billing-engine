package cn.shang.charging.charge.rules.relativetime;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

/**
 * `relativeTime` 规则在 UNIT_BASED 模式下的计算入口。
 * <p>
 * 当前阶段先做模式职责拆分，不改变原有计算语义；
 * 具体计算逻辑仍委托给 `RelativeTimeRule` 中的原实现。
 */
final class RelativeTimeUnitBasedCalculator {

    BillingSegmentResult calculate(RelativeTimeRule owner,
                                   BillingContext context,
                                   RelativeTimeConfig config,
                                   PromotionAggregate promotionAggregate) {
        return owner.calculateUnitBasedInternal(context, config, promotionAggregate);
    }
}
