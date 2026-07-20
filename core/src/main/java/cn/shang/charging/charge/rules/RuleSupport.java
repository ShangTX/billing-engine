package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeMinutes;
import cn.shang.charging.promotion.pojo.PromotionActivationMode;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则族共享无状态工具（层 2 旁路）：不通过继承传递的策略侧公共能力。
 * <p>
 * 当前承载 FROM_START 模式的 FREE_MINUTES 时段化（{@link #materializeFreeMinutes}），供
 * CONTINUOUS 策略族（DayNight/RelativeTime/NaturalTime/CompositeTime）、UNIT_BASED 与
 * DURATION_PERIOD 共享。DURATION_GLOBAL 需要同时支持价格感知分配，使用自己的分配逻辑。
 * <p>
 * TODO-20260706-002 阶段7：从 {@code AbstractTimeBasedRule} 搬出，废弃旧基类。
 */
public final class RuleSupport {

    /** 策略侧 FREE_MINUTES 时段化工具实例（无状态，共享）。 */
    public static final FreeMinuteAllocator FREE_MINUTE_ALLOCATOR = new FreeMinuteAllocator();

    private RuleSupport() {
    }

    /**
     * 把 PromotionAggregate 中的未时段化 FREE_MINUTES 时段化为时间段，与 FREE_RANGE 合并，
     * 返回最终免费段 + FREE_MINUTES usage。
     * <p>
     * CONTINUOUS 策略（DayNight/RelativeTime/NaturalTime/CompositeTime）、UNIT_BASED 与
     * DURATION_PERIOD 在 {@code calculate} 入口调用本方法获得 finalFreeRanges，替换旧路径直接读
     * {@code aggregate.getFreeTimeRanges()} 的行为（后者现在只含 FREE_RANGE）。
     * FREE_MINUTES 时段化已从 PromotionEngine 下放到策略侧（TODO-20260702-004）。
     * <p>
     * allocationMode 为空或 FROM_START 的免费分钟会在这里处理；CHARGED_TIME / HIGHEST_PRICE
     * 依赖规则价格语义，仅由 DURATION_GLOBAL 支持。
     * <p>
     * 无 FREE_MINUTES 时返回 {@code aggregate.freeTimeRanges}（FREE_RANGE）+ 空 usages，不产生副作用。
     *
     * @param promotionAggregate 优惠聚合（中间形式）
     * @param window              计算窗口
     * @return finalFreeRanges（FREE_RANGE + 时段化 FREE_MINUTES，已合并）+ FREE_MINUTES usages
     */
    public static FreeMinuteAllocationResult materializeFreeMinutes(
            PromotionAggregate promotionAggregate, CalculationWindow window) {
        List<FreeMinutes> freeMinutesList = promotionAggregate != null
                ? promotionAggregate.getFreeMinutesList() : null;
        List<FreeTimeRange> freeRangeOnly = promotionAggregate != null
                && promotionAggregate.getFreeTimeRanges() != null
                ? promotionAggregate.getFreeTimeRanges() : List.of();
        if (freeMinutesList == null || freeMinutesList.isEmpty()) {
            return new FreeMinuteAllocationResult()
                    .setFinalFreeRanges(freeRangeOnly)
                    .setPromotionUsages(List.of());
        }
        List<FreeMinutes> fromStartMinutes = freeMinutesList.stream()
                .filter(RuleSupport::isFromStartAllocation)
                .toList();
        if (fromStartMinutes.isEmpty()) {
            return new FreeMinuteAllocationResult()
                    .setFinalFreeRanges(freeRangeOnly)
                    .setPromotionUsages(List.of());
        }
        return FREE_MINUTE_ALLOCATOR.allocateAndMerge(fromStartMinutes, freeRangeOnly, window);
    }

    /**
     * Materializes FREE_MINUTES and applies conditional activation after allocation.
     * <p>
     * Conditional ranges still participate in allocation/priority resolution first; this intentionally avoids
     * re-flowing other promotions when a conditional promotion becomes inactive.
     */
    public static FreeMinuteAllocationResult materializeFreeMinutesForDuration(
            PromotionAggregate promotionAggregate, CalculationWindow window, LocalDateTime billingEnd) {
        FreeMinuteAllocationResult materialized = materializeFreeMinutes(promotionAggregate, window);
        List<FreeTimeRange> allRanges = materialized.getFinalFreeRanges() != null
                ? materialized.getFinalFreeRanges() : List.of();
        List<FreeTimeRange> activeRanges = filterActiveFreeRanges(allRanges, billingEnd);
        List<PromotionUsage> activeUsages = filterActivePromotionUsages(
                materialized.getPromotionUsages(), allRanges, activeRanges);
        return new FreeMinuteAllocationResult()
                .setFinalFreeRanges(activeRanges)
                .setPromotionUsages(activeUsages);
    }

    public static boolean hasConditionalActivation(PromotionAggregate promotionAggregate) {
        if (promotionAggregate == null) {
            return false;
        }
        if (hasConditionalRanges(promotionAggregate.getFreeTimeRanges())) {
            return true;
        }
        if (hasConditionalMinutes(promotionAggregate.getFreeMinutesList())) {
            return true;
        }
        return false;
    }

    public static boolean hasPriceAwareFreeMinutes(PromotionAggregate promotionAggregate) {
        return promotionAggregate != null
                && promotionAggregate.getFreeMinutesList() != null
                && promotionAggregate.getFreeMinutesList().stream()
                .anyMatch(minutes -> !isFromStartAllocation(minutes));
    }

    public static BConstants.FreeMinutesAllocationMode freeMinutesAllocationMode(FreeMinutes minutes) {
        if (minutes == null || minutes.getAllocationMode() == null) {
            return BConstants.FreeMinutesAllocationMode.FROM_START;
        }
        return minutes.getAllocationMode();
    }

    public static boolean isFromStartAllocation(FreeMinutes minutes) {
        return freeMinutesAllocationMode(minutes) == BConstants.FreeMinutesAllocationMode.FROM_START;
    }

    public static void assertConditionalActivationSupported(
            PromotionAggregate promotionAggregate, BConstants.CalculationMode calculationMode) {
        if (!hasConditionalActivation(promotionAggregate)) {
            return;
        }
        if (calculationMode == BConstants.CalculationMode.DURATION_PERIOD
                || calculationMode == BConstants.CalculationMode.DURATION_GLOBAL) {
            return;
        }
        throw new IllegalStateException(
                "activationMode END_WITHIN_RANGE is only supported in duration modes, but current mode is: "
                        + calculationMode);
    }

    public static List<FreeTimeRange> filterActiveFreeRanges(
            List<FreeTimeRange> ranges, LocalDateTime billingEnd) {
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }
        Set<PromotionKey> activeConditionalKeys = new HashSet<>();
        for (FreeTimeRange range : ranges) {
            if (isConditional(range) && range.getId() != null && containsEnd(range, billingEnd)) {
                activeConditionalKeys.add(PromotionKey.of(range));
            }
        }
        return ranges.stream()
                .filter(range -> {
                    if (!isConditional(range)) {
                        return true;
                    }
                    if (range.getId() == null) {
                        return containsEnd(range, billingEnd);
                    }
                    return activeConditionalKeys.contains(PromotionKey.of(range));
                })
                .toList();
    }

    public static List<PromotionUsage> filterActivePromotionUsages(
            List<PromotionUsage> usages,
            List<FreeTimeRange> allRanges,
            List<FreeTimeRange> activeRanges) {
        if (usages == null || usages.isEmpty()) {
            return List.of();
        }
        Set<PromotionKey> conditionalKeys = new HashSet<>();
        if (allRanges != null) {
            for (FreeTimeRange range : allRanges) {
                if (isConditional(range) && range.getId() != null) {
                    conditionalKeys.add(PromotionKey.of(range));
                }
            }
        }
        if (conditionalKeys.isEmpty()) {
            return usages;
        }
        Set<PromotionKey> activeKeys = new HashSet<>();
        if (activeRanges != null) {
            for (FreeTimeRange range : activeRanges) {
                if (range.getId() != null) {
                    activeKeys.add(PromotionKey.of(range));
                }
            }
        }
        return usages.stream()
                .filter(usage -> {
                    if (usage.getPromotionId() == null) {
                        return true;
                    }
                    PromotionKey key = PromotionKey.of(usage.getPromotionId(), usage.getType());
                    return !conditionalKeys.contains(key) || activeKeys.contains(key);
                })
                .toList();
    }

    private static boolean hasConditionalRanges(List<FreeTimeRange> ranges) {
        return ranges != null && ranges.stream().anyMatch(RuleSupport::isConditional);
    }

    private static boolean hasConditionalMinutes(List<FreeMinutes> minutes) {
        return minutes != null && minutes.stream().anyMatch(minute ->
                minute.getActivationMode() == PromotionActivationMode.END_WITHIN_RANGE);
    }

    private static boolean isConditional(FreeTimeRange range) {
        return range != null && range.getActivationMode() == PromotionActivationMode.END_WITHIN_RANGE;
    }

    private static boolean containsEnd(FreeTimeRange range, LocalDateTime billingEnd) {
        return billingEnd != null
                && range.getBeginTime() != null
                && range.getEndTime() != null
                && !billingEnd.isBefore(range.getBeginTime())
                && !billingEnd.isAfter(range.getEndTime());
    }

    private record PromotionKey(String id, BConstants.PromotionType type) {
        static PromotionKey of(FreeTimeRange range) {
            return of(range.getId(), range.getPromotionType());
        }

        static PromotionKey of(String id, BConstants.PromotionType type) {
            return new PromotionKey(id, type);
        }
    }
}
