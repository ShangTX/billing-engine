package cn.shang.charging;

import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.SimplifiedUnitMeta;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        SimplifiedUnitMeta meta = SimplifiedUnitMeta.from(unit);

        assertNotNull(meta);
        assertTrue(meta.simplified());
        assertEquals(2, meta.cycleIndex());
        assertEquals(5, meta.simplifiedCycleCount());
        assertEquals(new BigDecimal("88.80"), meta.simplifiedCycleAmount());
    }

    @Test
    void shouldReturnNullWhenRuleDataIsNotSimplified() {
        BillingUnit unit = BillingUnit.builder()
                .ruleData(Map.of("vendorSpecific", "x"))
                .build();

        assertNull(SimplifiedUnitMeta.from(unit));
    }
}
