package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BillingTestSupport {

    private BillingTestSupport() {
    }

    static BillingService service(BillingConfigResolver resolver) {
        PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
        promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        promotionRuleRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), promotionRuleRegistry),
                new BillingCalculator(new BillingRuleRegistry()),
                new ResultAssembler()
        );
    }

    static BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest request = new BillingRequest();
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeId("scheme-1");
        request.setSchemeChanges(List.of());
        request.setExternalPromotions(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        return request;
    }

    static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected amount " + expected + ", actual " + actual);
    }

    static void assertUnitsCover(BillingResult result, LocalDateTime begin, LocalDateTime end) {
        assertNotNull(result.getUnits());
        assertFalse(result.getUnits().isEmpty());
        List<BillingUnit> units = result.getUnits();
        assertEquals(begin, units.get(0).getBeginTime());
        assertEquals(end, units.get(units.size() - 1).getEndTime());
        for (int i = 0; i < units.size(); i++) {
            BillingUnit unit = units.get(i);
            assertTrue(unit.getDurationMinutes() > 0, "unit " + i + " duration must be positive");
            assertEquals(unit.getDurationMinutes(),
                    (int) Duration.between(unit.getBeginTime(), unit.getEndTime()).toMinutes());
            if (i > 0) {
                assertEquals(units.get(i - 1).getEndTime(), unit.getBeginTime(),
                        "unit " + i + " must be continuous with previous unit");
            }
        }
    }

    static void assertSingleSegmentUnitsConsistent(BillingResult result) {
        CompactConsistencyAssert.assertUnitsConsistent(result.getUnits());
    }
}
