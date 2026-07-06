package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.charging.promotion.pojo.FreeMinutes;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.util.List;

/**
 * 规则族共享无状态工具（层 2 旁路）：不通过继承传递的策略侧公共能力。
 * <p>
 * 当前承载 FREE_MINUTES 时段化（{@link #materializeFreeMinutes}），供 CONTINUOUS 策略族
 * （DayNight/RelativeTime/NaturalTime/CompositeTime）与时长策略
 * （{@link DurationGlobalStrategy}/{@link DurationPeriodStrategy}）共享。
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
     * CONTINUOUS 策略（DayNight/RelativeTime/NaturalTime/CompositeTime）与时长策略在
     * {@code calculate} 入口调用本方法获得 finalFreeRanges，替换旧路径直接读
     * {@code aggregate.getFreeTimeRanges()} 的行为（后者现在只含 FREE_RANGE）。
     * FREE_MINUTES 时段化已从 PromotionEngine 下放到策略侧（TODO-20260702-004）。
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
        return FREE_MINUTE_ALLOCATOR.allocateAndMerge(freeMinutesList, freeRangeOnly, window);
    }
}
