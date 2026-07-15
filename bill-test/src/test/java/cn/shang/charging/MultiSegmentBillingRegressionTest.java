package cn.shang.charging;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.charge.rules.compositetime.CompositePeriod;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSegmentBillingRegressionTest {

    @Test
    void playgroundTwoSegmentScenarioKeepsBoundaryAndAmount() {
        MultiSegmentBillingPlayground.MultiSegmentScenario scenario =
                MultiSegmentBillingPlayground.scenario1_twoSegments();

        BillingResult result = calculate(scenario);

        BillingTestSupport.assertAmount("26.00", result.getFinalAmount());
        BillingTestSupport.assertUnitsCover(
                result,
                LocalDateTime.of(2026, 7, 7, 8, 0),
                LocalDateTime.of(2026, 7, 7, 18, 0));
        assertEquals(2, result.getUnits().size());
        assertEquals(LocalDateTime.of(2026, 7, 7, 12, 0), result.getUnits().get(0).getEndTime());
        assertEquals(LocalDateTime.of(2026, 7, 7, 12, 0), result.getUnits().get(1).getBeginTime());
    }

    @Test
    void freeMinutesAreConsumedOnceAcrossSegments() {
        LocalDateTime begin = LocalDateTime.of(2026, 7, 7, 8, 0);
        MultiSegmentBillingPlayground.MultiSegmentScenario scenario =
                MultiSegmentBillingPlayground.MultiSegmentScenario.builder()
                        .name("free minutes consumed once")
                        .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                        .addSegment(MultiSegmentBillingPlayground.SegmentConfig.builder()
                                .schemeId("morning-a")
                                .beginTime(begin)
                                .endTime(begin.plusHours(1))
                                .ruleConfig(dayNightConfig("morning-a-rule", "2.00"))
                                .externalPromotions(List.of(PromotionGrant.builder()
                                        .id("member-free-60")
                                        .type(BConstants.PromotionType.FREE_MINUTES)
                                        .source(BConstants.PromotionSource.COUPON)
                                        .freeMinutes(60)
                                        .priority(1)
                                        .build()))
                                .build())
                        .addSegment(MultiSegmentBillingPlayground.SegmentConfig.builder()
                                .schemeId("morning-b")
                                .beginTime(begin.plusHours(1))
                                .endTime(begin.plusHours(2))
                                .ruleConfig(dayNightConfig("morning-b-rule", "3.00"))
                                .build())
                        .build();

        BillingResult result = calculate(scenario);

        BillingTestSupport.assertAmount("3.00", result.getFinalAmount());
        BillingTestSupport.assertUnitsCover(result, begin, begin.plusHours(2));
        assertEquals(1, result.getPromotionUsages().size());
        assertEquals("member-free-60", result.getPromotionUsages().get(0).getPromotionId());
        assertEquals(60, result.getPromotionUsages().get(0).getUsedMinutes());
    }

    @Test
    void compositeDurationPeriodSegmentsUseRealPipeline() {
        LocalDateTime begin = LocalDateTime.of(2026, 7, 10, 8, 0);
        MultiSegmentBillingPlayground.MultiSegmentScenario scenario =
                MultiSegmentBillingPlayground.MultiSegmentScenario.builder()
                        .name("composite duration period two segments")
                        .calculationMode(BConstants.CalculationMode.DURATION_PERIOD)
                        .addSegment(MultiSegmentBillingPlayground.SegmentConfig.builder()
                                .schemeId("weekday")
                                .beginTime(begin)
                                .endTime(begin.plusHours(2))
                                .ruleConfig(compositeConfig("weekday-rule", "2.00"))
                                .build())
                        .addSegment(MultiSegmentBillingPlayground.SegmentConfig.builder()
                                .schemeId("weekend")
                                .beginTime(begin.plusHours(2))
                                .endTime(begin.plusHours(4))
                                .ruleConfig(compositeConfig("weekend-rule", "3.00"))
                                .build())
                        .build();

        BillingResult result = calculate(scenario);

        BillingTestSupport.assertAmount("10.00", result.getFinalAmount());
        List<DurationSegment> segments = result.getDurationSegments();
        assertFalse(segments.isEmpty());
        assertEquals(2, segments.size());
        assertEquals("r:0-1440|n:0-1440", segments.get(0).periodLabel());
        assertEquals("r:0-1440|n:0-1440", segments.get(1).periodLabel());
        assertEquals(begin, segments.get(0).beginTime());
        assertEquals(begin.plusHours(2), segments.get(0).endTime());
        assertEquals(begin.plusHours(2), segments.get(1).beginTime());
        assertEquals(begin.plusHours(4), segments.get(1).endTime());
        assertTrue(result.getUnits() == null || result.getUnits().isEmpty());
    }

    private static BillingResult calculate(MultiSegmentBillingPlayground.MultiSegmentScenario scenario) {
        BillingRequest request = MultiSegmentBillingPlayground.buildRequest(scenario);
        BillingConfigResolver resolver = MultiSegmentBillingPlayground.buildResolver(scenario);
        return BillingTestSupport.service(resolver).calculate(request);
    }

    private static DayNightConfig dayNightConfig(String id, String dayPrice) {
        return new DayNightConfig()
                .setId(id)
                .setDayBeginMinute(8 * 60)
                .setDayEndMinute(20 * 60)
                .setDayUnitPrice(new BigDecimal(dayPrice))
                .setNightUnitPrice(new BigDecimal("1.00"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("100.00"))
                .setBlockWeight(new BigDecimal("0.5"));
    }

    private static CompositeTimeConfig compositeConfig(String id, String unitPrice) {
        return CompositeTimeConfig.builder()
                .id(id)
                .maxChargeOneCycle(new BigDecimal("100.00"))
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
