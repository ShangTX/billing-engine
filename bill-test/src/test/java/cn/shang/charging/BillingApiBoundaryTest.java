package cn.shang.charging;

import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.wrapper.BillingResultViewer;
import cn.shang.charging.wrapper.QuerySummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BillingApiBoundaryTest {

    @Test
    void resultViewer_shouldIgnoreRuleSpecificRuleData() {
        LocalDateTime begin = LocalDateTime.of(2026, 5, 14, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 5, 14, 9, 0);
        LocalDateTime queryTime = LocalDateTime.of(2026, 5, 14, 8, 30);

        BillingUnit unit = BillingUnit.builder()
                .beginTime(begin)
                .endTime(end)
                .chargedAmount(new BigDecimal("10.00"))
                .accumulatedAmount(new BigDecimal("10.00"))
                .valueSpec(new FixedValueSpec(new BigDecimal("10.00")))
                .ruleData(Map.of("vendorSpecific", "opaque"))
                .build();

        BillingResult result = BillingResult.builder()
                .units(List.of(unit))
                .calculationEndTime(end)
                .build();

        BillingResultViewer viewer = new BillingResultViewer();
        QuerySummary summary = viewer.createQuerySummary(result, queryTime);

        assertNotNull(summary);
        assertEquals(new BigDecimal("10.00"), summary.getAmount());
        assertEquals(begin, summary.getEffectiveFrom());
    }
}
