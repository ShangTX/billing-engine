package cn.shang.charging.billing.pojo;

/**
 * 计费规则配置接口。
 * <p>
 * 描述一条计费规则的参数（不直接参与计算），由调用方实现。
 * {@link BillingConfigResolver#resolveChargingRule} 返回具体实现。
 * 引擎通过 {@link #getType()} 分派到对应 {@link cn.shang.charging.charge.rules.BillingRule}。
 */
public interface RuleConfig {

    /** 规则配置ID（业务侧自定义，用于追踪和日志） */
    String getId();
    /** 规则类型（对应 BConstants.ChargeRuleType 中的常量，如 "dayNight"、"naturalTime"） */
    String getType();

    /**
     * 是否支持简化计算
     * null 表示默认支持
     */
    default Boolean getSimplifiedSupported() {
        return null;
    }

}
