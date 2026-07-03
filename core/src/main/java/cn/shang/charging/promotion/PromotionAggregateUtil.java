package cn.shang.charging.promotion;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.PromotionCarryOver;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 优惠聚合工具类
 */
public class PromotionAggregateUtil {

    private PromotionAggregateUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 为 FREE_RANGE 类型的免费时段产出 PromotionUsage。
     * <p>
     * 遍历 freeTimeRanges 中 promotionType=FREE_RANGE 的时段，计算其在计算窗口内的实际覆盖
     * （usedFrom/usedTo/usedMinutes）和等效优惠金额（equivalentAmount，由回调按 range.id 聚合）。
     * <p>
     * FREE_MINUTES 时段化后的 FreeTimeRange（promotionType=FREE_MINUTES）不在本方法范围，
     * 其 usage 由 FreeMinuteAllocator 产出。
     *
     * @param freeTimeRanges             免费时段列表（含 FREE_RANGE 和 FREE_MINUTES 时段化结果）
     * @param calcBegin                  计算窗口起点
     * @param calcEnd                    计算窗口终点
     * @param equivalentAmountByRangeId  按 range.id 聚合等效优惠金额的回调（免费段原价之和）
     * @return FREE_RANGE 类型的 PromotionUsage 列表
     */
    public static List<PromotionUsage> buildFreeRangeUsages(
            List<FreeTimeRange> freeTimeRanges,
            LocalDateTime calcBegin,
            LocalDateTime calcEnd,
            Function<String, BigDecimal> equivalentAmountByRangeId) {
        List<PromotionUsage> usages = new ArrayList<>();
        if (freeTimeRanges == null) {
            return usages;
        }
        for (FreeTimeRange range : freeTimeRanges) {
            if (range.getPromotionType() != BConstants.PromotionType.FREE_RANGE) {
                continue;
            }
            LocalDateTime usedFrom = range.getBeginTime().isBefore(calcBegin) ? calcBegin : range.getBeginTime();
            LocalDateTime usedTo = range.getEndTime().isAfter(calcEnd) ? calcEnd : range.getEndTime();
            if (!usedFrom.isBefore(usedTo)) {
                continue;
            }
            long usedMinutes = Duration.between(usedFrom, usedTo).toMinutes();
            long grantedMinutes = Duration.between(range.getBeginTime(), range.getEndTime()).toMinutes();
            BigDecimal equivalent = equivalentAmountByRangeId != null
                    ? equivalentAmountByRangeId.apply(range.getId()) : null;
            if (equivalent == null) {
                equivalent = BigDecimal.ZERO;
            }
            usages.add(PromotionUsage.builder()
                    .promotionId(range.getId())
                    .type(BConstants.PromotionType.FREE_RANGE)
                    .grantedMinutes(grantedMinutes)
                    .usedMinutes(usedMinutes)
                    .usedFrom(usedFrom)
                    .usedTo(usedTo)
                    .equivalentAmount(equivalent)
                    .build());
        }
        return usages;
    }

    /**
     * 从聚合结果中排除指定优惠
     *
     * @param original    原始聚合结果
     * @param excludedIds 要排除的优惠ID
     * @return 新的聚合结果
     */
    public static PromotionAggregate exclude(PromotionAggregate original, Set<String> excludedIds) {
        if (original == null || excludedIds == null || excludedIds.isEmpty()) {
            return original;
        }

        // 1. 过滤免费时间段
        List<FreeTimeRange> filteredRanges = original.getFreeTimeRanges() == null
            ? List.of()
            : original.getFreeTimeRanges().stream()
                .filter(r -> r.getId() != null && !excludedIds.contains(r.getId()))
                .toList();

        // 2. 过滤使用记录
        List<PromotionUsage> filteredUsages = original.getUsages() == null
            ? List.of()
            : original.getUsages().stream()
                .filter(u -> u.getPromotionId() != null && !excludedIds.contains(u.getPromotionId()))
                .toList();

        // 3. 重算总免费分钟数
        // 从过滤后的 usages 中累加 grantedMinutes
        // 注意：等效金额计算在完整计费后进行，此时 usages 已生成
        long filteredFreeMinutes = filteredUsages.stream()
            .mapToLong(PromotionUsage::getGrantedMinutes)
            .sum();

        // 4. 处理 promotionCarryOver（排除已排除优惠的结转状态）
        PromotionCarryOver filteredCarryOver = filterCarryOver(original.getPromotionCarryOver(), excludedIds);

        return PromotionAggregate.builder()
            .freeTimeRanges(filteredRanges)
            .freeMinutes(filteredFreeMinutes)
            .usages(filteredUsages)
            .promotionCarryOver(filteredCarryOver)
            .build();
    }

    /**
     * 过滤优惠结转状态
     */
    private static PromotionCarryOver filterCarryOver(PromotionCarryOver carryOver, Set<String> excludedIds) {
        if (carryOver == null) {
            return null;
        }

        // 过滤剩余分钟数（使用转换后的 Map）
        Map<String, Integer> filteredRemainingMinutes = null;
        Map<String, Integer> convertedRemainingMinutes = carryOver.getRemainingMinutesConverted();
        if (convertedRemainingMinutes != null) {
            filteredRemainingMinutes = convertedRemainingMinutes.entrySet().stream()
                .filter(e -> !excludedIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        // 过滤已使用的免费时段
        List<FreeTimeRange> filteredUsedRanges = null;
        if (carryOver.getUsedFreeRanges() != null) {
            filteredUsedRanges = carryOver.getUsedFreeRanges().stream()
                .filter(r -> r.getId() == null || !excludedIds.contains(r.getId()))
                .toList();
        }

        if ((filteredRemainingMinutes == null || filteredRemainingMinutes.isEmpty())
            && (filteredUsedRanges == null || filteredUsedRanges.isEmpty())) {
            return null;
        }

        // 构建 PromotionCarryOver，remainingMinutes 使用 Map<String, Object> 类型
        // filteredRemainingMinutes 是 Map<String, Integer>，需要转为 Map<String, Object>
        Map<String, Object> remainingMinutesObj = null;
        if (filteredRemainingMinutes != null && !filteredRemainingMinutes.isEmpty()) {
            remainingMinutesObj = new HashMap<>();
            for (Map.Entry<String, Integer> entry : filteredRemainingMinutes.entrySet()) {
                remainingMinutesObj.put(entry.getKey(), entry.getValue());
            }
        }

        return PromotionCarryOver.builder()
            .remainingMinutes(remainingMinutesObj)
            .usedFreeRanges(filteredUsedRanges)
            .build();
    }
}