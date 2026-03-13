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
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费单元延伸测试
 *
 * 延伸规则：
 * 1. 最后一个计费单元被计费结束时间截断时，恢复到完整单元长度
 * 2. 不能超过下一个边界（时间段边界或周期边界）
 * 3. 收费金额不变
 */
public class BillingUnitExtensionTest {

    public static void main(String[] args) {
        System.out.println("========== 计费单元延伸测试 ==========\n");

        test1_ExtendToFullUnitLength();
        test2_ExtendToPeriodBoundary();
        test3_ExtendToCycleBoundary();
        test4_NoExtensionNeeded_AlreadyFull();
        test5_UnitBasedMode();
    }

    /**
     * 测试1: 截断单元延伸到完整长度
     * 计费时间 08:00-08:30，单元长度60分钟
     * 延伸后：08:00-09:00
     */
    static void test1_ExtendToFullUnitLength() {
        System.out.println("=== 测试1: 截断单元延伸到完整长度 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-1");
        // 计费时间: 08:00-08:30 (30分钟，被截断)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 30, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-08:30 (30分钟)");
        System.out.println("单元长度: 60分钟");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 09:00 (延伸到完整60分钟)");

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后一个单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("单元时长: " + lastUnit.getDurationMinutes() + "分钟");
        System.out.println("收费金额: " + result.getFinalAmount() + " (全额收费)");
        System.out.println();
    }

    /**
     * 测试2: 延伸不能超过时间段边界
     * 计费时间 08:00-09:30，单元长度60分钟，时间段边界在10:00
     * 最后一个单元：09:00-09:30，延伸后：09:00-10:00（不能超过边界）
     */
    static void test2_ExtendToPeriodBoundary() {
        System.out.println("=== 测试2: 延伸不能超过时间段边界 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-2");
        // 计费时间: 08:00-09:30 (90分钟)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 30, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:30 (90分钟)");
        System.out.println("单元长度: 60分钟");
        System.out.println("时间段边界: 10:00 (period1结束)");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 10:00 (延伸到时间段边界，不能超过)");

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后一个单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("单元时长: " + lastUnit.getDurationMinutes() + "分钟");
        System.out.println();
    }

    /**
     * 测试3: 延伸不能超过周期边界
     * 计费时间 08:00-次日07:30，最后一个单元被截断
     * 延伸后：不能超过周期边界 08:00
     */
    static void test3_ExtendToCycleBoundary() {
        System.out.println("=== 测试3: 延伸不能超过周期边界 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-3");
        // 计费时间: 08:00-次日07:30 (23.5小时)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 11, 7, 30, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-次日07:30 (23.5小时)");
        System.out.println("单元长度: 60分钟");
        System.out.println("周期边界: 次日08:00");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 次日08:00 (延伸到周期边界)");

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后一个单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("单元时长: " + lastUnit.getDurationMinutes() + "分钟");
        System.out.println();
    }

    /**
     * 测试4: 单元完整不延伸
     * 计费时间 08:00-09:00，单元长度60分钟
     * 单元本身就是完整的，不需要延伸
     */
    static void test4_NoExtensionNeeded_AlreadyFull() {
        System.out.println("=== 测试4: 单元完整不延伸 ===");

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("test-4");
        // 计费时间: 08:00-09:00 (恰好60分钟)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-09:00 (恰好60分钟)");
        System.out.println("单元长度: 60分钟");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 09:00 (单元完整，无需延伸)");

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后一个单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("单元时长: " + lastUnit.getDurationMinutes() + "分钟");
        System.out.println();
    }

    /**
     * 测试5: UNIT_BASED 模式下的延伸
     */
    static void test5_UnitBasedMode() {
        System.out.println("=== 测试5: UNIT_BASED 模式下的延伸 ===");

        var billingService = getBillingServiceUnitBased();
        var request = new BillingRequest();
        // 计费时间: 08:00-08:30 (30分钟，被截断)
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 30, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00-08:30 (30分钟)");
        System.out.println("模式: UNIT_BASED");
        System.out.println();
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());
        System.out.println("预期: 09:00 (延伸到完整单元长度)");

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("最后一个单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("单元时长: " + lastUnit.getDurationMinutes() + "分钟");
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
                        .maxChargeOneCycle(new BigDecimal("100")) // 设置封顶金额
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
                        .maxChargeOneCycle(new BigDecimal("100")) // 设置封顶金额
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