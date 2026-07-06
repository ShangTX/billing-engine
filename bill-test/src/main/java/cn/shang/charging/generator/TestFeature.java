package cn.shang.charging.generator;

/**
 * 测试结果生成器的功能点开关。
 * <p>
 * 功能点只描述需要组合覆盖的计费能力，不表示预期金额是否正确。
 */
public enum TestFeature {
    /**
     * 固定计费单元模式。
     */
    UNIT_BASED,

    /**
     * 连续时间计费模式。
     */
    CONTINUOUS,

    /**
     * 延续计费：前一次结果生成 carryOver，后一次请求携带 previousCarryOver。
     */
    CONTINUE,

    /**
     * 多收费方案切换，覆盖 schemeChanges 和分段计算。
     */
    SCHEME_SWITCH,

    /**
     * 分段独立起算。
     */
    SEGMENT_LOCAL,

    /**
     * 全局起算后按分段裁剪。
     *
     * @deprecated TODO-20260706-003：GLOBAL_ORIGIN 模式已废弃，本 feature 保留作兼容入口，
     * 实际映射为 {@link #SEGMENT_LOCAL}。
     */
    @Deprecated
    GLOBAL_ORIGIN,

    /**
     * 对生成的计费结果附加多个 queryTime 查询摘要。
     */
    QUERY_TIME,

    /**
     * 带秒数的真实输入时间，用于观察时间取整。
     */
    TIME_ROUNDING,

    /**
     * 长时间计费，用于触发或观察简化计算。
     */
    SIMPLIFICATION,

    /**
     * 长同价时段窗口，用于稳定产出 compact 单元。
     * <p>
     * 选择纯白天或单时段的长窗口，使边界驱动循环产出的同质段足够长，
     * 便于人工校验 compact 单元的 JSON 结构和查询时点投影。
     * 与 {@link #DAY_NIGHT_CROSS_PERIOD_UNIT} 互斥（跨边界场景不产出 compact）。
     */
    COMPACT,

    /**
     * 规则免费分钟数。
     */
    FREE_MINUTES,

    /**
     * 多个优惠同时存在并聚合。
     */
    MULTI_PROMOTION,

    /**
     * 外部显式免费时间段。
     */
    EXTERNAL_FREE_RANGE,

    /**
     * 外部免费分钟数。
     */
    EXTERNAL_FREE_MINUTES,

    /**
     * 气泡型免费时段，用于观察免费时间对周期边界的延长。
     */
    BUBBLE_FREE_RANGE,

    /**
     * 起始免费优惠。
     */
    START_FREE,

    /**
     * 带查询窗口条件的起始免费，当前通过 valueSpec 表达即时查询值。
     */
    CONDITIONAL_START_FREE,

    /**
     * 日夜规则中一个计费单元跨白天和夜间边界。
     */
    DAY_NIGHT_CROSS_PERIOD_UNIT,

    /**
     * 日夜规则每日封顶。
     */
    DAY_NIGHT_DAILY_CAP,

    /**
     * 日夜混合单元生成可查询的单元内 valueSpec。
     */
    DAY_NIGHT_MIXED_VALUE_SPEC,

    /**
     * 相对时间规则的多时段配置。
     */
    RELATIVE_MULTI_PERIOD,

    /**
     * 相对时间规则的周期封顶。
     */
    RELATIVE_CYCLE_CAP,

    /**
     * 复合时间规则中的自然时段价格。
     */
    COMPOSITE_NATURAL_PERIOD,

    /**
     * 复合时间规则跨自然时段的处理模式。
     */
    COMPOSITE_CROSS_PERIOD_MODE,

    /**
     * 复合时间规则不足单元的收费模式。
     */
    COMPOSITE_INSUFFICIENT_UNIT,

    /**
     * 统一免费规则。
     */
    FLAT_FREE
}
