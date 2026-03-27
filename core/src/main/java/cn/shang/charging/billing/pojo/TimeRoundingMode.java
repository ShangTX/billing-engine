package cn.shang.charging.billing.pojo;

/**
 * 时间取整模式
 * 用于处理计费开始/结束时间中的秒数
 */
public enum TimeRoundingMode {

    /**
     * 保留秒数，不做处理
     */
    KEEP_SECONDS,

    /**
     * 开始和结束时间都直接去掉秒数（秒数置0）
     */
    TRUNCATE_BOTH,

    /**
     * 开始时间向上取整（增加一分钟，秒数置0），结束时间去掉秒数
     * 适用于"进场多算，出场不算"的场景
     */
    CEIL_BEGIN_TRUNCATE_END,

    /**
     * 开始时间去掉秒数，结束时间向上取整（增加一分钟，秒数置0）
     * 适用于"进场不算，出场多算"的场景
     */
    TRUNCATE_BEGIN_CEIL_END

}