package cn.shang.charging.promotion;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.pojo.*;
import cn.shang.charging.promotion.rules.PromotionRule;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 优惠计算engine
 */
@AllArgsConstructor
public class PromotionEngine {

    private BillingConfigResolver billingConfigResolver;
    private FreeTimeRangeMerger freeTimeRangeMerger;
    private FreeMinuteAllocator freeMinuteAllocator;
    private PromotionRuleRegistry promotionRuleRegistry;

    public PromotionAggregate evaluate(BillingContext context) {
        // 1️⃣ 确定本次 promotion 计算的时间窗口
        CalculationWindow window = context.getWindow();
        // window 内部已经处理了：
        // - 方案分段
        // - SEGMENT_ORIGIN / GLOBAL_ORIGIN
        List<FreeTimeRange> timeRangePromotions = new ArrayList<>();
        List<FreeMinutes> freeMinutesPromotions = new ArrayList<>();


        // 2.1 来自优惠规则（按方案 + 时间段）
        for (PromotionRuleConfig ruleConfig : context.getPromotionRules()) {
            List<PromotionGrant> grants = grant(context, ruleConfig);
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

        // 3️⃣ 应用优惠结转状态（CONTINUE 模式）
        Map<String, Integer> remainingMinutes = null;
        List<FreeTimeRange> usedFreeRanges = null;
        if (context.getPromotionCarryOver() != null) {
            remainingMinutes = context.getPromotionCarryOver().getRemainingMinutes();
            usedFreeRanges = context.getPromotionCarryOver().getUsedFreeRanges();
        }

        // 4️⃣ 应用剩余免费分钟数
        if (remainingMinutes != null && !remainingMinutes.isEmpty()) {
            applyRemainingMinutes(freeMinutesPromotions, remainingMinutes);
        }

        // 5️⃣ 处理已使用的免费时段（排除已用部分）
        List<FreeTimeRange> filteredTimeRangePromotions = timeRangePromotions;
        if (usedFreeRanges != null && !usedFreeRanges.isEmpty()) {
            filteredTimeRangePromotions = filterUsedFreeRanges(timeRangePromotions, usedFreeRanges, window);
        }

        // 6️⃣ 合并显式免费时间段
        TimeRangeMergeResult rangeMergeResult = freeTimeRangeMerger.merge(
                filteredTimeRangePromotions,
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
        var finalMergeResult = freeTimeRangeMerger.merge(
                Stream.concat(
                        explicitFreeRanges.stream(),
                        minuteResult.getGeneratedFreeRanges().stream()
                ).toList(),
                window.getCalculationBegin(),
                window.getCalculationEnd()
        );
        List<FreeTimeRange> finalFreeRanges = finalMergeResult.getMergedRanges();
        List<FreeTimeRange> boundaryReferences = finalMergeResult.getBoundaryReferences();

        // 7️⃣ 构建优惠结转输出状态
        PromotionCarryOver outputCarryOver = buildPromotionCarryOver(
                minuteResult.getPromotionUsages(),
                finalFreeRanges,
                window.getCalculationEnd()
        );

        return PromotionAggregate.builder()
                .freeTimeRanges(finalFreeRanges)
                .boundaryReferences(boundaryReferences)
                .usages(minuteResult.getPromotionUsages())
                .promotionCarryOver(outputCarryOver)
                .build();
    }

    /**
     * 应用剩余免费分钟数
     * 将结转的剩余分钟数更新到免费分钟数列表中
     */
    private void applyRemainingMinutes(List<FreeMinutes> freeMinutesPromotions, Map<String, Integer> remainingMinutes) {
        for (FreeMinutes fm : freeMinutesPromotions) {
            Integer remaining = remainingMinutes.get(fm.getId());
            if (remaining != null && remaining > 0) {
                // 使用剩余分钟数替换原始分钟数
                fm.setMinutes(remaining);
            } else if (remaining != null && remaining <= 0) {
                // 已用完，标记为 0
                fm.setMinutes(0);
            }
        }
    }

    /**
     * 过滤已使用的免费时段
     * 从可用时段中排除已使用的部分
     */
    private List<FreeTimeRange> filterUsedFreeRanges(List<FreeTimeRange> timeRangePromotions,
                                                      List<FreeTimeRange> usedFreeRanges,
                                                      CalculationWindow window) {
        List<FreeTimeRange> result = new ArrayList<>();

        for (FreeTimeRange range : timeRangePromotions) {
            List<FreeTimeRange> remaining = List.of(range);

            // 对每个已使用时段，从剩余时段中减去
            for (FreeTimeRange used : usedFreeRanges) {
                if (used.getId() != null && used.getId().equals(range.getId())) {
                    remaining = subtractFreeRanges(remaining, used);
                }
            }

            result.addAll(remaining);
        }

        return result;
    }

    /**
     * 从时段列表中减去一个已使用时段
     */
    private List<FreeTimeRange> subtractFreeRanges(List<FreeTimeRange> ranges, FreeTimeRange used) {
        List<FreeTimeRange> result = new ArrayList<>();

        for (FreeTimeRange range : ranges) {
            // 无交集，保留原时段
            if (!range.overlaps(used)) {
                result.add(range);
                continue;
            }

            // 有交集，分割时段
            // 前半段：range.beginTime ~ used.beginTime
            if (range.getBeginTime().isBefore(used.getBeginTime())) {
                result.add(FreeTimeRange.builder()
                        .id(range.getId())
                        .beginTime(range.getBeginTime())
                        .endTime(used.getBeginTime())
                        .priority(range.getPriority())
                        .promotionType(range.getPromotionType())
                        .build());
            }

            // 后半段：used.endTime ~ range.endTime
            if (used.getEndTime().isBefore(range.getEndTime())) {
                result.add(FreeTimeRange.builder()
                        .id(range.getId())
                        .beginTime(used.getEndTime())
                        .endTime(range.getEndTime())
                        .priority(range.getPriority())
                        .promotionType(range.getPromotionType())
                        .build());
            }
        }

        return result;
    }

    /**
     * 构建优惠结转输出状态
     */
    private PromotionCarryOver buildPromotionCarryOver(List<PromotionUsage> usages,
                                                        List<FreeTimeRange> finalFreeRanges,
                                                        LocalDateTime calculationEndTime) {
        // 计算剩余免费分钟数
        Map<String, Integer> remainingMinutes = new HashMap<>();
        for (PromotionUsage usage : usages) {
            int remaining = (int) (usage.getGrantedMinutes() - usage.getUsedMinutes());
            // 记录所有使用过的优惠，包括已用完的（remaining=0）
            // 这样在 CONTINUE 模式下可以正确识别哪些优惠已经用完
            if (remaining >= 0) {
                remainingMinutes.put(usage.getPromotionId(), remaining);
            }
        }

        // 记录部分使用的免费时段（在当前窗口内实际生效的部分）
        List<FreeTimeRange> usedFreeRanges = new ArrayList<>();
        for (FreeTimeRange range : finalFreeRanges) {
            // 记录所有在当前计算窗口内生效的免费时段
            if (range.getPromotionType() == BConstants.PromotionType.FREE_RANGE) {
                // 只要免费时段在计算窗口内（endTime <= calculationEndTime），就记录
                if (!range.getEndTime().isAfter(calculationEndTime)) {
                    usedFreeRanges.add(FreeTimeRange.builder()
                            .id(range.getId())
                            .beginTime(range.getBeginTime())
                            .endTime(range.getEndTime())
                            .promotionType(range.getPromotionType())
                            .build());
                }
            }
        }

        return PromotionCarryOver.builder()
                .remainingMinutes(remainingMinutes.isEmpty() ? null : remainingMinutes)
                .usedFreeRanges(usedFreeRanges.isEmpty() ? null : usedFreeRanges)
                .build();
    }

    /**
     * 计算有效优惠
     */
    private List<PromotionGrant> grant(BillingContext context, PromotionRuleConfig ruleConfig) {
        var rule = promotionRuleRegistry.get(ruleConfig.getType());
        if (!rule.getType().equals(ruleConfig.getType())) {
            throw new IllegalStateException("PromotionRuleConfig mismatch");
        }
        return invokeRule(rule, context, ruleConfig);

    }
    private <C extends PromotionRuleConfig> List<PromotionGrant> invokeRule(
            PromotionRule<C> rule,
            BillingContext context,
            PromotionRuleConfig rawConfig) {

        if (!rule.getConfigClass().isInstance(rawConfig)) {
            throw new IllegalStateException("PromotionRuleConfig mismatch");
        }
        C config = rule.getConfigClass().cast(rawConfig);
        return rule.grant(context, config);
    }

    /**
     * 将优惠规则中的优惠时间段转为计算的免费时间段
     */
    private FreeTimeRange convertTimeRangeFromRule(PromotionGrant grant) {
        return FreeTimeRange.builder()
                .id(grant.getId())
                .promotionType(grant.getType())
                .beginTime(grant.getBeginTime())
                .endTime(grant.getEndTime())
                .priority(grant.getPriority())
                .build();
    }

    /**
     * 将优惠规则中的免费时间段转化未计算的免费分钟数
     */
    private FreeMinutes convertMinutesFromRule(PromotionGrant grant) {
        return FreeMinutes.builder()
                .id(grant.getId())
                .minutes(grant.getFreeMinutes())
                .priority(grant.getPriority())
                .build();
    }


}
