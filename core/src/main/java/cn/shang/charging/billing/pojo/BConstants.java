package cn.shang.charging.billing.pojo;

/**
 * 计费常量
 */
public class BConstants {

    /**
     * 继续模式（是否从上次结果继续计算）
     */
    public enum ContinueMode {
        FROM_SCRATCH, // 从开始时间计算
        CONTINUE      // 从上一次的结果继续计算
    }

    /**
     * 计费模式（计费单位如何划分）
     */
    public enum BillingMode {
        CONTINUOUS, // 连续时间计费模式
        UNIT_BASED  // 计费单位模式
    }

    /**
     * 优惠模式
     */
    public enum PromotionType {
        AMOUNT, // 金额
        DISCOUNT, // 折扣
        FREE_RANGE, // 免费时间段
        FREE_MINUTES, // 免费分钟数
    }

    /**
     * 优惠来源
     */
    public enum PromotionSource {
        RULE, // 规则
        COUPON // 优惠券
    }

    /**
     * 分段计算方式
     */
    public enum SegmentCalculationMode {
        SINGLE, // 仅单个分段
        SEGMENT_LOCAL,     // 分段独立起算
        GLOBAL_ORIGIN      // 全局起算 + 分段截取
    }

    /**
     * 计费规则类型
     */
    public static class ChargeRuleType {
        public static String DAY_NIGHT = "dayNight"; // 日夜分时段计费
        public static String TIMES = "times"; // 按次数
        public static String NATURAL_TIME = "naturalTime"; // 按自然时间段计费
        public static String RELATIVE_TIME = "relativeTime"; // 按相对时间段计费
        public static String NR_TIME_MIX = "nrTimeMix"; // 按自然时间、相对时间混合时间段计费
    }

    public static class PromotionRuleType {
        public static String FREE_MINUTES = "freeMinutes"; // 免费分钟数
        public static String FREE_TIME_RANGE = "freeTimeRange"; // 免费时间段
    }

}
