package cn.shang.charging.promotion.rules.startfree;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 前N分钟免费优惠规则
 * <p>
 * 从计费段起点开始的 N 分钟为免费时段，生成的免费时间段和已有的
 * FREE_RANGE 按优先级合并，不会像 FREE_MINUTES 那样避开已有免费时段。
 */
public class StartFreePromotionRule implements PromotionRule<StartFreePromotionConfig> {

    @Override
    public String getType() {
        return BConstants.PromotionRuleType.START_FREE;
    }

    @Override
    public Class<StartFreePromotionConfig> getConfigClass() {
        return StartFreePromotionConfig.class;
    }

    @Override
    public List<PromotionGrant> grant(BillingContext billingContext, StartFreePromotionConfig config) {
        BillingSegment segment = billingContext.getSegment();
        LocalDateTime beginTime = segment.getBeginTime();
        LocalDateTime endTime = beginTime.plusMinutes(config.getMinutes());

        var promotionGrant = PromotionGrant.builder()
                .id(config.getId())
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.RULE)
                .priority(config.getPriority())
                .beginTime(beginTime)
                .endTime(endTime)
                .build();
        return List.of(promotionGrant);
    }
}
