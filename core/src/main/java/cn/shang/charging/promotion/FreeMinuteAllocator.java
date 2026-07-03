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
import java.util.stream.Stream;

/**
 * FREE_MINUTES 时段化工具（TODO-20260702-004：从 PromotionEngine 下放到策略侧）。
 * <p>
 * 把 FREE_MINUTES（分钟数标量）分配到计算窗口内非 FREE_RANGE 的空隙，转为具体时间段。
 * 策略侧（CONTINUOUS/UNIT_BASED/PERIOD）调用 {@link #allocateAndMerge} 获得最终免费段与 usage；
 * GLOBAL 策略不时段化，按分钟扣减 chargedMinutes。
 */
public class FreeMinuteAllocator {

    record TimeRange(LocalDateTime beginTime, LocalDateTime endTime) {

    }

    /**
     * 分配 FREE_MINUTES 并与 FREE_RANGE 合并，产出最终免费段 + usage。
     * <p>
     * 时段化位置只依赖窗口起点 + FREE_RANGE + 窗口长度，与计费规则无关（这是 GLOBAL_ORIGIN 减法
     * 与外部优惠跨段一致性的技术基础）。
     *
     * @param freeMinutesPromotions 未时段化的 FREE_MINUTES 列表
     * @param explicitFreeRanges    已合并的 FREE_RANGE 时段（不含 FREE_MINUTES）
     * @param window                计算窗口
     */
    public FreeMinuteAllocationResult allocateAndMerge(List<FreeMinutes> freeMinutesPromotions,
                                                        List<FreeTimeRange> explicitFreeRanges,
                                                        CalculationWindow window) {
        AllocateOutput output = allocate(freeMinutesPromotions,
                explicitFreeRanges != null ? explicitFreeRanges : List.of(),
                window);

        // 最终合并 FREE_RANGE + 时段化 FREE_MINUTES
        List<FreeTimeRange> merged = new FreeTimeRangeMerger().merge(
                Stream.concat(
                        output.explicitFreeRanges.stream(),
                        output.generatedFreeRanges.stream()
                ).toList(),
                window.getCalculationBegin(),
                window.getCalculationEnd()
        ).getMergedRanges();

        return new FreeMinuteAllocationResult()
                .setFinalFreeRanges(merged)
                .setPromotionUsages(output.promotionUsages);
    }

    /**
     * 分配免费分钟数（核心算法，产出未与 FREE_RANGE 合并的生成段）。
     *
     * @param freeMinutesPromotions 免费分钟数
     * @param explicitFreeRanges    合并后的 FREE_RANGE 时段
     * @param window                计算窗口
     */
    private AllocateOutput allocate(List<FreeMinutes> freeMinutesPromotions,
                                    List<FreeTimeRange> explicitFreeRanges,
                                    CalculationWindow window) {
        // 首先对免费分钟数进行排序
        var sortedFreeMinutesPromotions = freeMinutesPromotions == null
                ? List.<FreeMinutes>of()
                : freeMinutesPromotions.stream()
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
                        currentFreeTimeRange = null; // 下一个时间段
                    } else {
                        // 免费分钟数小于时间范围长度
                        var newRangeEndTime = cursor.plusMinutes(availableMinutes);
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
        return new AllocateOutput(explicitFreeRanges, generatedFreeRanges, promotionUsages);
    }

    /** 内部分配输出：FREE_RANGE（透传）+ 生成的 FREE_MINUTES 段 + usage。 */
    @Data
    private static class AllocateOutput {
        final List<FreeTimeRange> explicitFreeRanges;
        final List<FreeTimeRange> generatedFreeRanges;
        final List<PromotionUsage> promotionUsages;

        AllocateOutput(List<FreeTimeRange> explicitFreeRanges,
                       List<FreeTimeRange> generatedFreeRanges,
                       List<PromotionUsage> promotionUsages) {
            this.explicitFreeRanges = explicitFreeRanges;
            this.generatedFreeRanges = generatedFreeRanges;
            this.promotionUsages = promotionUsages;
        }
    }




}
