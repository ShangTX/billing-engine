package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingResultViewer;
import cn.shang.charging.wrapper.BillingTemplate;
import cn.shang.charging.wrapper.QuerySummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighRiskFeatureIntegrationTest {

    @Test
    void bubbleFreeRange_shouldAccumulateCycleBoundaryAcrossContinueCalls() {
        BillingConfigResolver resolver = createBubbleResolver();
        BillingService billingService = createDayNightBillingService(resolver, new PromotionRuleRegistry());

        BillingRequest request1 = new BillingRequest();
        request1.setId("risk-bubble-continue-1");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");
        request1.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("bubble-120")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                        .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 13, 0, 0))
                        .rangeType(FreeTimeRangeType.BUBBLE)
                        .build()
        ));

        BillingResult first = billingService.calculate(request1);

        BillingRequest request2 = new BillingRequest();
        request2.setId("risk-bubble-continue-2");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 18, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setPreviousCarryOver(first.getCarryOver());
        request2.setExternalPromotions(request1.getExternalPromotions());

        BillingResult second = billingService.calculate(request2);
        var ruleState = second.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState = (Map<String, Object>) ruleState.get("dayNight");

        assertEquals("2026-03-11T10:00", String.valueOf(dayNightState.get("cycleBoundary")));
    }

    @Test
    void simplifiedResult_shouldFallbackToPreciseQueryWhenHitUnitIsSimplified() {
        BillingConfigResolver resolver = createSimplificationResolver(7);
        BillingService billingService = createDayNightBillingService(resolver, new PromotionRuleRegistry());
        BillingTemplate template = new BillingTemplate(billingService, resolver);

        BillingRequest request = new BillingRequest();
        request.setId("risk-simplified-query");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 1, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 31, 8, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = template.calculateWithQuery(request, LocalDateTime.of(2026, Month.MARCH, 20, 8, 30, 0));

        assertNotNull(result.getCalculationResult());
        assertNotNull(result.getQueryResult());
        assertTrue(result.getCalculationResult().getUnits().stream().noneMatch(SimplifiedCalculationTest::isSimplifiedUnit));
    }

    @Test
    void conditionalStartFree_shouldBeFreeInsideWindowAndChargedOutsideWindow() {
        BillingService billingService = createConditionalStartFreeBillingService(60);
        BillingRequest request = new BillingRequest();
        request.setId("risk-conditional-query");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        BillingResult result = billingService.calculate(request);
        BillingResultViewer viewer = new BillingResultViewer();

        QuerySummary inside = viewer.createQuerySummary(result, LocalDateTime.of(2026, Month.MARCH, 10, 0, 46, 0));
        QuerySummary outside = viewer.createQuerySummary(result, LocalDateTime.of(2026, Month.MARCH, 10, 1, 1, 0));

        assertEquals(0, BigDecimal.ZERO.compareTo(inside.getAmount()));
        assertTrue(outside.getAmount().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void bubbleFreeRange_shouldStillAllowSimplificationAndPreserveExtendedBoundary() {
        BillingConfigResolver resolver = createSimplificationResolver(2);
        BillingService billingService = createDayNightBillingService(resolver, new PromotionRuleRegistry());

        BillingRequest request = new BillingRequest();
        request.setId("risk-bubble-simplified");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 1, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 11, 8, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("bubble-60")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, Month.MARCH, 1, 11, 0, 0))
                        .endTime(LocalDateTime.of(2026, Month.MARCH, 1, 12, 0, 0))
                        .rangeType(FreeTimeRangeType.BUBBLE)
                        .build()
        ));

        BillingResult result = billingService.calculate(request);
        long simplifiedCount = result.getUnits().stream().filter(SimplifiedCalculationTest::isSimplifiedUnit).count();

        assertTrue(simplifiedCount > 0, "应至少生成一个简化单元");
        var ruleState = result.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState = (Map<String, Object>) ruleState.get("dayNight");
        assertEquals("2026-03-11T09:00", String.valueOf(dayNightState.get("cycleBoundary")));
    }

    @Test
    void conditionalStartFree_shouldKeepQueryBehaviorThroughBillingTemplateApi() {
        BillingConfigResolver resolver = createConditionalStartFreeResolver(60);
        BillingService billingService = createConditionalStartFreeBillingService(60);
        BillingTemplate template = new BillingTemplate(billingService, resolver);

        BillingRequest request = new BillingRequest();
        request.setId("risk-conditional-template-query");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var inside = template.calculateWithQuery(request, LocalDateTime.of(2026, Month.MARCH, 10, 0, 46, 0));
        var outside = template.calculateWithQuery(request, LocalDateTime.of(2026, Month.MARCH, 10, 1, 1, 0));

        assertEquals(0, BigDecimal.ZERO.compareTo(inside.getQueryResult().getAmount()));
        assertTrue(outside.getQueryResult().getAmount().compareTo(BigDecimal.ZERO) > 0);
    }

    private static BillingConfigResolver createBubbleResolver() {
        return new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)
                        .setDayEndMinute(1200)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("50"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }

            @Override
            public int getSimplifiedCycleThreshold() {
                return 7;
            }
        };
    }

    private static BillingConfigResolver createSimplificationResolver(int threshold) {
        return new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)
                        .setDayEndMinute(1200)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("10"))
                        .setUnitMinutes(60)
                        .setSimplifiedSupported(true);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }

            @Override
            public int getSimplifiedCycleThreshold() {
                return threshold;
            }
        };
    }

    private static BillingService createConditionalStartFreeBillingService(int startFreeMinutes) {
        BillingConfigResolver resolver = createConditionalStartFreeResolver(startFreeMinutes);

        PromotionRuleRegistry promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());
        return createDayNightBillingService(resolver, promotionRegistry);
    }

    private static BillingConfigResolver createConditionalStartFreeResolver(int startFreeMinutes) {
        return new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740)
                        .setDayEndMinute(1140)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("100"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of(
                        new StartFreePromotionConfig()
                                .setId("start-free-conditional")
                                .setMinutes(startFreeMinutes)
                                .setPriority(1)
                                .setValidateQueryTime(true)
                );
            }
        };
    }

    private static BillingService createDayNightBillingService(BillingConfigResolver resolver, PromotionRuleRegistry promotionRegistry) {
        PromotionEngine promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
                promotionRegistry
        );

        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
