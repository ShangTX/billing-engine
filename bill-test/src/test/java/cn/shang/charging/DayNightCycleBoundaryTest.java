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
import cn.shang.charging.wrapper.BillingTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DayNight 周期边界测试：验证周期是「从 beginTime 起算的 24h 固定循环」，不是自然日。
 * <p>
 * 防回归：TODO-20260706-002 阶段3a 曾误改为自然日（跨午夜重置），此处锁定 24h 循环语义。
 */
class DayNightCycleBoundaryTest {

    /**
     * beginTime=10:00（非 00:00），计费 28h 跨 24h 周期。
     * <p>
     * 24h 循环（正确）：
     * - 第一周期 10:00–次日10:00（24h）：白天10h(20)+夜间12h(12)+白天2h(4)=36 元 > 封顶30 → 30
     * - 第二周期 次日10:00–14:00（4h 白天）：4×2=8 元 < 30 → 8
     * - 合计 38 元
     * <p>
     * 自然日（错误回退）：
     * - 第一天 10:00–24:00：封顶30 → 30
     * - 第二天 00:00–14:00：夜间8h(8)+白天6h(12)=20 元 < 30 → 20
     * - 合计 50 元
     */
    @Test
    void cycleIs24hFromBeginTime_notNaturalDay() {
        DayNightConfig config = new DayNightConfig()
                .setId("dn-cycle")
                .setDayBeginMinute(8 * 60)
                .setDayEndMinute(20 * 60)
                .setDayUnitPrice(new BigDecimal("2"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("30"))
                .setBlockWeight(new BigDecimal("0.5"));
        BillingTemplate template = createTemplate(config);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 2, 14, 0));
        BillingResult result = template.calculate(req);

        // 24h 循环：38 元（非自然日的 50 元）
        assertEquals(0, new BigDecimal("38.00").compareTo(result.getFinalAmount()),
                "DayNight 周期应为 24h 固定循环（从 beginTime 起算），不是自然日");
    }

    private BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSchemeChanges(List.of());
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        r.setExternalPromotions(List.of());
        return r;
    }

    private BillingTemplate createTemplate(DayNightConfig config) {
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
        PromotionEngine promotionEngine = new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry());
        BillingService billingService = new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());
        return new BillingTemplate(billingService, resolver);
    }
}
