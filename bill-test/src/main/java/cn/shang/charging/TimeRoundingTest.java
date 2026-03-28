package cn.shang.charging;

import cn.shang.charging.billing.*;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class TimeRoundingTest {
    public static void main(String[] args) {
        System.out.println("========== 时间取整模式测试 ==========\n");
        
        // 原始时间：带秒数
        LocalDateTime begin = LocalDateTime.of(2026, 3, 26, 15, 37, 14);
        LocalDateTime end = LocalDateTime.of(2026, 3, 26, 17, 37, 19);
        
        System.out.println("原始时间: " + begin + " - " + end);
        System.out.println();
        
        BillingTemplate template = getBillingTemplate();
        
        // 模式1: 保留秒数
        testMode(template, begin, end, TimeRoundingMode.KEEP_SECONDS, "KEEP_SECONDS");
        
        // 模式2: 两边都去掉秒数
        testMode(template, begin, end, TimeRoundingMode.TRUNCATE_BOTH, "TRUNCATE_BOTH");
        
        // 模式3: 开始向上取整，结束去掉秒数（默认）
        testMode(template, begin, end, TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END, "CEIL_BEGIN_TRUNCATE_END");
        
        // 模式4: 开始去掉秒数，结束向上取整
        testMode(template, begin, end, TimeRoundingMode.TRUNCATE_BEGIN_CEIL_END, "TRUNCATE_BEGIN_CEIL_END");
        
        System.out.println("\n========== 默认模式测试 ==========");
        // 不指定模式，使用默认的 CEIL_BEGIN_TRUNCATE_END
        BillingRequest request = new BillingRequest();
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeId("scheme-1");
        request.setSchemeChanges(List.of());
        
        BillingResult result = template.calculate(request);
        System.out.println("默认模式结果: " + result.getFinalAmount() + " 元");
    }
    
    static void testMode(BillingTemplate template, LocalDateTime begin, LocalDateTime end, 
                         TimeRoundingMode mode, String modeName) {
        BillingRequest request = new BillingRequest();
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeId("scheme-1");
        request.setSchemeChanges(List.of());
        
        BillingResult result = template.calculate(request, mode);
        
        System.out.println("【" + modeName + "】");
        System.out.println("  处理后时间: " + request.getBeginTime() + " - " + request.getEndTime());
        System.out.println("  金额: " + result.getFinalAmount() + " 元");
        System.out.println();
    }

    static BillingTemplate getBillingTemplate() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.UNIT_BASED;
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
                new FreeMinuteAllocator(),
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
