package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DayNight CONTINUOUS 跨日夜单元按比例归属测试。
 * <p>
 * {@code splitDayNightBoundary=false}（opt-in）：CONTINUOUS 不在日夜边界切断，单元跨日夜按
 * {@code crossPeriodMode}（默认 BLOCK_WEIGHT）+ {@code blockWeight} 归属白天/夜晚价。
 * 对照 {@code splitDayNightBoundary=true}（默认，切断）验证差异。
 * <p>
 * 场景：19:30-20:30（跨 dayEnd=20:00），unitMinutes=60，blockWeight=0.5，day 9.40/night 1.50。
 * <ul>
 *   <li>false：19:30-20:30 单元跨日夜，day 30min/60min=0.5 &ge; 0.5 归 DAY -> 9.40</li>
 *   <li>true：19:30-20:00（DAY 9.40）+ 20:00-20:30（NIGHT 1.50）= 10.90</li>
 * </ul>
 */
class DayNightContinuousCrossPeriodTest {

    /** splitDayNightBoundary=false：跨日夜单元按 blockWeight 归 DAY，9.40。 */
    @Test
    void crossDayNight_false_assignsByBlockWeight() {
        BillingResult result = calculate(false);
        assertEquals(0, new BigDecimal("9.40").compareTo(result.getFinalAmount()));
    }

    /** splitDayNightBoundary=true（默认）：日夜边界切断，DAY 9.40 + NIGHT 1.50 = 10.90。 */
    @Test
    void crossDayNight_true_splitsAtBoundary() {
        BillingResult result = calculate(true);
        assertEquals(0, new BigDecimal("10.90").compareTo(result.getFinalAmount()));
    }

    /** 默认（不设 splitDayNightBoundary）：同 true，切断，10.90。 */
    @Test
    void crossDayNight_default_splitsAtBoundary() {
        DayNightConfig config = DayNightConfig.builder()
                .id("dn-cross-default")
                .dayBeginMinute(480)
                .dayEndMinute(1200)
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .dayUnitPrice(new BigDecimal("9.40"))
                .nightUnitPrice(new BigDecimal("1.50"))
                .maxChargeOneDay(new BigDecimal("158.27"))
                .build();
        BillingResult result = createService(config).calculate(request());
        assertEquals(0, new BigDecimal("10.90").compareTo(result.getFinalAmount()));
    }

    // ==================== 辅助方法 ====================

    private BillingResult calculate(boolean splitDayNightBoundary) {
        DayNightConfig config = config(splitDayNightBoundary);
        return createService(config).calculate(request());
    }

    private DayNightConfig config(boolean splitDayNightBoundary) {
        return DayNightConfig.builder()
                .id("dn-cross-" + splitDayNightBoundary)
                .dayBeginMinute(480)
                .dayEndMinute(1200)
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .dayUnitPrice(new BigDecimal("9.40"))
                .nightUnitPrice(new BigDecimal("1.50"))
                .maxChargeOneDay(new BigDecimal("158.27"))
                .splitDayNightBoundary(splitDayNightBoundary)
                .build();
    }

    private BillingRequest request() {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(LocalDateTime.of(2026, 1, 1, 19, 30));
        r.setEndTime(LocalDateTime.of(2026, 1, 1, 20, 30));
        r.setSchemeId("scheme-1");
        r.setSchemeChanges(List.of());
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        r.setExternalPromotions(List.of());
        return r;
    }

    private BillingService createService(DayNightConfig config) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<cn.shang.charging.billing.pojo.PromotionRuleConfig> resolvePromotionRules(
                    String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return List.of();
            }
        };
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
        PromotionEngine promotionEngine = new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry());
        return new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());
    }
}
