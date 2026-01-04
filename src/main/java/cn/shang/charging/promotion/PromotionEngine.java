package cn.shang.charging.promotion;

import cn.shang.charging.billing.RuleResolver;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.pojo.*;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 优惠计算engine
 */
@AllArgsConstructor
@Service
public class PromotionEngine {

    private RuleResolver ruleResolver;
    private FreeTimeRangeMerger freeTimeRangeMerger;
    private FreeMinuteAllocator freeMinuteAllocator;

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
            List<PromotionGrant> grants = rule.grant(context, window);
            grants.forEach(grant -> {
                if (grant.getType() == BConstants.PromotionType.FREE_RANGE) {
                    timeRangePromotions.add(convertTimeRangeFromRule(grant));
                }
                if (grant.getType() == BConstants.PromotionType.FREE_MINUTES) {
                    freeMinutesPromotions.add(convertMinutesFromRule(grant));
                }
            });
        }

        // 2️⃣ 来自外部优惠
        for (PromotionGrant externalPromotion : context.getExternalPromotions()) {
            if (externalPromotion.getType() == BConstants.PromotionType.FREE_RANGE) {
                timeRangePromotions.add(convertTimeRangeFromRule(externalPromotion));
            }
            if (externalPromotion.getType() == BConstants.PromotionType.FREE_MINUTES) {
                freeMinutesPromotions.add(convertMinutesFromRule(externalPromotion));
            }
        }

        // 3️⃣ 合并显式免费时间段
        TimeRangeMergeResult rangeMergeResult = freeTimeRangeMerger.merge(
                timeRangePromotions,
                context.getBeginTime(),
                context.getEndTime());
        List<FreeTimeRange> explicitFreeRanges = rangeMergeResult.getMergedRanges();

        // 免费分钟数转为时间段
        FreeMinuteAllocationResult minuteResult =
                freeMinuteAllocator.allocate(
                        freeMinutesPromotions,
                        explicitFreeRanges,
                        window
                );

        // 最终合并
        List<FreeTimeRange> finalFreeRanges = freeTimeRangeMerger.merge(
                Stream.concat(
                        explicitFreeRanges.stream(),
                        minuteResult.getGeneratedFreeRanges().stream()
                ).toList(),
                window.getCalculationBegin(),
                window.getCalculationEnd()
        ).getMergedRanges();

        return PromotionAggregate.builder()
                .freeTimeRanges(finalFreeRanges)
                .usages(List.of())
                .build();
    }

    /**
     * 将优惠规则中的优惠时间段转为计算的免费时间段
     */
    private FreeTimeRange convertTimeRangeFromRule(PromotionGrant grant) {
        return null;
    }

    /**
     * 将优惠规则中的免费时间段转化未计算的免费分钟数
     */
    private FreeMinutes convertMinutesFromRule(PromotionGrant grant) {
        return null;
    }


}
