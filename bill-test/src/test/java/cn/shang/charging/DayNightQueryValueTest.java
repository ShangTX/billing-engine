package cn.shang.charging;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingResultViewer;
import cn.shang.charging.wrapper.QuerySummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DayNightQueryValueTest {

    @Test
    void unitBased_mixedDayNightUnit_returnsProgressiveCurrentValue() {
        BillingService service = createUnitBasedService();

        BillingRequest request = new BillingRequest();
        request.setBeginTime(LocalDateTime.of(2026, 4, 20, 18, 50));
        request.setEndTime(LocalDateTime.of(2026, 4, 20, 19, 50));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(List.of());

        BillingResult result = service.calculate(request);
        BillingResultViewer viewer = new BillingResultViewer();

        QuerySummary at1900 = viewer.createQuerySummary(result, LocalDateTime.of(2026, 4, 20, 19, 0));
        QuerySummary at1920 = viewer.createQuerySummary(result, LocalDateTime.of(2026, 4, 20, 19, 20));

        assertEquals(new BigDecimal("2.00"), at1900.getAmount());
        assertEquals(new BigDecimal("1.00"), at1920.getAmount());
    }

    private BillingService createUnitBasedService() {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.UNIT_BASED;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return DayNightConfig.builder()
                        .id("day-night")
                        .dayBeginMinute(8 * 60)
                        .dayEndMinute(19 * 60)
                        .unitMinutes(60)
                        .blockWeight(new BigDecimal("0.5"))
                        .dayUnitPrice(new BigDecimal("2.00"))
                        .nightUnitPrice(new BigDecimal("1.00"))
                        .maxChargeOneDay(new BigDecimal("100.00"))
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }
        };

        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        PromotionEngine promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                new PromotionRuleRegistry()
        );

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
