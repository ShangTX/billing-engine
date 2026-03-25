package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 气泡型免费时间段测试
 */
public class BubbleFreeTimeRangeTest {

    private static BillingService billingService;

    public static void main(String[] args) {
        System.out.println("========== 气泡型免费时间段测试 ==========\n");

        initBillingService();

        // 测试1: 单气泡延长周期边界
        testSingleBubbleExtension();

        // 测试2: 跨计算段气泡累积延长
        testCrossCalculationBubbleExtension();

        // 测试3: 气泡型与普通型混合
        testMixedBubbleAndNormal();

        // 测试4: 多气泡场景
        testMultipleBubbles();

        System.out.println("\n========== 所有测试完成 ==========");
    }

    static void initBillingService() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)   // 08:00
                        .setDayEndMinute(1200)    // 20:00
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("50"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return List.of();
            }

            @Override
            public int getSimplifiedCycleThreshold() {
                return 7;
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

        billingService = new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    /**
     * 测试1: 单气泡延长周期边界
     *
     * 气泡型免费时段：11:00-12:00（60分钟）
     * 计费起点：08:00
     * 预期：周期边界从次日 08:00 延长到次日 09:00
     */
    static void testSingleBubbleExtension() {
        System.out.println("=== 测试1: 单气泡延长周期边界 ===\n");

        var request = new BillingRequest();
        request.setId("test-bubble-1");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 气泡型免费时段 11:00-12:00
        List<PromotionGrant> promotions = new ArrayList<>();
        promotions.add(PromotionGrant.builder()
                .id("bubble-60")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00");
        System.out.println("气泡型免费时段: 11:00-12:00（60分钟）");
        System.out.println("预期周期边界: 次日 09:00（延长60分钟）");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());

        // 检查 cycleBoundary
        var ruleState = result.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState = (java.util.Map<String, Object>) ruleState.get("dayNight");
        var cycleBoundary = dayNightState.get("cycleBoundary");
        System.out.println("输出 cycleBoundary: " + cycleBoundary);

        // 验证：周期边界应该是次日 09:00
        LocalDateTime expectedBoundary = LocalDateTime.of(2026, Month.MARCH, 11, 9, 0, 0);
        boolean passed = cycleBoundary.toString().equals(expectedBoundary.toString());
        System.out.println("测试" + (passed ? "通过" : "失败"));
        System.out.println();
    }

    /**
     * 测试2: 跨计算段气泡累积延长
     *
     * 气泡型免费时段：11:00-13:00（120分钟）
     * 第一次计算：08:00-12:00，使用 11:00-12:00（60分钟）
     * 第二次计算：12:00-18:00，使用 12:00-13:00（60分钟）
     * 预期：第一次延长到次日 09:00，第二次延长到次日 10:00
     */
    static void testCrossCalculationBubbleExtension() {
        System.out.println("=== 测试2: 跨计算段气泡累积延长 ===\n");

        // 第一次计算
        var request1 = new BillingRequest();
        request1.setId("test-bubble-2-1");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");

        List<PromotionGrant> promotions1 = new ArrayList<>();
        promotions1.add(PromotionGrant.builder()
                .id("bubble-120")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 13, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        request1.setExternalPromotions(promotions1);

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算 (08:00-12:00):");
        System.out.println("  气泡型免费时段: 11:00-13:00");
        System.out.println("  本次使用: 11:00-12:00（60分钟）");

        var ruleState1 = result1.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState1 = (java.util.Map<String, Object>) ruleState1.get("dayNight");
        System.out.println("  输出 cycleBoundary: " + dayNightState1.get("cycleBoundary"));

        // 第二次计算（CONTINUE）
        var request2 = new BillingRequest();
        request2.setId("test-bubble-2-2");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 18, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setPreviousCarryOver(result1.getCarryOver());
        request2.setExternalPromotions(promotions1);

        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算 (CONTINUE, 12:00-18:00):");
        System.out.println("  本次使用: 12:00-13:00（60分钟）");

        var ruleState2 = result2.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState2 = (java.util.Map<String, Object>) ruleState2.get("dayNight");
        System.out.println("  输出 cycleBoundary: " + dayNightState2.get("cycleBoundary"));

        // 验证：第二次周期边界应该是次日 10:00
        LocalDateTime expectedBoundary = LocalDateTime.of(2026, Month.MARCH, 11, 10, 0, 0);
        boolean passed = dayNightState2.get("cycleBoundary").toString().equals(expectedBoundary.toString());
        System.out.println("测试" + (passed ? "通过" : "失败"));
        System.out.println();
    }

    /**
     * 测试3: 气泡型与普通型混合
     */
    static void testMixedBubbleAndNormal() {
        System.out.println("=== 测试3: 气泡型与普通型混合 ===\n");

        var request = new BillingRequest();
        request.setId("test-bubble-3");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 16, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        List<PromotionGrant> promotions = new ArrayList<>();
        // 普通免费时段：09:00-10:00（不影响周期）
        promotions.add(PromotionGrant.builder()
                .id("normal-60")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .rangeType(FreeTimeRangeType.NORMAL)
                .build());
        // 气泡型免费时段：13:00-14:00（延长周期60分钟）
        promotions.add(PromotionGrant.builder()
                .id("bubble-60")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 13, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 16:00");
        System.out.println("普通免费时段: 09:00-10:00（不影响周期）");
        System.out.println("气泡型免费时段: 13:00-14:00（延长周期60分钟）");
        System.out.println();

        var ruleState = result.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState = (java.util.Map<String, Object>) ruleState.get("dayNight");
        System.out.println("输出 cycleBoundary: " + dayNightState.get("cycleBoundary"));

        // 验证：周期边界应该是次日 09:00（只有气泡延长60分钟）
        LocalDateTime expectedBoundary = LocalDateTime.of(2026, Month.MARCH, 11, 9, 0, 0);
        boolean passed = dayNightState.get("cycleBoundary").toString().equals(expectedBoundary.toString());
        System.out.println("测试" + (passed ? "通过" : "失败"));
        System.out.println();
    }

    /**
     * 测试4: 多气泡场景
     */
    static void testMultipleBubbles() {
        System.out.println("=== 测试4: 多气泡场景 ===\n");

        var request = new BillingRequest();
        request.setId("test-bubble-4");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 18, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        List<PromotionGrant> promotions = new ArrayList<>();
        // 气泡1：10:00-11:00（60分钟）
        promotions.add(PromotionGrant.builder()
                .id("bubble-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        // 气泡2：14:00-15:00（60分钟）
        promotions.add(PromotionGrant.builder()
                .id("bubble-2")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 15, 0, 0))
                .rangeType(FreeTimeRangeType.BUBBLE)
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 18:00");
        System.out.println("气泡1: 10:00-11:00（60分钟）");
        System.out.println("气泡2: 14:00-15:00（60分钟）");
        System.out.println("预期周期延长: 120分钟");
        System.out.println();

        var ruleState = result.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState = (java.util.Map<String, Object>) ruleState.get("dayNight");
        System.out.println("输出 cycleBoundary: " + dayNightState.get("cycleBoundary"));

        // 验证：周期边界应该是次日 10:00（延长120分钟）
        LocalDateTime expectedBoundary = LocalDateTime.of(2026, Month.MARCH, 11, 10, 0, 0);
        boolean passed = dayNightState.get("cycleBoundary").toString().equals(expectedBoundary.toString());
        System.out.println("测试" + (passed ? "通过" : "失败"));
        System.out.println();
    }
}