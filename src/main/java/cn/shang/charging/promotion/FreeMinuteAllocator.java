package cn.shang.charging.promotion;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeMinutes;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class FreeMinuteAllocator {

    record TimeRange(LocalDateTime beginTime, LocalDateTime endTime) {

    }

    public FreeMinuteAllocationResult allocate(List<FreeMinutes> freeMinutesPromotions,
                                               List<FreeTimeRange> explicitFreeRanges,
                                               CalculationWindow window) {
        // 首先对免费分钟数进行排序
        var sortedFreeMinutesPromotions = freeMinutesPromotions.stream()
                .sorted(Comparator.comparing(FreeMinutes::getPriority)).toList();

        // ===== 1. 初始化结果容器 =====

        List<FreeTimeRange> generatedFreeRanges = new ArrayList<>();
        List<PromotionUsage> promotionUsages = new ArrayList<>();


        // ⚠️ subtractFreeRanges:
        // - 返回 window 内所有“非免费”的连续时间段
        // - 已假设 explicitFreeRanges 已合并、排序
        LocalDateTime cursor = window.getCalculationBegin();
        var timeRangeIterator = explicitFreeRanges.iterator(); // 已有时间段迭代器
        var freeMinutesIterator = sortedFreeMinutesPromotions.iterator(); // 免费分钟数迭代器
        FreeTimeRange currentFreeTimeRange = null;

        FreeMinutes currentFreeMinutes = null;
        PromotionUsage currentPromotionUsage = null;

        var loop = true;

        while (cursor.isBefore(window.getCalculationEnd()) && loop) {
            LocalDateTime allocateEndTime = null;
            LocalDateTime nextCursorTime = null;
            if (currentFreeTimeRange == null) {
                if (!timeRangeIterator.hasNext()) {
                    allocateEndTime = window.getCalculationEnd();
                    nextCursorTime = window.getCalculationEnd();
                } else {
                    currentFreeTimeRange = timeRangeIterator.next();
                    allocateEndTime = currentFreeTimeRange.getBeginTime();
                    nextCursorTime = currentFreeTimeRange.getEndTime();
                }
            }

            if (cursor.isBefore(allocateEndTime)) {
                // 在这个时间范围内分配免费分钟数
                while (cursor.isBefore(allocateEndTime)) {
                    if (currentFreeMinutes == null) {
                        if (!freeMinutesIterator.hasNext()) {
                            loop = false;
                            break;
                        }
                        currentFreeMinutes = freeMinutesIterator.next();
                        currentPromotionUsage = new PromotionUsage().setPromotionId(currentFreeMinutes.getId())
                                .setType(BConstants.PromotionType.FREE_MINUTES)
                                .setGrantedMinutes(currentFreeMinutes.getMinutes())
                                .setUsedMinutes(0);
                        promotionUsages.add(currentPromotionUsage);
                    }
                    // 计算这个范围内的分钟数
                    int gapMinutes = (int) Duration.between(cursor, allocateEndTime).toMinutes();
                    // 此免费分钟数剩余
                    int availableMinutes = (int) (currentPromotionUsage.getGrantedMinutes()
                            - currentPromotionUsage.getUsedMinutes());
                    int remainFreeMinutes = availableMinutes - gapMinutes;
                    // 免费时长大于等于时间范围长度
                    if (remainFreeMinutes >= 0) {
                        // 生成新的免费时间段
                        generatedFreeRanges.add(new FreeTimeRange()
                                .setBeginTime(cursor).setEndTime(allocateEndTime)
                                .setId(currentFreeMinutes.getId())
                                .setPriority(currentFreeMinutes.getPriority())
                                .setPromotionType(BConstants.PromotionType.FREE_MINUTES));
                        // 更新已使用分钟数
                        currentPromotionUsage.setUsedMinutes(currentPromotionUsage.getUsedMinutes() + gapMinutes);
                        // 游标前进
                        cursor = nextCursorTime;
                        if (remainFreeMinutes == 0) {
                            currentFreeMinutes = null; // 下次计算用下一份免费分钟数
                            currentPromotionUsage = null; // 下次计算用下一份免费分钟数
                        }
                    } else {
                        // 免费分钟数小雨时间范围长度
                        var newRangeEndTime = cursor.plusMinutes(gapMinutes);
                        generatedFreeRanges.add(new FreeTimeRange()
                                .setBeginTime(cursor)
                                .setEndTime(newRangeEndTime)
                                .setId(currentFreeMinutes.getId())
                                .setPriority(currentFreeMinutes.getPriority())
                                .setPromotionType(BConstants.PromotionType.FREE_MINUTES));

                        // 更新已使用分钟数
                        currentPromotionUsage.setUsedMinutes(currentPromotionUsage.getGrantedMinutes());

                        currentFreeMinutes = null; // 下次计算用下一份免费分钟数
                        currentPromotionUsage = null; // 下次计算用下一份免费分钟数
                        cursor = newRangeEndTime;
                    }
                }

            } else {
                // 游标前进，下一轮循环用下一个免费时间段
                cursor = nextCursorTime;
                currentFreeTimeRange = null;
            }

        }
        // 计算未使用的优惠
        return new FreeMinuteAllocationResult().setGeneratedFreeRanges(generatedFreeRanges)
                .setPromotionUsages(promotionUsages);
    }




}
