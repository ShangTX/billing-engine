package cn.shang.charging;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.compositetime.CompositePeriod;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeTimeServiceContractTest {

    @Test
    void serviceRejectsUnitBasedMode() {
        BillingService service = service(
                singlePeriodConfig("unsupported-unit-based", "1.00", "50.00"),
                BConstants.CalculationMode.UNIT_BASED);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.calculate(BillingTestSupport.request(
                        LocalDateTime.of(2026, 1, 1, 8, 0),
                        LocalDateTime.of(2026, 1, 1, 10, 0))));

        assertTrue(ex.getMessage().contains("does not support calculation mode"));
    }

    @Test
    void continuousCycleCapThroughServiceKeepsCapMarker() {
        BillingService service = service(
                singlePeriodConfig("continuous-cap", "1.00", "3.00"),
                BConstants.CalculationMode.CONTINUOUS);

        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 12, 0);
        BillingResult result = service.calculate(BillingTestSupport.request(begin, end));

        BillingTestSupport.assertAmount("3.00", result.getFinalAmount());
        BillingTestSupport.assertUnitsCover(result, begin, end);
        BillingTestSupport.assertSingleSegmentUnitsConsistent(result);

        BillingUnit lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        assertTrue(lastUnit.isFree());
        assertEquals("CYCLE_CAP", lastUnit.getFreePromotionId());
    }

    @Test
    void globalAggregatesSameBucketAcrossFreeRangeThroughService() {
        BillingService service = service(
                singlePeriodConfig("global-free-range", "2.00", "1000.00"),
                BConstants.CalculationMode.DURATION_GLOBAL);

        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 8, 0);
        BillingRequest request = BillingTestSupport.request(begin, begin.plusMinutes(90));
        request.setExternalPromotions(List.of(PromotionGrant.builder()
                .id("middle-free")
                .type(BConstants.PromotionType.FREE_RANGE)
                .beginTime(begin.plusMinutes(30))
                .endTime(begin.plusMinutes(60))
                .priority(1)
                .build()));

        BillingResult result = service.calculate(request);

        BillingTestSupport.assertAmount("2.00", result.getFinalAmount());
        assertEquals(1, result.getDurationSegments().size());
        DurationSegment bucket = result.getDurationSegments().get(0);
        assertNull(bucket.beginTime());
        assertNull(bucket.endTime());
        assertEquals("r:0-1440|n:0-1440", bucket.periodLabel());
        assertEquals(60, bucket.chargedMinutes());
        BillingTestSupport.assertAmount("2.00", bucket.chargedAmount());
    }

    private static BillingService service(RuleConfig config, BConstants.CalculationMode mode) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return mode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd,
                                                  Map<String, Object> context) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart,
                                                                   LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }
        };
        return BillingTestSupport.service(resolver);
    }

    private static CompositeTimeConfig singlePeriodConfig(String id, String unitPrice, String cycleCap) {
        return CompositeTimeConfig.builder()
                .id(id)
                .maxChargeOneCycle(new BigDecimal(cycleCap))
                .periods(List.of(CompositePeriod.builder()
                        .beginMinute(0)
                        .endMinute(1440)
                        .unitMinutes(60)
                        .naturalPeriods(List.of(NaturalPeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitPrice(new BigDecimal(unitPrice))
                                .build()))
                        .build()))
                .build();
    }
}
