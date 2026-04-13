package cn.shang.charging.charge.rules.flatfree;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一免费计费规则
 * <p>
 * 忽略所有优惠，返回一个覆盖整个计算窗口的免费计费单元。
 * 无论 CONTINUOUS 还是 UNIT_BASED 模式，都只返回一个免费单元。
 */
public class FlatFreeRule implements BillingRule<FlatFreeConfig> {

    private static final String FREE_PROMOTION_ID = "FLAT_FREE";

    @Override
    public Class<FlatFreeConfig> configClass() {
        return FlatFreeConfig.class;
    }

    @Override
    public Set<BConstants.BillingMode> supportedModes() {
        return Set.of(BConstants.BillingMode.CONTINUOUS, BConstants.BillingMode.UNIT_BASED);
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context,
                                          FlatFreeConfig ruleConfig,
                                          PromotionAggregate promotionAggregate) {
        LocalDateTime calcBegin = context.getWindow().getCalculationBegin();
        LocalDateTime calcEnd = context.getWindow().getCalculationEnd();

        int durationMinutes = (int) Duration.between(calcBegin, calcEnd).toMinutes();

        BillingUnit unit = BillingUnit.builder()
                .beginTime(calcBegin)
                .endTime(calcEnd)
                .durationMinutes(durationMinutes)
                .unitPrice(BigDecimal.ZERO)
                .originalAmount(BigDecimal.ZERO)
                .chargedAmount(BigDecimal.ZERO)
                .free(true)
                .freePromotionId(FREE_PROMOTION_ID)
                .build();

        // 保存结转状态，使调用方能识别这是 CONTINUE 模式的计算结果
        Map<String, Object> ruleOutputState = Map.of(
                "flatFree", Map.of("calculatedUpTo", calcEnd)
        );

        return BillingSegmentResult.builder()
                .segmentId(context.getSegment().getId())
                .segmentStartTime(context.getSegment().getBeginTime())
                .segmentEndTime(context.getSegment().getEndTime())
                .calculationStartTime(calcBegin)
                .calculationEndTime(calcEnd)
                .chargedAmount(BigDecimal.ZERO)
                .billingUnits(List.of(unit))
                .promotionUsages(List.of())
                .promotionAggregate(promotionAggregate)
                .feeEffectiveStart(calcBegin)
                .feeEffectiveEnd(calcEnd)
                .ruleOutputState(ruleOutputState)
                .build();
    }
}
