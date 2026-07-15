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
import cn.shang.charging.promotion.pojo.PromotionActivationMode;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalPromotionActivationTest {

    @Test
    void periodMode_conditionalFreeRangeAppliesWhenBillingEndInsideRange() {
        BillingService service = createService(dayNightConfig(), BConstants.CalculationMode.DURATION_PERIOD, List.of());

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(conditionalFreeRange("cond-range",
                LocalDateTime.of(2026, 1, 1, 9, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0))));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("2.00").compareTo(result.getFinalAmount()));
        assertTrue(result.getPromotionUsages().stream()
                .anyMatch(u -> "cond-range".equals(u.getPromotionId())
                        && u.getType() == BConstants.PromotionType.FREE_RANGE));
    }

    @Test
    void periodMode_conditionalFreeRangeIgnoredWhenBillingEndAfterRange() {
        BillingService service = createService(dayNightConfig(), BConstants.CalculationMode.DURATION_PERIOD, List.of());

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        req.setExternalPromotions(List.of(conditionalFreeRange("cond-range",
                LocalDateTime.of(2026, 1, 1, 9, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0))));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("8.00").compareTo(result.getFinalAmount()));
        assertTrue(result.getPromotionUsages().stream()
                .noneMatch(u -> "cond-range".equals(u.getPromotionId())));
    }

    @Test
    void globalMode_conditionalFreeRangeAppliesOnlyWhenBillingEndInsideRange() {
        BillingService service = createService(dayNightConfig(), BConstants.CalculationMode.DURATION_GLOBAL, List.of());

        BillingRequest active = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        active.setExternalPromotions(List.of(conditionalFreeRange("cond-range",
                LocalDateTime.of(2026, 1, 1, 9, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0))));
        assertEquals(0, new BigDecimal("2.00").compareTo(service.calculate(active).getFinalAmount()));

        BillingRequest inactive = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        inactive.setExternalPromotions(List.of(conditionalFreeRange("cond-range",
                LocalDateTime.of(2026, 1, 1, 9, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0))));
        BillingResult inactiveResult = service.calculate(inactive);

        assertEquals(0, new BigDecimal("8.00").compareTo(inactiveResult.getFinalAmount()));
        assertTrue(inactiveResult.getPromotionUsages().stream()
                .noneMatch(u -> "cond-range".equals(u.getPromotionId())));
    }

    @Test
    void continuousMode_rejectsConditionalActivation() {
        BillingService service = createService(dayNightConfig(), BConstants.CalculationMode.CONTINUOUS, List.of());

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(conditionalFreeRange("cond-range",
                LocalDateTime.of(2026, 1, 1, 9, 0),
                LocalDateTime.of(2026, 1, 1, 11, 0))));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.calculate(req));
        assertTrue(ex.getMessage().contains("activationMode"));
    }

    @Test
    void periodMode_conditionalFreeMinutesAppliesOnlyWhenBillingEndInsideGeneratedRange() {
        BillingService service = createService(dayNightConfig(), BConstants.CalculationMode.DURATION_PERIOD, List.of());

        BillingRequest active = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 8, 20));
        active.setExternalPromotions(List.of(conditionalFreeMinutes("cond-min", 30)));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.calculate(active).getFinalAmount()));

        BillingRequest inactive = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 8, 40));
        inactive.setExternalPromotions(List.of(conditionalFreeMinutes("cond-min", 30)));
        assertEquals(0, new BigDecimal("2.00").compareTo(service.calculate(inactive).getFinalAmount()));
    }

    @Test
    void periodMode_startFreePromotionSupportsConditionalActivation() {
        StartFreePromotionConfig startFree = StartFreePromotionConfig.builder()
                .id("start-free")
                .priority(1)
                .minutes(30)
                .activationMode(PromotionActivationMode.END_WITHIN_RANGE)
                .build();
        BillingService service = createService(dayNightConfig(), BConstants.CalculationMode.DURATION_PERIOD, List.of(startFree));

        BillingResult active = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 8, 20)));
        assertEquals(0, BigDecimal.ZERO.compareTo(active.getFinalAmount()));

        BillingResult inactive = service.calculate(request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 8, 40)));
        assertEquals(0, new BigDecimal("2.00").compareTo(inactive.getFinalAmount()));
    }

    private PromotionGrant conditionalFreeRange(String id, LocalDateTime begin, LocalDateTime end) {
        return PromotionGrant.builder()
                .id(id)
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(begin)
                .endTime(end)
                .priority(1)
                .activationMode(PromotionActivationMode.END_WITHIN_RANGE)
                .build();
    }

    private PromotionGrant conditionalFreeMinutes(String id, int minutes) {
        return PromotionGrant.builder()
                .id(id)
                .type(BConstants.PromotionType.FREE_MINUTES)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(minutes)
                .priority(1)
                .activationMode(PromotionActivationMode.END_WITHIN_RANGE)
                .build();
    }

    private BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        r.setSchemeChanges(List.of());
        r.setExternalPromotions(List.of());
        return r;
    }

    private DayNightConfig dayNightConfig() {
        return DayNightConfig.builder()
                .id("dn-conditional")
                .dayBeginMinute(8 * 60)
                .dayEndMinute(20 * 60)
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .unitMinutes(60)
                .maxChargeOneDay(new BigDecimal("100.00"))
                .blockWeight(new BigDecimal("0.5"))
                .build();
    }

    private BillingService createService(
            DayNightConfig config,
            BConstants.CalculationMode calculationMode,
            List<PromotionRuleConfig> promotionRules) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return promotionRules;
            }

            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return calculationMode;
            }
        };

        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
        PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
        promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        promotionRuleRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), promotionRuleRegistry),
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
