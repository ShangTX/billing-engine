package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.RuleConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 规则族语义接口（层 0）：描述规则族"是什么"，不含计算逻辑。
 * <p>
 * 由各规则族实现（DayNightSemantics / RelativeTimeSemantics / ...），封装周期模型、时段结构、
 * 价格、封顶等语义差异。通用 {@link ContinuousStrategy}（层 2）消费语义，实现 CONTINUOUS 模式
 * 的边界驱动切断 + 封顶 + 累计 + 简化，消除 4 份 applyCapAndAccumulate 重复。
 * <p>
 * TODO-20260706-002 阶段3：引入 RuleSemantics，正交解耦规则族语义与模式行为。
 */
public interface RuleSemantics<C extends RuleConfig> {

    // ==================== 周期 ====================

    /**
     * 周期长度（分钟），默认 1440（24h）。
     */
    default int cycleMinutes() {
        return 1440;
    }

    /**
     * 周期起点（DayNight 用自然日，可为 null；其余用计费起点或分段起点）。
     */
    LocalDateTime cycleOrigin(BillingContext context);

    /**
     * 初始周期边界（calcBegin 所在周期的结束边界）。
     * ContinuousStrategy 维护 currentCycleBoundary 状态，从初始值开始。
     */
    LocalDateTime initialCycleBoundary(LocalDateTime cycleOrigin, LocalDateTime calcBegin);

    /**
     * 判断 seg 是否越过当前周期边界（seg 跨越周期边界时返回 true，触发 cycleAccumulated 重置）。
     * 无状态查询，仅看 seg 与 currentCycleBoundary。
     */
    boolean isCycleBoundary(HomogeneousSegment seg, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin);

    /**
     * 计算下一个周期边界。
     * <ul>
     *   <li>固定周期（DayNight/RelativeTime/CompositeTime）：基于 currentCycleBoundary 或 cycleOrigin 推进</li>
     *   <li>滑动窗口（NaturalTime）：基于 segEndTime 推进</li>
     * </ul>
     */
    LocalDateTime nextCycleBoundary(LocalDateTime segEndTime, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin);

    // ==================== 单元与时段 ====================

    /**
     * 指定时间点的单元时长（分钟）。
     * DayNight/NaturalTime 全局统一；RelativeTime/CompositeTime 按 period。
     */
    int unitMinutes(LocalDateTime time, C config, LocalDateTime cycleOrigin);

    /**
     * 是否配置了时段独立封顶（periodCap）。仅 CompositeTime 返回 true。
     */
    default boolean hasPeriodCap(C config) {
        return false;
    }

    /**
     * 指定时间点所在时段的独立封顶金额（null 表示该时段无独立封顶）。
     * 仅 CompositeTime 提供非 null 值。
     */
    default BigDecimal periodCap(LocalDateTime time, C config, LocalDateTime cycleOrigin) {
        return null;
    }

    /**
     * 指定时间点所在时段的唯一标识（用于 periodStartIndex 跟踪时段切换）。
     * 同一时段返回相同 key，不同时段返回不同 key。
     */
    default String periodKey(LocalDateTime time, C config, LocalDateTime cycleOrigin) {
        return "default";
    }

    // ==================== 封顶 ====================

    /**
     * 周期封顶金额（maxChargeOneDay / maxChargeOneCycle）。
     */
    BigDecimal cycleCap(C config);

    /**
     * 周期封顶标记字符串（DayNight="DAILY_CAP"，其余="CYCLE_CAP"）。
     */
    default String cycleCapLabel() {
        return "CYCLE_CAP";
    }

    // ==================== 不足单元 ====================

    BConstants.IncompleteUnitChargeMode incompleteMode(C config);

    Integer thresholdMinutes(C config);

    BigDecimal thresholdRatio(C config);
}
