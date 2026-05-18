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
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.util.JacksonUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DayNightParkingParityTest {

    @Test
    void shouldMatchParkingLegacyDayNightCase() {
        LocalDateTime beginTime = LocalDateTime.of(2026, 11, 3, 10, 52);
        LocalDateTime endTime = LocalDateTime.of(2026, 11, 4, 6, 0);

        DayNightConfig config = DayNightConfig.builder()
                .id("day-night-parity")
                .dayBeginMinute(374)
                .dayEndMinute(977)
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .dayUnitPrice(new BigDecimal("9.40"))
                .nightUnitPrice(new BigDecimal("1.50"))
                .maxChargeOneDay(new BigDecimal("158.27"))
                .build();

        BillingResult result = createService(config).calculate(baseRequest(beginTime, endTime));
        BigDecimal expected = new BigDecimal("69.50");

        System.out.println("=== BillingResult JSON ===");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println("=== BillingUnit Wrappers JSON ===");
        System.out.println(JacksonUtils.toJsonString(result.getUnits().stream()
                .map(unit -> BillingUnitDebugView.from(unit))
                .toList()));

        assertEquals(0, expected.compareTo(result.getFinalAmount()),
                () -> "dayNight 停车差异样例金额不一致，expected=" + expected + ", actual=" + result.getFinalAmount());
    }

    private BillingRequest baseRequest(LocalDateTime beginTime, LocalDateTime endTime) {
        BillingRequest request = new BillingRequest();
        request.setBeginTime(beginTime);
        request.setEndTime(endTime);
        request.setSchemeId("scheme-1");
        request.setSchemeChanges(List.of());
        request.setExternalPromotions(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        return request;
    }

    private BillingService createService(DayNightConfig config) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
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

    private record BillingUnitDebugView(
            LocalDateTime beginTime,
            LocalDateTime endTime,
            Integer durationMinutes,
            BigDecimal unitPrice,
            BigDecimal originalAmount,
            BigDecimal chargedAmount,
            BigDecimal accumulatedAmount,
            Boolean free,
            Boolean truncated,
            String freePromotionId,
            Object ruleData
    ) {
        private static BillingUnitDebugView from(cn.shang.charging.billing.pojo.BillingUnit unit) {
            return new BillingUnitDebugView(
                    unit.getBeginTime(),
                    unit.getEndTime(),
                    unit.getDurationMinutes(),
                    unit.getUnitPrice(),
                    unit.getOriginalAmount(),
                    unit.getChargedAmount(),
                    unit.getAccumulatedAmount(),
                    unit.isFree(),
                    unit.getIsTruncated(),
                    unit.getFreePromotionId(),
                    unit.getRuleData()
            );
        }
    }
}
