package cn.shang.charging.charge.rules.naturaltime;

import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

/**
 * `naturalTime` 规则在 UNIT_BASED 模式下的计算入口。
 */
final class NaturalTimeUnitBasedCalculator {

    private final NaturalTimePeriodResolver periodResolver = new NaturalTimePeriodResolver();
    private final NaturalTimeCrossPeriodPriceResolver priceResolver = new NaturalTimeCrossPeriodPriceResolver();
    private final NaturalTimeCycleStateManager cycleStateManager = new NaturalTimeCycleStateManager();

    BillingSegmentResult calculate(NaturalTimeRule owner,
                                   BillingContext context,
                                   NaturalTimeConfig config,
                                   PromotionAggregate promotionAggregate) {
        return owner.calculateUnitBasedInternal(context, config, promotionAggregate,
                periodResolver, priceResolver, cycleStateManager);
    }
}