package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.RuleResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.charge.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.ranges.FreeTimeRangePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 相对时间段计费测试
 */
public class RelativeTimeTest {

    public static void main(String[] args) {
        System.out.println("========== 相对时间段计费测试 ==========\n");

        // 测试1: 单周期计费（带规则级别免费分钟数）
        testSingleCycle();

        // 测试2: 跨周期计费（带规则级别免费分钟数）
        testCrossCycle();

        // 测试3: 封顶逻辑
        testCap();

        // 测试4: 免费时段优惠（外部优惠）
        testFreeTimeRangeFromExternal();

        // 测试5: 不足一个单元收全额
        testPartialUnit();

        // 测试6: 免费分钟数优惠（规则级别 + 外部优惠）
        testFreeMinutes();

        // 测试7: 复合优惠（规则级别免费分钟数 + 外部免费时间段 + 外部免费分钟数）
        testCompoundPromotion();
    }

    /**
     * 测试1: 单周期计费（带规则级别免费分钟数）
     */
    static void testSingleCycle() {
        System.out.println("=== 测试1: 单周期计费 ===");

        var billingService = getBillingService();
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
        System.out.println("配置:");
        System.out.println("  Period 1: 0-120分钟 (前2小时), 单元30分钟, 单价1元");
        System.out.println("  Period 2: 120-1440分钟 (后22小时), 单元60分钟, 单价2元");
        System.out.println("优惠规则: 免费分钟数30分钟（规则级别）");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());

        // 原始: 4×1 + 4×2 = 12元
        // 规则优惠: 30分钟免费，抵扣1个单元
        // 最终: 11元
        System.out.println("预期: 11.00元 (原始12元 - 规则优惠30分钟=1元)");
        System.out.println(JacksonUtils.toJsonString(result));
    }

    /**
     * 测试2: 跨周期计费（带规则级别免费分钟数）
     */
    static void testCrossCycle() {
        System.out.println("=== 测试2: 跨周期计费 ===");

        var billingService = getBillingService();
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
        System.out.println("预期: 51.00元 (原始52元 - 规则优惠30分钟=1元)");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试3: 封顶逻辑（从最后一个单元开始削减）
     */
    static void testCap() {
        System.out.println("=== 测试3: 封顶逻辑 ===");

        var billingService = getBillingServiceWithCap();
        var request = new BillingRequest();
        request.setId("test-3");
        // 计费时间: 08:00 - 12:00 (4小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00 (4小时)");
        System.out.println("每周期封顶: 5元");
        System.out.println("优惠规则: 免费分钟数30分钟（规则级别）");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("预期: 5.00元 (封顶)");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试4: 免费时段优惠（外部优惠）
     */
    static void testFreeTimeRangeFromExternal() {
        System.out.println("=== 测试4: 免费时段优惠（外部优惠）===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-4");
        // 计费时间: 08:00 - 14:00 (6小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部优惠: 免费时间段 09:00 - 11:00
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("external-free-range")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("优惠规则: 免费分钟数30分钟（规则级别）");
        System.out.println("外部优惠: 免费时间段 09:00 - 11:00");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());

        System.out.println();
        System.out.println("--- 详细结果 ---");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试5: 不足一个单元收全额
     */
    static void testPartialUnit() {
        System.out.println("=== 测试5: 不足一个单元收全额 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-5");
        // 计费时间: 08:00 - 08:15 (15分钟，不足30分钟单元)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 15, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 08:15 (15分钟)");
        System.out.println("配置: Period 1 单元30分钟, 单价1元");
        System.out.println("优惠规则: 免费分钟数30分钟（规则级别）");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());

        // 15分钟不足30分钟单元，但收全额1元
        // 规则优惠30分钟 >= 15分钟，所以免费
        System.out.println("预期: 0.00元 (规则优惠30分钟可覆盖)");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试6: 免费分钟数优惠（规则级别 + 外部优惠）
     */
    static void testFreeMinutes() {
        System.out.println("=== 测试6: 免费分钟数优惠（规则级别 + 外部优惠）===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-6");
        // 计费时间: 08:00 - 12:00 (4小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部优惠: 60分钟免费
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("external-free-min")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(60)
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 12:00 (4小时)");
        System.out.println("优惠规则: 免费分钟数30分钟（规则级别）");
        System.out.println("外部优惠: 免费分钟数60分钟");
        System.out.println("总免费分钟数: 90分钟");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());

        System.out.println();
        System.out.println("--- 详细结果 ---");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 测试7: 复合优惠（规则级别免费分钟数 + 外部免费时间段 + 外部免费分钟数）
     */
    static void testCompoundPromotion() {
        System.out.println("=== 测试7: 复合优惠（规则级别 + 外部优惠）===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-7");
        // 计费时间: 08:00 - 14:00 (6小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部优惠: 免费分钟数 + 免费时间段
        List<PromotionGrant> externalPromotions = new ArrayList<>();

        // 外部免费分钟数: 60分钟
        externalPromotions.add(PromotionGrant.builder()
                .id("external-free-min")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(60)
                .build());

        // 外部免费时间段: 12:00 - 13:00
        externalPromotions.add(PromotionGrant.builder()
                .id("external-free-range")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 13, 0, 0))
                .build());

        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("优惠规则（规则级别）:");
        System.out.println("  - 免费分钟数: 30分钟");
        System.out.println("外部优惠:");
        System.out.println("  - 免费分钟数: 60分钟");
        System.out.println("  - 免费时间段: 12:00 - 13:00");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());

        System.out.println();
        System.out.println("--- 详细结果 ---");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();
    }

    /**
     * 基础服务（带免费分钟数规则）
     */
    static BillingService getBillingService() {
        var ruleResolver = new RuleResolver() {
            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(120)
                                .unitMinutes(30)
                                .unitPrice(new BigDecimal("1"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(120)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-1")
                        .periods(periods)
                        .maxChargeOneCycle(null)
                        .build();
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
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_TIME_RANGE, new FreeTimeRangePromotionRule());
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                ruleResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                ruleResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    /**
     * 带封顶的服务（带免费分钟数规则）
     */
    static BillingService getBillingServiceWithCap() {
        var ruleResolver = new RuleResolver() {
            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(120)
                                .unitMinutes(30)
                                .unitPrice(new BigDecimal("1"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(120)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-2")
                        .periods(periods)
                        .maxChargeOneCycle(new BigDecimal("5"))
                        .build();
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
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_TIME_RANGE, new FreeTimeRangePromotionRule());
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                ruleResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                ruleResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}