package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.PromotionEquivalentCalculator;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FREE_MINUTES allocationMode 专项测试。
 * <p>
 * dayNight：日段 8:00-20:00 单价 2 元/h，夜段单价 1 元/h，unitMinutes=60。
 */
class FreeMinutesAllocationModeTest {

    @Test
    void globalMode_freeMinutes_defaultFromStart() {
        DayNightConfig config = dayNightConfig("fm-default", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(freeMinutes("fm-60-default", 60, 1, null)));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("5.00").compareTo(result.getFinalAmount()));

        PromotionUsage usage = usage(result, "fm-60-default");
        assertEquals(BConstants.PromotionType.FREE_MINUTES, usage.getType());
        assertEquals(LocalDateTime.of(2026, 1, 1, 6, 0), usage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 7, 0), usage.getUsedTo());
    }

    @Test
    void globalMode_freeMinutes_chargedTimeSkipsZeroPricePeriod() {
        DayNightConfig config = dayNightConfig("fm-chargeable", new BigDecimal("1000.00"))
                .setNightUnitPrice(BigDecimal.ZERO);
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(freeMinutes(
                "fm-60-chargeable", 60, 1, BConstants.FreeMinutesAllocationMode.CHARGED_TIME)));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("2.00").compareTo(result.getFinalAmount()));

        PromotionUsage usage = usage(result, "fm-60-chargeable");
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0), usage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), usage.getUsedTo());
    }

    @Test
    void globalMode_freeMinutes_highestPricePrefersHighPriceSegment() {
        DayNightConfig config = dayNightConfig("fm-high", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(freeMinutes(
                "fm-60-high", 60, 1, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE)));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));

        List<DurationSegment> segs = result.getDurationSegments();
        assertNotNull(segs);
        assertTrue(segs.stream().allMatch(s -> s.beginTime() == null && s.endTime() == null));
        assertTrue(segs.stream().noneMatch(s -> s.chargedMinutes() == 0));

        PromotionUsage usage = usage(result, "fm-60-high");
        assertEquals(60, usage.getGrantedMinutes());
        assertEquals(60, usage.getUsedMinutes());
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0), usage.getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), usage.getUsedTo());
    }

    @Test
    void globalMode_freeMinutes_highestPriceOverflowsToLowerPriceSegment() {
        DayNightConfig config = dayNightConfig("fm-overflow", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(freeMinutes(
                "fm-180-high", 180, 1, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE)));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("1.00").compareTo(result.getFinalAmount()));
        assertEquals(180, usage(result, "fm-180-high").getUsedMinutes());
    }

    @Test
    void globalMode_freeMinutes_homogeneousSameAsFromStart() {
        DayNightConfig config = dayNightConfig("fm-homo", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 14, 0));
        req.setExternalPromotions(List.of(freeMinutes(
                "fm-60-homo", 60, 1, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE)));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("6.00").compareTo(result.getFinalAmount()));
        assertEquals(180, result.getDurationSegments().get(0).chargedMinutes());
    }

    @Test
    void continuousMode_priceAwareFreeMinutes_throws() {
        assertThrowsWithPriceAwareFreeMinutes(BConstants.CalculationMode.CONTINUOUS);
    }

    @Test
    void unitBasedMode_priceAwareFreeMinutes_throws() {
        assertThrowsWithPriceAwareFreeMinutes(BConstants.CalculationMode.UNIT_BASED);
    }

    @Test
    void periodMode_priceAwareFreeMinutes_throws() {
        assertThrowsWithPriceAwareFreeMinutes(BConstants.CalculationMode.DURATION_PERIOD);
    }

    @Test
    void globalMode_fromStartAndHighestPriceCoexist() {
        DayNightConfig config = dayNightConfig("fm-coexist", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                freeMinutes("fm-60-start", 60, 1, null),
                freeMinutes("fm-60-high", 60, 2, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE)
        ));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("3.00").compareTo(result.getFinalAmount()));
        assertEquals(120, result.getPromotionUsages().stream()
                .filter(u -> u.getType() == BConstants.PromotionType.FREE_MINUTES)
                .mapToLong(PromotionUsage::getUsedMinutes)
                .sum());
    }

    @Test
    void globalMode_highestPriceThenFromStartRespectsPriority() {
        DayNightConfig config = dayNightConfig("fm-priority", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                freeMinutes("fm-60-high", 60, 1, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE),
                freeMinutes("fm-60-start", 60, 2, null)
        ));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("3.00").compareTo(result.getFinalAmount()));
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0), usage(result, "fm-60-high").getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), usage(result, "fm-60-high").getUsedTo());
        assertEquals(LocalDateTime.of(2026, 1, 1, 6, 0), usage(result, "fm-60-start").getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 7, 0), usage(result, "fm-60-start").getUsedTo());
    }

    @Test
    void globalMode_highestPriceSkipsFreeRange() {
        DayNightConfig config = dayNightConfig("fm-skip", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("range-08-09")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 9, 0))
                        .priority(1)
                        .build(),
                freeMinutes("fm-60-high", 60, 1, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE)
        ));

        BillingResult result = service.calculate(req);

        assertEquals(0, new BigDecimal("2.00").compareTo(result.getFinalAmount()));
        assertEquals(LocalDateTime.of(2026, 1, 1, 9, 0), usage(result, "fm-60-high").getUsedFrom());
        assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0), usage(result, "fm-60-high").getUsedTo());
    }

    @Test
    void equivalentAmount_highestPriceFreeMinutes_correct() {
        DayNightConfig config = dayNightConfig("fm-equiv", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);
        PromotionEquivalentCalculator equivalentCalculator = new PromotionEquivalentCalculator(service);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(freeMinutes(
                "fm-60-high", 60, 1, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE)));

        Map<String, BigDecimal> equivalents = equivalentCalculator.calculate(req);

        assertEquals(1, equivalents.size());
        assertEquals(0, new BigDecimal("2.00").compareTo(equivalents.get("fm-60-high")));
    }

    @Test
    void equivalentAmount_fromStartAndHighestPrice_eachCorrect() {
        DayNightConfig config = dayNightConfig("fm-equiv-coexist", new BigDecimal("1000.00"));
        BillingService service = createService(config, BConstants.CalculationMode.DURATION_GLOBAL);
        PromotionEquivalentCalculator equivalentCalculator = new PromotionEquivalentCalculator(service);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                freeMinutes("fm-60-high", 60, 1, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE),
                freeMinutes("fm-60-start", 60, 2, null)
        ));

        Map<String, BigDecimal> equivalents = equivalentCalculator.calculate(req);

        assertEquals(2, equivalents.size());
        assertEquals(0, new BigDecimal("2.00").compareTo(equivalents.get("fm-60-high")));
        assertEquals(0, new BigDecimal("1.00").compareTo(equivalents.get("fm-60-start")));
    }

    private void assertThrowsWithPriceAwareFreeMinutes(BConstants.CalculationMode mode) {
        DayNightConfig config = dayNightConfig("fm-err-" + mode, new BigDecimal("1000.00"));
        BillingService service = createService(config, mode);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 8, 0),
                LocalDateTime.of(2026, 1, 1, 12, 0));
        req.setExternalPromotions(List.of(freeMinutes(
                "fm-err", 60, 1, BConstants.FreeMinutesAllocationMode.HIGHEST_PRICE)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.calculate(req));
        assertTrue(ex.getMessage().contains("allocationMode"),
                "异常消息应提及 allocationMode: " + ex.getMessage());
    }

    private PromotionUsage usage(BillingResult result, String id) {
        return result.getPromotionUsages().stream()
                .filter(u -> id.equals(u.getPromotionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 promotion usage: " + id));
    }

    private PromotionGrant freeMinutes(
            String id,
            int minutes,
            int priority,
            BConstants.FreeMinutesAllocationMode allocationMode) {
        return PromotionGrant.builder()
                .id(id)
                .type(BConstants.PromotionType.FREE_MINUTES)
                .freeMinutes(minutes)
                .allocationMode(allocationMode)
                .priority(priority)
                .build();
    }

    private DayNightConfig dayNightConfig(String id, BigDecimal maxCharge) {
        return DayNightConfig.builder()
                .id(id)
                .dayBeginMinute(8 * 60)
                .dayEndMinute(20 * 60)
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(maxCharge)
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
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

    private BillingService createService(DayNightConfig config, BConstants.CalculationMode calculationMode) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }

            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return calculationMode;
            }
        };

        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry()),
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
