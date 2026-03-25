package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 日夜分时段计费测试
 */
public class DayNightTest {

    public static void main(String[] args) {
        System.out.println("========== 日夜分时段计费测试 ==========\n");

        // 测试1: 单周期计费（带规则级别免费分钟数）
        testSingleCycle();

        // 测试2: 跨周期计费
        testCrossCycle();

        // 测试3: 封顶逻辑
        testCap();

        // 测试4: 免费时段优惠
        testFreeTimeRange();

        // 测试5: 不足一个单元收全额
        testPartialUnit();

        // 测试6: CONTINUOUS vs UNIT_BASED 模式对比
        testModeComparison();
    }

    /**
     * 测试1: 单周期计费（带规则级别免费分钟数）
     */
    static void testSingleCycle() {
        System.out.println("=== 测试1: 单周期计费 ===");

        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-1");
        // 计费时间: 08:00 - 14:00 (6小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("配置: 白天 12:20-19:00, 单价2元; 夜间 单价1元; 单元60分钟");
        System.out.println("优惠规则: 免费分钟数30分钟（规则级别）");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试2: 跨周期计费
     */
    static void testCrossCycle() {
        System.out.println("=== 测试2: 跨周期计费 ===");

        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-2");
        // 计费时间: 08:00 - 次日 10:00 (26小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 11, 10, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 次日 10:00 (26小时)");
        System.out.println("优惠规则: 免费分钟数30分钟（规则级别）");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试3: 封顶逻辑
     */
    static void testCap() {
        System.out.println("=== 测试3: 封顶逻辑 ===");

        var billingService = getBillingServiceWithCap(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-3");
        // 计费时间: 08:00 - 20:00 (12小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 20, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 20:00 (12小时)");
        System.out.println("每周期封顶: 10元");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 10.00元 (封顶)");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试4: 免费时段优惠
     */
    static void testFreeTimeRange() {
        System.out.println("=== 测试4: 免费时段优惠 ===");

        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-4");
        // 计费时间: 08:00 - 14:00 (6小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部优惠: 免费时间段 10:00 - 12:00
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("external-free-range")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0))
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("外部优惠: 免费时间段 10:00 - 12:00");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试5: 不足一个单元收全额
     */
    static void testPartialUnit() {
        System.out.println("=== 测试5: 不足一个单元收全额 ===");

        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        request.setId("test-5");
        // 计费时间: 08:00 - 08:30 (30分钟，不足60分钟单元)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 30, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 08:30 (30分钟)");
        System.out.println("配置: 单元60分钟, 夜间单价1元");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 1.00元 (不足单元收全额)");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试6: CONTINUOUS vs UNIT_BASED 模式对比
     */
    static void testModeComparison() {
        System.out.println("=== 测试6: CONTINUOUS vs UNIT_BASED 模式对比 ===");

        // 场景：停车 05:00 - 08:15，单元60分钟，免费时段 06:30-07:30
        // CONTINUOUS 预期：6元
        // UNIT_BASED 预期：8元

        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("free-range")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 6, 30, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 7, 30, 0))
                .build());

        // CONTINUOUS 模式
        var billingServiceContinuous = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var requestContinuous = new BillingRequest();
        requestContinuous.setId("test-continuous");
        requestContinuous.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 5, 0, 0));
        requestContinuous.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 15, 0));
        requestContinuous.setSchemeChanges(List.of());
        requestContinuous.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        requestContinuous.setSchemeId("scheme-1");
        requestContinuous.setExternalPromotions(externalPromotions);

        var resultContinuous = billingServiceContinuous.calculate(requestContinuous);

        System.out.println("场景: 停车 05:00 - 08:15，单元60分钟，免费时段 06:30-07:30");
        System.out.println();
        System.out.println("CONTINUOUS 模式:");
        System.out.println("  结果: finalAmount = " + resultContinuous.getFinalAmount());
        System.out.println("  预期: 6元 (05:00-06:00=2元, 06:00-06:30=2元, 06:30-07:30=免费, 07:30-08:15=2元)");
        System.out.println();

        // UNIT_BASED 模式
        var billingServiceUnitBased = getBillingService(BConstants.BillingMode.UNIT_BASED);
        var requestUnitBased = new BillingRequest();
        requestUnitBased.setId("test-unit-based");
        requestUnitBased.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 5, 0, 0));
        requestUnitBased.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 15, 0));
        requestUnitBased.setSchemeChanges(List.of());
        requestUnitBased.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        requestUnitBased.setSchemeId("scheme-1");
        requestUnitBased.setExternalPromotions(externalPromotions);

        var resultUnitBased = billingServiceUnitBased.calculate(requestUnitBased);

        System.out.println("UNIT_BASED 模式:");
        System.out.println("  结果: finalAmount = " + resultUnitBased.getFinalAmount());
        System.out.println("  预期: 8元 (免费时段未完全覆盖整个计费单元，按全额收取)");
        System.out.println();

        System.out.println("--- CONTINUOUS 模式详细结果 ---");
        System.out.println(JacksonUtils.toJsonString(resultContinuous));
        System.out.println();

        System.out.println("--- UNIT_BASED 模式详细结果 ---");
        System.out.println(JacksonUtils.toJsonString(resultUnitBased));
        System.out.println();
    }

    /**
     * 基础服务（带免费分钟数规则）
     */
    static BillingService getBillingService(BConstants.BillingMode billingMode) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return billingMode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                // 白天: 12:20-19:00 (740-1140分钟)
                // 夜间: 19:00-次日12:20
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740)   // 12:20
                        .setDayEndMinute(1140)    // 19:00
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("100")) // 封顶金额必填
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                // 规则级别优惠: 免费分钟数30分钟
                return List.of(
                        new FreeMinutesPromotionConfig()
                                .setId("rule-free-min")
                                .setPriority(1)
                                .setMinutes(30)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

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

    /**
     * 带封顶的服务
     */
    static BillingService getBillingServiceWithCap(BConstants.BillingMode billingMode) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return billingMode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new DayNightConfig()
                        .setId("daynight-2")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740)
                        .setDayEndMinute(1140)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("10"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return List.of(
                        new FreeMinutesPromotionConfig()
                                .setId("rule-free-min")
                                .setPriority(1)
                                .setMinutes(30)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

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