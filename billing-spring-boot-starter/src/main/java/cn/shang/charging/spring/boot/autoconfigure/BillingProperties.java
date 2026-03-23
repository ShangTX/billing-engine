package cn.shang.charging.spring.boot.autoconfigure;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计费配置属性
 * 仅包含 scheme 元信息，具体规则参数由调用方通过 BillingConfigResolver 实现
 */
@Data
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    /**
     * schemeId → scheme 元信息
     */
    private Map<String, SchemeMeta> schemes = new LinkedHashMap<>();

    /**
     * Scheme 元信息
     */
    @Data
    public static class SchemeMeta {
        /**
         * 规则类型: DAY_NIGHT, RELATIVE_TIME, COMPOSITE_TIME
         */
        private String ruleType;

        /**
         * 计费模式: CONTINUOUS, UNIT_BASED
         */
        private BConstants.BillingMode billingMode;

        /**
         * 简化计算阈值（连续无优惠周期数超过此值时启用简化，0 表示禁用）
         */
        private int simplifiedThreshold = 0;
    }
}