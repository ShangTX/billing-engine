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
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelativeTimeParkingParityTest {

    @Test
    void shouldMatchParkingLegacyRelativeTimeCase() {
        LocalDateTime beginTime = LocalDateTime.of(2026, 5, 3, 8, 45);
        LocalDateTime endTime = beginTime.plusMinutes(5677);

        RelativeTimeConfig config = new RelativeTimeConfig();
        config.setId("relative-time-parity");
        config.setMaxChargeOneCycle(new BigDecimal("45.00"));
        RelativeTimePeriod period = new RelativeTimePeriod();
        period.setBeginMinute(0);
        period.setEndMinute(1440);
        period.setUnitMinutes(60);
        period.setUnitPrice(new BigDecimal("1.00"));
        config.setPeriods(List.of(period));

        BillingResult result = createService(config).calculate(baseRequest(beginTime, endTime));
        BigDecimal expected = expectedLegacyAmount(beginTime, endTime, config);

        assertEquals(0, expected.compareTo(result.getFinalAmount()),
                () -> "relativeTime 停车差异样例金额不一致，expected=" + expected + ", actual=" + result.getFinalAmount());
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

    private BillingService createService(RelativeTimeConfig config) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.CONTINUOUS;
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
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        PromotionEngine promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
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

    private BigDecimal expectedLegacyAmount(LocalDateTime beginTime, LocalDateTime endTime, RelativeTimeConfig config) {
        BigDecimal total = BigDecimal.ZERO;
        LocalDateTime cycleStart = beginTime;

        while (cycleStart.isBefore(endTime)) {
            LocalDateTime cycleEnd = cycleStart.plusHours(24);
            if (cycleEnd.isAfter(endTime)) {
                cycleEnd = endTime;
            }

            BigDecimal cycleAmount = BigDecimal.ZERO;
            LocalDateTime unitStart = cycleStart;
            RelativeTimePeriod period = config.getPeriods().getFirst();
            while (unitStart.isBefore(cycleEnd)) {
                cycleAmount = cycleAmount.add(period.getUnitPrice());
                if (cycleAmount.compareTo(config.getMaxChargeOneCycle()) >= 0) {
                    cycleAmount = config.getMaxChargeOneCycle();
                    break;
                }
                unitStart = unitStart.plusMinutes(period.getUnitMinutes());
            }

            total = total.add(cycleAmount);
            cycleStart = cycleEnd;
        }

        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
