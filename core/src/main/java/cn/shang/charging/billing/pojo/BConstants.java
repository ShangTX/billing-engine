package cn.shang.charging.billing.pojo;

/**
 * 计费常量
 */
public class BConstants {

    /**
     * 继续模式（是否从上次结果继续计算）
     */

    /**
     * 计费模式（计费单位如何划分）
     */
    public enum BillingMode {
        CONTINUOUS, // 连续时间计费模式
        UNIT_BASED  // 计费单位模式
    }

    /**
     * 时长计费模式（Duration-Based Billing Mode）
     * <p>
     * 将时间轴视为连续的分钟流，按分钟数计费，而非按单元计费。
     * 仅在结果输出阶段区分，边界驱动循环完全复用。
     */
    public enum DurationMode {
        /**
         * 不使用时长模式
         */
        NONE,

        /**
         * 周期内时长模式
         * 每个周期内按时长计费，周期封顶
         * 时段封顶按每个周期独立计算
         */
        PERIOD,

        /**
         * 全局时长模式
         * 不关心周期边界，全局按时长计费
         * 时段封顶 = period.maxCharge × 周期数
         * 周期封顶 = maxChargeOneCycle × 周期数
         */
        GLOBAL
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
     * 不完整计费单元收费模式
     * <p>
     * 当计费单元时长不足 unitMinutes 时，如何计费。
     */
    public enum IncompleteUnitChargeMode {
        /**
         * 完整收费（默认）
         * 不完整单元收取完整单元价格
         */
        FULL_CHARGE,

        /**
         * 按时长比例收费
         * chargedAmount = unitPrice * (durationMinutes / unitMinutes)
         */
        PROPORTIONAL,

        /**
         * 不收费
         * 不完整单元免费
         */
        FREE,

        /**
         * 分钟阈值模式
         * 超过阈值分钟数后全额收费，否则免费
         * 需配合 thresholdMinutes 配置
         */
        THRESHOLD_MINUTES,

        /**
         * 比例阈值模式
         * 超过阈值比例后全额收费，否则按比例收费
         * 需配合 thresholdRatio 配置
         */
        THRESHOLD_RATIO
    }

    /**
     * 计费规则类型
     */
    public static class ChargeRuleType {
        public static String DAY_NIGHT = "dayNight"; // 日夜分时段计费
        public static String NATURAL_TIME = "naturalTime"; // 多自然时段计费
        public static String RELATIVE_TIME = "relativeTime"; // 按相对时间段计费
        public static String COMPOSITE_TIME = "compositeTime"; // 混合时间计费
        public static String FLAT_FREE = "flatFree"; // 统一免费计费

        /** @deprecated 使用 compositeTime 替代 */
        @Deprecated
        public static String NR_TIME_MIX = "nrTimeMix"; // 已被 compositeTime 覆盖

        /** 预留：按次数计费（非时间计费场景，需另行设计） */
        public static String TIMES = "times"; // 按次数（预留）
    }

    public static class PromotionRuleType {
        public static String FREE_MINUTES = "freeMinutes"; // 免费分钟数
        public static String START_FREE = "startFree"; // 前N分钟免费
    }

}
