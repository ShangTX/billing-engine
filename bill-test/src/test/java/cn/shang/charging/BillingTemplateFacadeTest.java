package cn.shang.charging;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.billing.pojo.TimeRoundingMode;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.wrapper.BillingTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class BillingTemplateFacadeTest {

    @Test
    void normalize_returnsMinuteAlignedCopy() {
        BillingTemplate template = BillingTemplate.create(resolver(List.of()));
        BillingRequest request = request(
                LocalDateTime.of(2026, 7, 15, 9, 0, 30),
                LocalDateTime.of(2026, 7, 15, 11, 0, 45));
        PromotionGrant grant = freeRange(
                LocalDateTime.of(2026, 7, 15, 10, 0, 15),
                LocalDateTime.of(2026, 7, 15, 10, 30, 20));
        request.setExternalPromotions(List.of(grant));

        BillingRequest normalized = template.normalize(request, TimeRoundingMode.TRUNCATE_BOTH);

        assertNotSame(request, normalized);
        assertNotSame(request.getExternalPromotions().get(0), normalized.getExternalPromotions().get(0));
        assertEquals(LocalDateTime.of(2026, 7, 15, 9, 0), normalized.getBeginTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 11, 0), normalized.getEndTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 0), normalized.getExternalPromotions().get(0).getBeginTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 30), normalized.getExternalPromotions().get(0).getEndTime());

        assertEquals(LocalDateTime.of(2026, 7, 15, 9, 0, 30), request.getBeginTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 0, 15), request.getExternalPromotions().get(0).getBeginTime());
    }

    @Test
    void normalize_ceilBeginTruncateEnd_narrowsBillingAndWidensFreeRange() {
        BillingTemplate template = BillingTemplate.create(resolver(List.of()));
        BillingRequest request = request(
                LocalDateTime.of(2026, 7, 15, 9, 0, 30),
                LocalDateTime.of(2026, 7, 15, 11, 0, 45));
        request.setExternalPromotions(List.of(freeRange(
                LocalDateTime.of(2026, 7, 15, 10, 0, 15),
                LocalDateTime.of(2026, 7, 15, 10, 30, 20))));

        BillingRequest normalized = template.normalize(request, TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);

        assertEquals(LocalDateTime.of(2026, 7, 15, 9, 1), normalized.getBeginTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 11, 0), normalized.getEndTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 0), normalized.getExternalPromotions().get(0).getBeginTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 31), normalized.getExternalPromotions().get(0).getEndTime());
    }

    @Test
    void normalize_requestModeOverridesMethodMode() {
        BillingTemplate template = BillingTemplate.create(resolver(List.of()));
        BillingRequest request = request(
                LocalDateTime.of(2026, 7, 15, 9, 0, 30),
                LocalDateTime.of(2026, 7, 15, 11, 0, 45));
        request.setTimeRoundingMode(TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);

        BillingRequest normalized = template.normalize(request, TimeRoundingMode.TRUNCATE_BOTH);

        assertEquals(LocalDateTime.of(2026, 7, 15, 9, 1), normalized.getBeginTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 11, 0), normalized.getEndTime());
    }

    @Test
    void normalize_ceilBeginTruncateEnd_sameMinuteFallsBackToTruncateBoth() {
        BillingTemplate template = BillingTemplate.create(resolver(List.of()));
        BillingRequest request = request(
                LocalDateTime.of(2026, 7, 15, 10, 30, 30),
                LocalDateTime.of(2026, 7, 15, 10, 30, 50));

        BillingRequest normalized = template.normalize(request, TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);

        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 30), normalized.getBeginTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 30), normalized.getEndTime());
    }

    @Test
    void calculate_doesNotMutateOriginalRequest() {
        BillingTemplate template = BillingTemplate.create(resolver(List.of()));
        BillingRequest request = request(
                LocalDateTime.of(2026, 7, 15, 9, 0, 30),
                LocalDateTime.of(2026, 7, 15, 10, 30, 45));
        request.setExternalPromotions(List.of(freeRange(
                LocalDateTime.of(2026, 7, 15, 10, 0, 15),
                LocalDateTime.of(2026, 7, 15, 10, 15, 20))));

        BillingResult result = template.calculate(request);

        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));
        assertEquals(LocalDateTime.of(2026, 7, 15, 9, 0, 30), request.getBeginTime());
        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 0, 15), request.getExternalPromotions().get(0).getBeginTime());
    }

    @Test
    void create_assemblesDefaultPromotionRules() {
        StartFreePromotionConfig startFree = StartFreePromotionConfig.builder()
                .id("start-free")
                .priority(1)
                .minutes(60)
                .build();
        BillingTemplate template = BillingTemplate.create(resolver(List.of(startFree)));
        BillingRequest request = request(
                LocalDateTime.of(2026, 7, 15, 9, 0),
                LocalDateTime.of(2026, 7, 15, 11, 0));

        BillingResult result = template.calculate(request);

        assertEquals(0, new BigDecimal("2.00").compareTo(result.getFinalAmount()));
        assertEquals("start-free", result.getPromotionUsages().get(0).getPromotionId());
    }

    private BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest request = new BillingRequest();
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeId("scheme-1");
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        request.setExternalPromotions(List.of());
        return request;
    }

    private PromotionGrant freeRange(LocalDateTime begin, LocalDateTime end) {
        return PromotionGrant.builder()
                .id("external-free")
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .beginTime(begin)
                .endTime(end)
                .build();
    }

    private BillingConfigResolver resolver(List<PromotionRuleConfig> promotions) {
        DayNightConfig config = new DayNightConfig()
                .setId("day-night")
                .setDayBeginMinute(8 * 60)
                .setDayEndMinute(20 * 60)
                .setDayUnitPrice(new BigDecimal("2"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("100"))
                .setBlockWeight(new BigDecimal("0.5"));

        return new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId,
                                                  LocalDateTime segmentStart,
                                                  LocalDateTime segmentEnd,
                                                  Map<String, Object> context) {
                return config;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                                   LocalDateTime segmentStart,
                                                                   LocalDateTime segmentEnd,
                                                                   Map<String, Object> context) {
                return promotions;
            }
        };
    }
}
