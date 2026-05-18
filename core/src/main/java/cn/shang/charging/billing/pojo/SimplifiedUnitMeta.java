package cn.shang.charging.billing.pojo;

import cn.shang.charging.util.TypeConversionUtil;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 简化单元公共元数据。
 * <p>
 * 该模型用于表达长期简化计算后写入单元的公共信息，
 * 供 core 与 billing-api 通过稳定契约交互，而不是直接解析规则私有 Map 结构。
 */
public record SimplifiedUnitMeta(
        int cycleIndex,
        int simplifiedCycleCount,
        BigDecimal simplifiedCycleAmount,
        boolean simplified
) {

    public static SimplifiedUnitMeta from(BillingUnit unit) {
        if (unit == null) {
            return null;
        }
        return from(unit.getRuleData());
    }

    @SuppressWarnings("unchecked")
    public static SimplifiedUnitMeta from(Object ruleData) {
        if (ruleData == null) {
            return null;
        }
        if (ruleData instanceof SimplifiedUnitMeta meta) {
            return meta;
        }
        if (!(ruleData instanceof Map<?, ?> rawMap)) {
            return null;
        }

        Map<String, Object> map = (Map<String, Object>) rawMap;
        if (!Boolean.TRUE.equals(map.get("isSimplified"))) {
            return null;
        }

        return new SimplifiedUnitMeta(
                TypeConversionUtil.toInteger(map.getOrDefault("cycleIndex", 0)),
                TypeConversionUtil.toInteger(map.getOrDefault("simplifiedCycleCount", 0)),
                TypeConversionUtil.toBigDecimal(map.getOrDefault("simplifiedCycleAmount", BigDecimal.ZERO)),
                true
        );
    }
}
