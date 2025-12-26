package cn.shang.billing.pojo;

/**
 * 计费常量
 */
public class BConstants {

    /**
     * 计费模式
     */
    public enum BillingMode {
        STATELESS, // 无状态，完全重算
        CACHE, // 缓存
        PERSIST // 持久化
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
    enum SegmentCalculationMode {
        SEGMENT_LOCAL,     // 分段独立起算
        GLOBAL_ORIGIN      // 全局起算 + 分段截取
    }

}
