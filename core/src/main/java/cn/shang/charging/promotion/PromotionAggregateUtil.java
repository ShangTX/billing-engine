package cn.shang.charging.promotion;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.promotion.pojo.FreeMinutes;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
                    .source(range.getSource())
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
     * 从聚合结果中排除指定优惠（等效金额消去法用）。
     * 过滤 FREE_RANGE 时段 + 未时段化 FREE_MINUTES / SMART_FREE_MINUTES 列表，重算总免费分钟数。
     *
     * @param original    原始聚合结果
     * @param excludedIds 要排除的优惠ID
     * @return 新的聚合结果
     */
    public static PromotionAggregate exclude(PromotionAggregate original, Set<String> excludedIds) {
        if (original == null || excludedIds == null || excludedIds.isEmpty()) {
            return original;
        }

        // 1. 过滤免费时间段（FREE_RANGE）
        List<FreeTimeRange> filteredRanges = original.getFreeTimeRanges() == null
            ? List.of()
            : original.getFreeTimeRanges().stream()
                .filter(r -> r.getId() != null && !excludedIds.contains(r.getId()))
                .toList();

        // 2. 过滤未时段化的 FREE_MINUTES 列表
        List<FreeMinutes> filteredFreeMinutesList = original.getFreeMinutesList() == null
            ? List.of()
            : original.getFreeMinutesList().stream()
                .filter(fm -> fm.getId() != null && !excludedIds.contains(fm.getId()))
                .toList();

        // 3. 过滤未时段化的 SMART_FREE_MINUTES 列表（TODO-20260706-002 阶段5）
        List<FreeMinutes> filteredSmartFreeMinutesList = original.getSmartFreeMinutesList() == null
            ? List.of()
            : original.getSmartFreeMinutesList().stream()
                .filter(fm -> fm.getId() != null && !excludedIds.contains(fm.getId()))
                .toList();

        // 4. 重算总免费分钟数（以 freeMinutesList 为准，SMART_FREE_MINUTES 不计入简化判定）
        long filteredFreeMinutes = filteredFreeMinutesList.stream()
            .filter(fm -> fm.getMinutes() != null)
            .mapToLong(FreeMinutes::getMinutes)
            .sum();

        return PromotionAggregate.builder()
            .freeTimeRanges(filteredRanges)
            .freeMinutes(filteredFreeMinutes)
            .freeMinutesList(filteredFreeMinutesList)
            .smartFreeMinutesList(filteredSmartFreeMinutesList)
            .amountDiscounts(original.getAmountDiscounts())
            .totalAmountDiscount(original.getTotalAmountDiscount())
            .bestDiscountRate(original.getBestDiscountRate())
            .build();
    }
}
