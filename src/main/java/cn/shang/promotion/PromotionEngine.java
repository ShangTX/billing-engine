package cn.shang.promotion;

import cn.shang.billing.RuleResolver;
import cn.shang.billing.pojo.*;
import cn.shang.promotion.pojo.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 优惠计算engine
 */
@Service
public class PromotionEngine {


    private final RuleResolver ruleResolver;
    private final FreeTimeRangeMerger freeTimeRangeMerger;
    private final FreeMinuteAllocator freeMinuteAllocator;

    public PromotionAggregate evaluate(BillingContext context) {
        // 1️⃣ 确定本次 promotion 计算的时间窗口
        CalculationWindow window = context.getWindow();
        // window 内部已经处理了：
        // - 方案分段
        // - SEGMENT_ORIGIN / GLOBAL_ORIGIN
        List<PromotionContribution> timeRangePromotions = new ArrayList<>();
        List<PromotionContribution> freeMinutesPromotions = new ArrayList<>();


        // 2.1 来自优惠规则（按方案 + 时间段）
        List<PromotionRuleSnapshot> promotionRules = context.getPromotionRules();
        for (PromotionRuleSnapshot rule : context.getPromotionRules()) {
            PromotionContribution grant = rule.grant(context, window);
            if (grant.getType() == BConstants.PromotionType.FREE_MINUTES) {
                freeMinutesPromotions.add(grant);
            }
            if (grant.getType() == BConstants.PromotionType.FREE_RANGE) {
                timeRangePromotions.add(grant);
            }
        }

        // 2️⃣ 来自外部优惠
        for (PromotionContribution externalPromotion : context.getExternalPromotions()) {
            if (externalPromotion.getType() == BConstants.PromotionType.FREE_MINUTES) {
                freeMinutesPromotions.add(externalPromotion);
            }
            if (externalPromotion.getType() == BConstants.PromotionType.FREE_RANGE) {
                timeRangePromotions.add(externalPromotion);
            }
        }

        // 3️⃣ 合并显式免费时间段
        List<FreeTimeRange> explicitFreeRanges =
                freeTimeRangeMerger.merge(
                        rawInput.getFreeTimeRanges(),
                        window
                );

        // 免费分钟数转为时间段
        FreeMinuteAllocationResult minuteResult =
                freeMinuteAllocator.allocate(
                        rawInput.getFreeMinuteGrants(),
                        explicitFreeRanges,
                        window
                );

        // 最终合并
        List<FreeTimeRange> finalFreeRanges =
                freeTimeRangeMerger.merge(
                        Stream.concat(
                                explicitFreeRanges.stream(),
                                minuteResult.getGeneratedFreeRanges().stream()
                        ).toList(),
                        window
                );

        return PromotionAggregate.builder()
                .freeTimeRanges(finalFreeRanges)
                .promotionUsages(minuteResult.getUsages())
                .unconsumedPromotions(minuteResult.getUnconsumed())
                .build();
    }


    @Data
    public static class PromotionRawInput {
        List<FreeMinuteGrant> minuteGrants;
        List<FreeTimeRange> timeRangeGrants;
    }


}
