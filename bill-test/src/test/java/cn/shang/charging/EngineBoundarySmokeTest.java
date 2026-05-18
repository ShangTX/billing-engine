package cn.shang.charging;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.value.FixedValueSpec;
import cn.shang.charging.billing.value.StepValueSpec;
import cn.shang.charging.wrapper.CalculationWithQueryResult;
import cn.shang.charging.wrapper.BillingTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineBoundarySmokeTest {

    @Test
    void currentSimplifiedMarker_isStoredInRuleDataMap() {
        BillingUnit unit = BillingUnit.builder()
                .ruleData(Map.of(
                        "isSimplified", true,
                        "cycleIndex", 1,
                        "simplifiedCycleCount", 3,
                        "simplifiedCycleAmount", new BigDecimal("120.00")
                ))
                .build();

        assertTrue(unit.getRuleData() instanceof Map<?, ?>);
        Map<?, ?> ruleData = (Map<?, ?>) unit.getRuleData();
        assertTrue(Boolean.TRUE.equals(ruleData.get("isSimplified")));
        assertEquals(1, ruleData.get("cycleIndex"));
        assertEquals(3, ruleData.get("simplifiedCycleCount"));
        assertEquals(new BigDecimal("120.00"), ruleData.get("simplifiedCycleAmount"));
    }

    @Test
    void calculateWithQuery_currentlyRerunsWhenHitUnitCarriesSimplifiedMarker() {
        LocalDateTime begin = LocalDateTime.of(2026, 5, 14, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 5, 14, 9, 0);
        LocalDateTime switchTime = LocalDateTime.of(2026, 5, 14, 8, 30);
        LocalDateTime queryTime = LocalDateTime.of(2026, 5, 14, 8, 29);

        BillingUnit simplifiedUnit = BillingUnit.builder()
                .beginTime(begin)
                .endTime(end)
                .chargedAmount(new BigDecimal("20.00"))
                .accumulatedAmount(new BigDecimal("20.00"))
                .valueSpec(new FixedValueSpec(new BigDecimal("20.00")))
                .ruleData(Map.of("isSimplified", true))
                .build();

        BillingUnit detailedUnit = BillingUnit.builder()
                .beginTime(begin)
                .endTime(end)
                .chargedAmount(new BigDecimal("20.00"))
                .accumulatedAmount(new BigDecimal("20.00"))
                .valueSpec(new StepValueSpec(switchTime, new BigDecimal("8.00"), new BigDecimal("20.00")))
                .ruleData(Map.of())
                .build();

        BillingResult simplifiedResult = BillingResult.builder()
                .units(List.of(simplifiedUnit))
                .calculationEndTime(end)
                .build();
        BillingResult detailedResult = BillingResult.builder()
                .units(List.of(detailedUnit))
                .calculationEndTime(end)
                .build();

        AtomicInteger invocationCount = new AtomicInteger();
        BillingTemplate template = new BillingTemplate(
                new CountingBillingService(invocationCount, simplifiedResult, detailedResult),
                (BillingConfigResolver) null
        );

        BillingRequest request = new BillingRequest();
        request.setBeginTime(begin);
        request.setEndTime(end);

        CalculationWithQueryResult result = template.calculateWithQuery(request, queryTime);

        assertEquals(2, invocationCount.get());
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>) result.getCalculationResult().getUnits().get(0).getRuleData()).get("isSimplified")));
        assertEquals(new BigDecimal("8.00"), result.getQueryResult().getAmount());
    }

    private static class CountingBillingService extends BillingService {
        private final AtomicInteger invocationCount;
        private final BillingResult simplifiedResult;
        private final BillingResult detailedResult;

        CountingBillingService(AtomicInteger invocationCount,
                               BillingResult simplifiedResult,
                               BillingResult detailedResult) {
            super(null, null, null, null, null);
            this.invocationCount = invocationCount;
            this.simplifiedResult = simplifiedResult;
            this.detailedResult = detailedResult;
        }

        @Override
        public BillingResult calculate(BillingRequest request) {
            invocationCount.incrementAndGet();
            return Boolean.TRUE.equals(request.getDisableSimplification()) ? detailedResult : simplifiedResult;
        }
    }
}
