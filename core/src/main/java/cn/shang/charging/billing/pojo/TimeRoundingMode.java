package cn.shang.charging.billing.pojo;

/**
 * 时间取整模式
 * 用于处理计费开始/结束时间中的秒数
 * <p>
 * BillingTemplate 当前有效支持 TRUNCATE_BOTH 与 CEIL_BEGIN_TRUNCATE_END；
 * 其他枚举值作为兼容旧值保留，按 TRUNCATE_BOTH 处理。
 */
public enum TimeRoundingMode {

    /**
     * 兼容旧值；BillingTemplate 当前按 TRUNCATE_BOTH 处理。
     */
    KEEP_SECONDS,

    /**
     * 开始和结束时间都直接去掉秒数（秒数置0）
     */
    TRUNCATE_BOTH,

    /**
     * 开始时间向上取整（增加一分钟，秒数置0），结束时间去掉秒数
     * 适用于"进场多算，出场不算"的场景；外部 FREE_RANGE 优惠时间段按相反方向放宽。
     */
    CEIL_BEGIN_TRUNCATE_END,

    /**
     * 兼容旧值；BillingTemplate 当前按 TRUNCATE_BOTH 处理。
     */
    TRUNCATE_BEGIN_CEIL_END

}
