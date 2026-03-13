package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费单元延伸测试
 */
public class BillingUnitExtensionTest {

    public static void main(String[] args) {
        System.out.println("========== 计费单元延伸测试 ==========\n");

        test1_ExtendToPeriodBoundary();
        test2_ExtendToCycleBoundary();
        test3_NoExtensionNeeded_BoundaryEqualsEnd();
        test4_FreeTimeRangeExtension();
        test5_UnitBasedMode();
    }

    /**
     * 测试1: 延伸到时间段边界
     */
    static void test1_ExtendToPeriodBoundary() {
        System.out.println("=== 测试1: 延伸到时间段边界 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-1");
        // 计费时间: 08:00-09:00 (1小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("配置: period1 = 0-120分钟, period2 = 120-1440分钟");
        System.out.println("下一个时间段边界: 10:00 (120分钟边界)");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 10:00");
        System.out.println("最后一个单元: " + result.getUnits().get(result.getUnits().size() - 1).getBeginTime() +
                " - " + result.getUnits().get(result.getUnits().size() - 1).getEndTime());
        System.out.println("收费金额: " + result.getFinalAmount());
        System.out.println();
    }

    /**
     * 测试2: 单元完整不延伸（边界恰好等于结束时间）
     */
    static void test2_ExtendToCycleBoundary() {
        System.out.println("=== 测试2: 无时间段边界，延伸到周期边界 ===");

        // 使用没有时间段边界限制的配置
        var billingService = getBillingServiceNoPeriodBoundary();
        var request = new BillingRequest();
        request.setId("test-2");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("配置: 无时间段边界，只有周期边界");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 次日 08:00（24小时周期边界）");
        System.out.println();
    }

    /**
     * 测试3: 边界恰好等于结束时间，不延伸
     * 对应 spec 测试3: 单元长度60分钟，计费结束时间09:00，下一个边界也是09:00
     */
    static void test3_NoExtensionNeeded_BoundaryEqualsEnd() {
        System.out.println("=== 测试3: 边界恰好等于结束时间，不延伸 ===");

        // 配置: period1 = 0-60分钟 (边界在09:00), period2 = 60-1440分钟
        var billingService = getBillingServiceWithBoundaryAt9();
        var request = new BillingRequest();
        // 计费时间: 08:00-09:00 (恰好到边界)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("配置: period1 = 0-60分钟 (边界在09:00)");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 次日 08:00（延伸到周期边界，因为时间段边界恰好等于结束时间时查找下一个边界）");
        System.out.println("最后一个单元结束时间: " + result.getUnits().get(result.getUnits().size() - 1).getEndTime());
        System.out.println();
    }

    /**
     * 测试4: 免费时段覆盖的单元延伸
     */
    static void test4_FreeTimeRangeExtension() {
        System.out.println("=== 测试4: 免费时段覆盖的单元延伸 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 免费时段: 08:00-10:00
        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("free-range-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .build());
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("免费时段: 08:00-10:00");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 10:00");
        System.out.println("收费金额: " + result.getFinalAmount() + " (免费)");
        System.out.println();
    }

    /**
     * 测试5: UNIT_BASED 模式下的延伸
     */
    static void test5_UnitBasedMode() {
        System.out.println("=== 测试5: UNIT_BASED 模式下的延伸 ===");

        var billingService = getBillingServiceUnitBased();
        var request = new BillingRequest();
        // 计费时间: 08:00-09:00 (1小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00");
        System.out.println("模式: UNIT_BASED");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 10:00 (延伸到时间段边界)");
        System.out.println("最后一个单元: " + result.getUnits().get(result.getUnits().size() - 1).getBeginTime() +
                " - " + result.getUnits().get(result.getUnits().size() - 1).getEndTime());
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
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(120)
                                .unitMinutes(60)
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
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
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
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static BillingService getBillingServiceNoPeriodBoundary() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                // 只有一个时间段，覆盖整个周期
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("1"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-2")
                        .periods(periods)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
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
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static BillingService getBillingServiceWithBoundaryAt9() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                // period1 = 0-60分钟 (边界在 09:00), period2 = 60-1440分钟
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(60)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("1"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(60)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-3")
                        .periods(periods)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
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
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static BillingService getBillingServiceUnitBased() {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.UNIT_BASED;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(120)
                                .unitMinutes(60)
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
                        .id("relative-time-unit-based")
                        .periods(periods)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
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
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}