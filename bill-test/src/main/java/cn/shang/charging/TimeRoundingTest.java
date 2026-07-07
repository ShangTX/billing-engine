package cn.shang.charging;

import cn.shang.charging.billing.*;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;
import cn.shang.charging.wrapper.TimeRounding;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 时间取整演示：引擎对所有时间统一向下取整，外部业务策略通过 {@link TimeRounding} 预处理。
 */
public class TimeRoundingTest {
    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        } catch (Exception ignored) {
        }

        System.out.println("========== 时间取整演示（引擎统一向下取整）==========\n");

        BillingTemplate template = getBillingTemplate();
        LocalDateTime begin = LocalDateTime.of(2026, 3, 26, 15, 37, 14);
        LocalDateTime end = LocalDateTime.of(2026, 3, 26, 17, 37, 19);
        System.out.println("原始时间: " + begin + " - " + end);

        // 1. 引擎统一向下取整（忽略 TimeRoundingMode，所有模式结果一致）
        System.out.println("\n【直接传入带秒数时间 → 引擎统一向下取整】");
        BillingRequest req = request(begin, end);
        BillingResult result = template.calculate(req);
        System.out.println("  处理后: " + req.getBeginTime() + " - " + req.getEndTime());
        System.out.println("  金额: " + result.getFinalAmount() + " 元");

        // 2. 外部预处理：begin 向上取整（"进场多算"业务策略）
        System.out.println("\n【外部预处理：TimeRounding.ceil(begin) 后传入】");
        BillingRequest req2 = request(TimeRounding.ceil(begin), end);
        BillingResult result2 = template.calculate(req2);
        System.out.println("  处理后: " + req2.getBeginTime() + " - " + req2.getEndTime());
        System.out.println("  金额: " + result2.getFinalAmount() + " 元");
        System.out.println("  （引擎向下取整不改变已对齐的 begin，外部意图保留）");
    }

    static BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSchemeChanges(List.of());
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        return r;
    }

    static BillingTemplate getBillingTemplate() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-test")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(420)
                        .setDayEndMinute(1140)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("2"))
                        .setMaxChargeOneDay(new BigDecimal("20"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        BillingService billingService = new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );

        return new BillingTemplate(billingService, billingConfigResolver);
    }
}
