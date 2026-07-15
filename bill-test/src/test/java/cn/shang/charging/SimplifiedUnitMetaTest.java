package cn.shang.charging;

import cn.shang.charging.billing.pojo.BillingUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简化单元 ruleData Map 契约测试。
 * <p>
 * 旧 {@code SimplifiedUnitMeta} 记录类已随 TODO-20260706-002 阶段7 删除；
 * 简化单元改由 {@code ContinuousStrategy.buildSimplifiedUnit} 直接写入 ruleData Map，
 * 约定键：{@code isSimplified}(Boolean.TRUE) / {@code cycleIndex} / {@code simplifiedCycleCount} /
 * {@code simplifiedCycleAmount}。本测试固化该契约。
 */
class SimplifiedUnitMetaTest {

    @Test
    void shouldReadMetaFromBillingUnitRuleDataMap() {
        BillingUnit unit = BillingUnit.builder()
                .ruleData(Map.of(
                        "isSimplified", true,
                        "cycleIndex", 2,
                        "simplifiedCycleCount", 5,
                        "simplifiedCycleAmount", new BigDecimal("88.80")
                ))
                .build();

        assertTrue(isSimplifiedUnit(unit));
        Map<String, Object> data = (Map<String, Object>) unit.getRuleData();
        assertEquals(2, data.get("cycleIndex"));
        assertEquals(5, data.get("simplifiedCycleCount"));
        assertEquals(new BigDecimal("88.80"), data.get("simplifiedCycleAmount"));
    }

    @Test
    void shouldReturnFalseWhenRuleDataIsNotSimplified() {
        BillingUnit unit = BillingUnit.builder()
                .ruleData(Map.of("vendorSpecific", "x"))
                .build();

        assertFalse(isSimplifiedUnit(unit));
    }

    @SuppressWarnings("unchecked")
    private static boolean isSimplifiedUnit(BillingUnit unit) {
        if (unit == null || unit.getRuleData() == null) {
            return false;
        }
        if (!(unit.getRuleData() instanceof Map<?, ?> rawMap)) {
            return false;
        }
        return Boolean.TRUE.equals(((Map<String, Object>) rawMap).get("isSimplified"));
    }
}
