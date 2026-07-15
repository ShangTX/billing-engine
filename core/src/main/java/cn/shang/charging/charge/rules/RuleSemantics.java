package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.RuleConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则族语义接口（层 0）：描述规则族"是什么"，不含计算逻辑。
 * <p>
 * 由各规则族实现（DayNightSemantics / RelativeTimeSemantics / ...），封装周期模型、时段结构、
 * 价格、封顶等语义差异。通用 {@link ContinuousStrategy}（层 2）消费语义，实现 CONTINUOUS 模式
 * 的边界驱动切断 + 封顶 + 累计 + 简化，消除 4 份 applyCapAndAccumulate 重复。
 * 通用 {@link DurationPeriodStrategy} / {@link DurationGlobalStrategy}（层 2）消费语义，
 * 实现时长模式（PERIOD/GLOBAL）的边界驱动切断 + 时段封顶 + 周期封顶，消除 4 份时长策略重复。
 * <p>
 * TODO-20260706-002 阶段3：引入 RuleSemantics，正交解耦规则族语义与模式行为。
 * TODO-20260706-002 阶段4：加 priceAt / periodBoundaryProvider / periodLabel，时长模式通用化。
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
     *
     * @param context 计费上下文（提供计算窗口、分段起点等定位信息）
     */
    LocalDateTime cycleOrigin(BillingContext context);

    /**
     * 初始周期边界（calcBegin 所在周期的结束边界）。
     * ContinuousStrategy 维护 currentCycleBoundary 状态，从初始值开始。
     *
     * @param cycleOrigin 周期起点
     * @param calcBegin   计算窗口起点
     */
    LocalDateTime initialCycleBoundary(LocalDateTime cycleOrigin, LocalDateTime calcBegin);

    /**
     * 判断 seg 是否越过当前周期边界（seg 跨越周期边界时返回 true，触发 cycleAccumulated 重置）。
     * 无状态查询，仅看 seg 与 currentCycleBoundary。
     *
     * @param seg                  待判定的同质段
     * @param currentCycleBoundary 当前周期边界（seg 越过此边界则触发周期切换）
     * @param cycleOrigin          周期起点（NaturalTime 滑动窗口不依赖此参数）
     */
    boolean isCycleBoundary(HomogeneousSegment seg, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin);

    /**
     * 计算下一个周期边界。
     * <ul>
     *   <li>固定周期（DayNight/RelativeTime/CompositeTime）：基于 currentCycleBoundary 或 cycleOrigin 推进</li>
     *   <li>滑动窗口（NaturalTime）：基于 segEndTime 推进</li>
     * </ul>
     *
     * @param segEndTime           当前段结束时间（滑动窗口据此推进一个周期）
     * @param currentCycleBoundary 当前周期边界（固定周期据此推进）
     * @param cycleOrigin          周期起点
     */
    LocalDateTime nextCycleBoundary(LocalDateTime segEndTime, LocalDateTime currentCycleBoundary, LocalDateTime cycleOrigin);

    // ==================== 单元与时段 ====================

    /**
     * 指定时间点的单元时长（分钟）。
     * DayNight/NaturalTime 全局统一；RelativeTime/CompositeTime 按 period。
     *
     * @param time        时间点
     * @param config      规则配置
     * @param cycleOrigin 周期起点（RelativeTime/CompositeTime 按周期内偏移定位 period）
     */
    int unitMinutes(LocalDateTime time, C config, LocalDateTime cycleOrigin);

    /**
     * 是否配置了时段独立封顶（periodCap）。仅 CompositeTime 返回 true。
     *
     * @param config 规则配置
     */
    default boolean hasPeriodCap(C config) {
        return false;
    }

    /**
     * 指定时间点所在时段的独立封顶金额（null 表示该时段无独立封顶）。
     * 仅 CompositeTime 提供非 null 值。
     *
     * @param time        时间点
     * @param config      规则配置
     * @param cycleOrigin 周期起点（按周期内偏移定位 period）
     */
    default BigDecimal periodCap(LocalDateTime time, C config, LocalDateTime cycleOrigin) {
        return null;
    }

    /**
     * 指定时间点所在时段的唯一标识（用于 periodStartIndex 跟踪时段切换）。
     * 同一时段返回相同 key，不同时段返回不同 key。
     *
     * @param time        时间点
     * @param config      规则配置
     * @param cycleOrigin 周期起点（按周期内偏移定位 period）
     */
    default String periodKey(LocalDateTime time, C config, LocalDateTime cycleOrigin) {
        return "default";
    }

    /**
     * 时长模式下该时间点所在时段的人类可读标签（如 "day"/"night"/"period-1"）。
     * 默认与 {@link #periodKey} 一致；DayNight 等需人类可读标签的规则族覆盖。
     *
     * @param time        时间点
     * @param config      规则配置
     * @param cycleOrigin 周期起点（按周期内偏移定位 period）
     */
    default String periodLabel(LocalDateTime time, C config, LocalDateTime cycleOrigin) {
        return periodKey(time, config, cycleOrigin);
    }

    // ==================== 价格（时长模式用） ====================

    /**
     * 时长模式下指定区间 [begin, end) 的单元单价。
     * <p>
     * 时长模式不按单元对齐切断（同质段由边界 provider 切断），故单价基于区间端点解析：
     * non-dayNight 时间规则族应在时段边界统一切断，同质段直接返回段起点所在时段单价；
     * dayNight 可保留自己的跨日夜归属逻辑。
     *
     * @param begin       区间起点
     * @param end         区间终点
     * @param config      规则配置
     * @param cycleOrigin 周期起点（RelativeTime/CompositeTime 按 cycleOrigin 算周期内偏移定位 period；
     *                    DayNight/NaturalTime 用自然日内分钟，不依赖此参数）
     */
    BigDecimal priceAt(LocalDateTime begin, LocalDateTime end, C config, LocalDateTime cycleOrigin);

    /**
     * 时长模式下时段结构边界 provider（PERIOD/GLOBAL 共用）。
     * <p>
     * 返回的边界用于边界驱动循环切断同质段（如日夜边界、relativeTime period 结束、naturalTime 时段边界）。
     * provider 自行保证返回的边界严格大于 current、不大于 calcEnd。
     *
     * @param config      规则配置
     * @param cycleOrigin 周期起点（用于按周期内偏移定位 period 边界）
     */
    BoundaryProvider periodBoundaryProvider(C config, LocalDateTime cycleOrigin);

    // ==================== 封顶 ====================

    /**
     * 周期封顶金额（maxChargeOneDay / maxChargeOneCycle）。
     *
     * @param config 规则配置
     */
    BigDecimal cycleCap(C config);

    /**
     * 周期封顶标记字符串（DayNight="DAILY_CAP"，其余="CYCLE_CAP"）。
     */
    default String cycleCapLabel() {
        return "CYCLE_CAP";
    }

    // ==================== 不足单元 ====================

    /**
     * 不足单元计费模式（不满一个 unitMinutes 的余数如何收费）。
     *
     * @param config 规则配置
     */
    default BConstants.IncompleteUnitChargeMode incompleteMode(C config) {
        return config.getIncompleteUnitChargeMode();
    }

    /**
     * THRESHOLD_MINUTES 阈值（分钟）：余数达到此值收全额，否则免费。null 视为 0。
     *
     * @param config 规则配置
     */
    default Integer thresholdMinutes(C config) {
        return config.getThresholdMinutes();
    }

    /**
     * THRESHOLD_RATIO 阈值（余数/单元时长 &ge; 此比例时按比例收费，否则免费）。null 视为 0。
     *
     * @param config 规则配置
     */
    default BigDecimal thresholdRatio(C config) {
        return config.getThresholdRatio();
    }
}
