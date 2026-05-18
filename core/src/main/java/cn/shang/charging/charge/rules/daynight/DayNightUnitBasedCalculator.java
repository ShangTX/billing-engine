package cn.shang.charging.charge.rules.daynight;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

/**
 * `dayNight` 规则在 UNIT_BASED 模式下的计算入口。
 * <p>
 * 当前阶段先做模式职责拆分，不改变原有计算语义；
 * 具体计算逻辑仍委托给 `DayNightRule` 中的原实现。
 */
final class DayNightUnitBasedCalculator {

    BillingSegmentResult calculate(DayNightRule owner,
                                   BillingContext context,
                                   DayNightConfig config,
                                   PromotionAggregate promotionAggregate) {
        return owner.calculateUnitBasedInternal(context, config, promotionAggregate);
    }
}
