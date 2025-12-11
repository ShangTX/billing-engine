package cn.shang.charge.pojo;

import lombok.Data;

/**
 * 折扣
 */
@Data
public class Discount {
    Long id;
    Type type; // 优惠类型
    Source source; // 优惠来源
    Integer priority; // 优先级


    public enum Type {
        FREE_MINUTES, // 免费分钟数
        FREE_PERIOD, // 免费时间段
        FIXED_AMOUNT // 固定金额
    }

    public enum Source {
        BILLING_RULE, // 计费规则
        COUPON, // 优惠券
        PASS // 通行证
    }
}
