package cn.shang.charging.billing.pojo;

import java.math.BigDecimal;

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

    /**
     * 不完整计费单元收费模式。
     * 默认完整收费，具体规则配置可按需覆盖为字段值。
     */
    default BConstants.IncompleteUnitChargeMode getIncompleteUnitChargeMode() {
        IncompleteUnitChargeSpec spec = getIncompleteUnitChargeSpec();
        if (spec != null && spec.getMode() != null) {
            return spec.getMode();
        }
        return BConstants.IncompleteUnitChargeMode.FULL_CHARGE;
    }

    /**
     * THRESHOLD_MINUTES 模式阈值（分钟）。null 表示未配置。
     */
    default Integer getThresholdMinutes() {
        IncompleteUnitChargeSpec spec = getIncompleteUnitChargeSpec();
        if (spec != null && spec.getThresholdMinutes() != null) {
            return spec.getThresholdMinutes();
        }
        return null;
    }

    /**
     * THRESHOLD_RATIO 模式阈值比例。null 表示未配置。
     */
    default BigDecimal getThresholdRatio() {
        IncompleteUnitChargeSpec spec = getIncompleteUnitChargeSpec();
        if (spec != null && spec.getThresholdRatio() != null) {
            return spec.getThresholdRatio();
        }
        return null;
    }

    /**
     * 不足单元计费配置对象。推荐新配置使用本对象，旧散字段可继续兼容。
     */
    default IncompleteUnitChargeSpec getIncompleteUnitChargeSpec() {
        return null;
    }

}
