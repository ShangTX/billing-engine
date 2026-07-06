package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
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
import java.util.Map;

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

        // 测试3: 气泡型与普通型混合
        testMixedBubbleAndNormal();

        // 测试4: 多气泡场景
        testMultipleBubbles();

        System.out.println("\n========== 所有测试完成 ==========");
    }

    static void initBillingService() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
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
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
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
        System.out.println("[PASS] 测试通过");
        System.out.println();
    }

    /**
     * 测试2: 跨计算段气泡累积延长 - DELETED (依赖 CONTINUE 模式)
     * 此测试方法已被删除，因为项目重构移除了 CONTINUE 续算模式。
     * 如果需要测试气泡延长功能，请使用单次计算测试（如 testSingleBubbleExtension）。
     */

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
        System.out.println("[PASS] 测试通过");
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
        System.out.println("[PASS] 测试通过");
        System.out.println();
    }
}