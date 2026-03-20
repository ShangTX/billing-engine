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
 * 优惠功能测试
 * 专注测试优惠聚合、免费分钟数分配、免费时段合并等功能
 */
public class PromotionTest {

    public static void main(String[] args) {
        System.out.println("========== 优惠功能测试 ==========\n");

        // 测试1: 多个免费分钟数优惠叠加
        testMultipleFreeMinutes();

        // 测试2: 多个免费时段合并
        testFreeTimeRangeMerge();

        // 测试3: 规则级别 + 外部优惠组合
        testRuleAndExternalPromotions();

        // 测试4: 免费分钟数 + 免费时段组合
        testFreeMinutesAndRangeCombined();
    }

    /**
     * 测试1: 多个免费分钟数优惠叠加
     */
    static void testMultipleFreeMinutes() {
        System.out.println("=== 测试1: 多个免费分钟数优惠叠加 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-1");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部优惠: 30分钟 + 40分钟 = 70分钟免费
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("coupon-30")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(30)
                .build());
        externalPromotions.add(PromotionGrant.builder()
                .id("coupon-40")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(40)
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00 (4小时)");
        System.out.println("规则优惠: 30分钟免费");
        System.out.println("外部优惠: 30分钟 + 40分钟 = 70分钟免费");
        System.out.println("总免费分钟数: 100分钟");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试2: 多个免费时段合并
     */
    static void testFreeTimeRangeMerge() {
        System.out.println("=== 测试2: 多个免费时段合并 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-2");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部优惠: 两个重叠的免费时段 (01:00-04:00 和 03:00-07:00) 应合并为 01:00-07:00
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("range-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 1, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 4, 0, 0))
                .build());
        externalPromotions.add(PromotionGrant.builder()
                .id("range-2")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 3, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 7, 0, 0))
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 00:00 - 08:00 (8小时)");
        System.out.println("外部优惠: 免费时段 01:00-04:00 和 03:00-07:00");
        System.out.println("合并后: 免费时段 01:00-07:00 (6小时免费)");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试3: 规则级别 + 外部优惠组合
     */
    static void testRuleAndExternalPromotions() {
        System.out.println("=== 测试3: 规则级别 + 外部优惠组合 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-3");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 规则级别: 30分钟免费（在 getBillingService 中配置）
        // 外部优惠: 免费时间段 10:00-12:00
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("external-range")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0))
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("规则级别优惠: 30分钟免费");
        System.out.println("外部优惠: 免费时间段 10:00-12:00");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试4: 免费分钟数 + 免费时段组合
     */
    static void testFreeMinutesAndRangeCombined() {
        System.out.println("=== 测试4: 免费分钟数 + 免费时段组合 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-4");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 规则级别: 30分钟免费
        // 外部优惠: 60分钟免费 + 免费时间段 12:00-13:00
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("external-minutes")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(60)
                .build());
        externalPromotions.add(PromotionGrant.builder()
                .id("external-range")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 13, 0, 0))
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("规则级别优惠: 30分钟免费");
        System.out.println("外部优惠: 60分钟免费 + 免费时间段 12:00-13:00");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    static BillingService getBillingService() {
        var billingConfigResolver = new BillingConfigResolver() {

            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                // 白天: 12:20-19:00, 夜间: 19:00-次日12:20
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740)   // 12:20
                        .setDayEndMinute(1140)    // 19:00
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(null)
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
}