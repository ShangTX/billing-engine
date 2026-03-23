package cn.shang.charging.promotion;

import cn.shang.charging.billing.pojo.PromotionCarryOver;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 优惠聚合工具类
 */
public class PromotionAggregateUtil {

    private PromotionAggregateUtil() {
        // 工具类，禁止实例化
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

        // 过滤剩余分钟数
        Map<String, Integer> filteredRemainingMinutes = null;
        if (carryOver.getRemainingMinutes() != null) {
            filteredRemainingMinutes = carryOver.getRemainingMinutes().entrySet().stream()
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

        return PromotionCarryOver.builder()
            .remainingMinutes(filteredRemainingMinutes)
            .usedFreeRanges(filteredUsedRanges)
            .build();
    }
}