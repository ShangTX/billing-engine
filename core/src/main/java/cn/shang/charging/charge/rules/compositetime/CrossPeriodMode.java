package cn.shang.charging.charge.rules.compositetime;

/**
 * 跨自然时段处理模式
 */
public enum CrossPeriodMode {

    /** 按时间比例判断用哪个价格（类似 DayNightRule 的 blockWeight） */
    BLOCK_WEIGHT,

    /** 取较高价格 */
    HIGHER_PRICE,

    /** 取较低价格 */
    LOWER_PRICE,

    /** 按比例拆分计算 */
    PROPORTIONAL,

    /** 取开始时间所在时段的价格 */
    BEGIN_TIME_PRICE,

    /** 取结束时间所在时段的价格 */
    END_TIME_PRICE,

    /** 取开始时间价格，并用自然时段边界截断单元 */
    BEGIN_TIME_TRUNCATE
}