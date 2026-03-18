package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.ranges.FreeTimeRangePromotionRule;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * boundaryReferences 功能测试
 *
 * 测试场景：延伸与窗口外优惠的交互
 *
 * 核心问题：
 * - 免费时段在计算窗口外，但延伸区域会进入
 * - 延伸应该停在优惠边界，不"闯入"未处理的优惠区域
 *
 * 典型场景：
 * - 免费时段：09:20-09:50
 * - 计算窗口：07:30-09:00
 * - 计费单元长度：60分钟
 * - 最后单元：08:30-09:00 应该延伸到 09:20（停在免费时段边界）
 */
public class BoundaryReferencesTest {

    static LocalDateTime BASE_DATE = LocalDateTime.of(2026, 3, 10, 0, 0);
    static DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public static void main(String[] args) {
        System.out.println("========== boundaryReferences 功能测试 ==========\n");

        testBoundaryReference_BasicScenario();
        testBoundaryReference_MultipleFreeRanges();
        testBoundaryReference_WithContinue();
        testBoundaryReference_NoConflict();

        System.out.println("\n========== 测试完成 ==========\n");
    }

    /**
     * 场景1：基础场景 - 延伸停在窗口外免费时段边界
     *
     * 典型场景验证：
     * - 免费时段：09:20-09:50（在计算窗口外）
     * - 计算窗口：07:30-09:00
     * - 计费单元长度：60分钟
     * - 最后单元：08:30-09:00
     *
     * 预期行为：
     * - 延伸到 09:20（停在免费时段边界）
     * - calculationEndTime = 09:20
     * - 免费时段不被消耗（usedFreeRanges 为空）
     */
    static void testBoundaryReference_BasicScenario() {
        System.out.println("=== 场景1: 延伸停在窗口外免费时段边界 ===\n");

        var billingService = createBillingService(new BigDecimal("100"));

        // 外部优惠: 免费时段 09:20-09:50（在计算窗口外）
        var freeRange = PromotionGrant.builder()
                .id("free-range-outside")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("09:20"))
                .endTime(parseTime("09:50"))
                .build();

        // 计算窗口: 07:30-09:00
        var request = createRequest("07:30", "09:00");
        request.setExternalPromotions(List.of(freeRange));
        var result = billingService.calculate(request);

        System.out.println("计算窗口: 07:30 - 09:00");
        System.out.println("免费时段: 09:20 - 09:50（在窗口外）");
        System.out.println("计费单元长度: 60分钟");
        System.out.println();
        System.out.println("结果金额: " + result.getFinalAmount());
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());

        // 验证计费单元
        System.out.println("\n计费单元:");
        for (var unit : result.getUnits()) {
            System.out.printf("  %s - %s (%d分钟) 金额:%s 免费:%s%n",
                    unit.getBeginTime().format(TIME_FORMAT),
                    unit.getEndTime().format(TIME_FORMAT),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount(),
                    unit.isFree());
        }

        // 验证最后单元延伸情况
        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("\n验证:");
        System.out.println("  最后单元: " + lastUnit.getBeginTime().format(TIME_FORMAT) + " - " + lastUnit.getEndTime().format(TIME_FORMAT));

        boolean extendedCorrectly = lastUnit.getEndTime().equals(parseTime("09:20"));
        System.out.println("  预期延伸到: 09:20（停在免费时段边界）");
        System.out.println("  实际延伸到: " + lastUnit.getEndTime().format(TIME_FORMAT));
        System.out.println("  延伸正确: " + (extendedCorrectly ? "✓" : "✗"));

        // 验证优惠未消耗
        var carryOver = result.getCarryOver();
        if (carryOver != null && carryOver.getSegments() != null) {
            var segmentCarryOver = carryOver.getSegments().values().iterator().next();
            if (segmentCarryOver.getPromotionState() != null) {
                var usedRanges = segmentCarryOver.getPromotionState().getUsedFreeRanges();
                System.out.println("  已使用免费时段: " + usedRanges);
                System.out.println("  优惠未消耗: " + (usedRanges == null || usedRanges.isEmpty() ? "✓" : "✗"));
            }
        }

        System.out.println();
    }

    /**
     * 场景2：多个窗口外免费时段 - 延伸停在最近的边界
     *
     * 场景设置：
     * - 免费时段：09:20-09:50, 10:00-11:00（都在窗口外）
     * - 计算窗口：07:30-09:00
     *
     * 预期行为：
     * - 延伸到 09:20（最近的边界）
     */
    static void testBoundaryReference_MultipleFreeRanges() {
        System.out.println("=== 场景2: 多个窗口外免费时段，延伸停最近边界 ===\n");

        var billingService = createBillingService(new BigDecimal("100"));

        // 外部优惠: 多个免费时段
        var freeRange1 = PromotionGrant.builder()
                .id("free-range-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("09:20"))
                .endTime(parseTime("09:50"))
                .build();

        var freeRange2 = PromotionGrant.builder()
                .id("free-range-2")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("10:00"))
                .endTime(parseTime("11:00"))
                .build();

        var request = createRequest("07:30", "09:00");
        request.setExternalPromotions(List.of(freeRange1, freeRange2));
        var result = billingService.calculate(request);

        System.out.println("计算窗口: 07:30 - 09:00");
        System.out.println("免费时段1: 09:20 - 09:50");
        System.out.println("免费时段2: 10:00 - 11:00");
        System.out.println();
        System.out.println("结果金额: " + result.getFinalAmount());
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("\n验证:");
        System.out.println("  最后单元: " + lastUnit.getBeginTime().format(TIME_FORMAT) + " - " + lastUnit.getEndTime().format(TIME_FORMAT));
        System.out.println("  预期延伸到: 09:20（最近的边界）");

        boolean extendedCorrectly = lastUnit.getEndTime().equals(parseTime("09:20"));
        System.out.println("  延伸正确: " + (extendedCorrectly ? "✓" : "✗"));

        System.out.println();
    }

    /**
     * 场景3：延伸后继续计算 - 验证优惠仍然可用
     *
     * 场景设置：
     * - 第一次计算：07:30-09:00，免费时段 09:20-09:50
     * - 第二次计算：继续到 10:00
     *
     * 预期行为：
     * - 第一次延伸到 09:20
     * - 第二次从 09:00 继续（不是 09:20，因为上次只计算到 09:00）
     * - 免费时段 09:20-09:50 仍然有效
     */
    static void testBoundaryReference_WithContinue() {
        System.out.println("=== 场景3: 延伸后继续计算，优惠仍然可用 ===\n");

        var billingService = createBillingService(new BigDecimal("100"));

        var freeRange = PromotionGrant.builder()
                .id("free-range-continue")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("09:20"))
                .endTime(parseTime("09:50"))
                .build();

        // 第一次计算: 07:30 - 09:00
        var request1 = createRequest("07:30", "09:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 07:30 - 09:00");
        System.out.println("  免费时段: 09:20 - 09:50");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());

        var lastUnit1 = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元: " + lastUnit1.getEndTime().format(TIME_FORMAT));

        // 第二次计算: 继续 09:00 - 10:00
        var request2 = createRequest("07:30", "10:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 09:00 - 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  calculationEndTime: " + result2.getCalculationEndTime());

        // 验证免费时段是否被使用
        System.out.println("\n计费单元:");
        for (var unit : result2.getUnits()) {
            System.out.printf("  %s - %s 金额:%s 免费:%s%n",
                    unit.getBeginTime().format(TIME_FORMAT),
                    unit.getEndTime().format(TIME_FORMAT),
                    unit.getChargedAmount(),
                    unit.isFree() ? "是(" + unit.getFreePromotionId() + ")" : "否");
        }

        // 验证 09:20-09:50 是否免费
        boolean hasFreeUnitInRange = result2.getUnits().stream()
                .anyMatch(u -> u.isFree() && u.getFreePromotionId() != null &&
                        u.getFreePromotionId().contains("free-range"));

        System.out.println("\n验证:");
        System.out.println("  免费时段 09:20-09:50 被正确使用: " + (hasFreeUnitInRange ? "✓" : "✗"));

        System.out.println();
    }

    /**
     * 场景4：无冲突 - 延伸区域无优惠
     *
     * 场景设置：
     * - 免费时段：11:00-12:00（延伸区域外）
     * - 计算窗口：07:30-09:00
     *
     * 预期行为：
     * - 延伸到 09:30（完整单元长度，停在周期边界或达到完整长度）
     */
    static void testBoundaryReference_NoConflict() {
        System.out.println("=== 场景4: 延伸区域无优惠，正常延伸 ===\n");

        var billingService = createBillingService(new BigDecimal("100"));

        var freeRange = PromotionGrant.builder()
                .id("free-range-far")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("11:00"))
                .endTime(parseTime("12:00"))
                .build();

        var request = createRequest("07:30", "09:00");
        request.setExternalPromotions(List.of(freeRange));
        var result = billingService.calculate(request);

        System.out.println("计算窗口: 07:30 - 09:00");
        System.out.println("免费时段: 11:00 - 12:00（延伸区域外）");
        System.out.println();
        System.out.println("结果金额: " + result.getFinalAmount());
        System.out.println("calculationEndTime: " + result.getCalculationEndTime());

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("\n验证:");
        System.out.println("  最后单元: " + lastUnit.getBeginTime().format(TIME_FORMAT) + " - " + lastUnit.getEndTime().format(TIME_FORMAT));
        System.out.println("  预期: 延伸到完整单元长度，不受优惠影响");

        System.out.println();
    }

    // ==================== 辅助方法 ====================

    static BillingService createBillingService(BigDecimal maxCharge) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.UNIT_BASED;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return RelativeTimeConfig.builder()
                        .id("relative-time-1")
                        .periods(List.of(
                                RelativeTimePeriod.builder()
                                        .beginMinute(0)
                                        .endMinute(1440)
                                        .unitMinutes(60)
                                        .unitPrice(new BigDecimal("1"))
                                        .build()
                        ))
                        .maxChargeOneCycle(maxCharge)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_TIME_RANGE, new FreeTimeRangePromotionRule());
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

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

    static BillingRequest createRequest(String begin, String end) {
        var request = new BillingRequest();
        request.setId("test-boundary");
        request.setBeginTime(parseTime(begin));
        request.setEndTime(parseTime(end));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-boundary");
        request.setExternalPromotions(new ArrayList<>());
        return request;
    }

    static LocalDateTime parseTime(String timeStr) {
        return BASE_DATE.with(java.time.LocalTime.parse(timeStr, TIME_FORMAT));
    }
}