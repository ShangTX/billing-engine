package cn.shang.promotion;

import cn.shang.billing.RuleResolver;
import cn.shang.billing.pojo.*;
import cn.shang.charge.pojo.merge.TimeSlotMergeWithDiscard;
import cn.shang.promotion.pojo.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 优惠计算engine
 */
@AllArgsConstructor
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
        List<FreeTimeRange> timeRangePromotions = new ArrayList<>();
        List<FreeMinutes> freeMinutesPromotions = new ArrayList<>();


        // 2.1 来自优惠规则（按方案 + 时间段）
        for (PromotionRuleSnapshot rule : context.getPromotionRules()) {
            List<PromotionContribution> grants = rule.grant(context, window);
            grants.forEach(grant -> {
                if (grant.getType() == BConstants.PromotionType.FREE_MINUTES) {
                    freeMinutesPromotions.addAll(grant);
                }
                if (grant.getType() == BConstants.PromotionType.FREE_RANGE) {
                    timeRangePromotions.addAll(grant);
                }
            });
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
        TimeRangeMergeResult rangeMergeResult = freeTimeRangeMerger.merge(
                        timeRangePromotions,
                        context.getBeginTime(),
                        context.getEndTime()
                );
        List<FreeTimeRange> explicitFreeRanges = rangeMergeResult.getMergedRanges();

        // 免费分钟数转为时间段
        FreeMinuteAllocationResult minuteResult =
                freeMinuteAllocator.allocate(
                        freeMinutesPromotions,
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
        List<FreeMinutes> minuteGrants;
        List<FreeTimeRange> timeRangeGrants;
    }


}
