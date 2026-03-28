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
import cn.shang.charging.util.JacksonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class DebugTest {
    public static void main(String[] args) {
        System.out.println("========== UNIT_BASED 模式封顶测试 ==========\n");
        
        // 24小时 + 5秒
        testCase("24小时+5秒 (UNIT_BASED)", 
            LocalDateTime.of(2026, 3, 26, 15, 37, 14),
            LocalDateTime.of(2026, 3, 27, 15, 37, 19));
    }
    
    static void testCase(String name, LocalDateTime begin, LocalDateTime end) {
        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("debug-" + name);
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        var result = billingService.calculate(request);
        
        long minutes = java.time.Duration.between(begin, end).toMinutes();
        long seconds = java.time.Duration.between(begin, end).getSeconds() % 60;
        
        System.out.println("【" + name + "】");
        System.out.println("  时间: " + begin + " - " + end);
        System.out.println("  时长: " + minutes + "分" + seconds + "秒");
        System.out.println("  单价: 2元/小时, 封顶: 20元/天");
        System.out.println("  预期: 第1天封顶20元, 第2天按实际收费 (约2元)");
        System.out.println();
        System.out.println("  结果: " + result.getFinalAmount() + " 元");
        System.out.println("  单元数: " + result.getUnits().size());
        System.out.println();
        System.out.println("  单元明细:");
        for (int i = 0; i < result.getUnits().size(); i++) {
            var unit = result.getUnits().get(i);
            System.out.printf("    #%d %s - %s (%dm) 单价:%s 金额:%s 收费:%s%s\n",
                i + 1, 
                unit.getBeginTime().toLocalTime(), 
                unit.getEndTime().toLocalTime(),
                unit.getDurationMinutes(),
                unit.getUnitPrice(),
                unit.getOriginalAmount(),
                unit.getChargedAmount(),
                unit.isFree() ? " [免费:" + unit.getFreePromotionId() + "]" : "");
        }
    }

    static BillingService getBillingService() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.UNIT_BASED;  // UNIT_BASED 模式
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-debug")
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

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
