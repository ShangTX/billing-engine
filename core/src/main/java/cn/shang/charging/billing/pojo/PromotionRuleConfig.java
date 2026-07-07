package cn.shang.charging.billing.pojo;

import lombok.Data;

/**
 * 优惠规则配置接口。
 * <p>
 * 描述一条优惠规则的参数，由调用方实现。
 * {@link BillingConfigResolver#resolvePromotionRules} 返回具体实现列表。
 * 引擎通过 {@link #getType()} 分派到对应 {@link cn.shang.charging.promotion.rules.PromotionRule}。
 */
public interface PromotionRuleConfig {
    /** 规则配置ID（业务侧自定义） */
    String getId();
    /** 规则类型（对应 BConstants.PromotionRuleType 中的常量，如 "freeMinutes"、"startFree"） */
    String getType();
    /** 优先级（数字越小优先级越高，用于多优惠叠加时的顺序控制） */
    Integer getPriority();
}
