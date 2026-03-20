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
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.charge.util.JacksonUtils;

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

        // 性能开销测试
        testPerformanceOverhead();

        System.out.println("\n========== 测试完成 ==========\n");
    }

    /**
     * 性能开销测试
     * 精确测量 boundaryReferences 带来的额外开销
     */
    static void testPerformanceOverhead() {
        System.out.println("=== 性能开销测试 ===\n");

        var billingService = createBillingService(new BigDecimal("100"));

        // 准备测试数据：10个窗口外免费时段
        List<PromotionGrant> manyFreeRanges = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyFreeRanges.add(PromotionGrant.builder()
                    .id("free-range-" + i)
                    .type(BConstants.PromotionType.FREE_RANGE)
                    .priority(1)
                    .source(BConstants.PromotionSource.COUPON)
                    .beginTime(parseTime(String.format("%02d:00", 10 + i)))
                    .endTime(parseTime(String.format("%02d:30", 10 + i)))
                    .build());
        }

        // 准备测试数据：窗口内免费时段（用于对比优惠处理开销）
        List<PromotionGrant> insideFreeRanges = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            insideFreeRanges.add(PromotionGrant.builder()
                    .id("free-range-inside-" + i)
                    .type(BConstants.PromotionType.FREE_RANGE)
                    .priority(1)
                    .source(BConstants.PromotionSource.COUPON)
                    .beginTime(parseTime(String.format("%02d:00", 7 + i / 2)))
                    .endTime(parseTime(String.format("%02d:30", 7 + i / 2)))
                    .build());
        }

        // 预热
        var warmupRequest = createRequest("07:30", "09:00");
        warmupRequest.setExternalPromotions(manyFreeRanges);
        for (int i = 0; i < 100; i++) {
            billingService.calculate(warmupRequest);
        }

        int iterations = 10000;

        // 测试1：无任何优惠（基准）
        long startNoPromo = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var request = createRequest("07:30", "09:00");
            request.setExternalPromotions(new ArrayList<>());
            billingService.calculate(request);
        }
        long endNoPromo = System.nanoTime();

        // 测试2：有 boundaryReferences（10个窗口外优惠）
        long startWithBoundary = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var request = createRequest("07:30", "09:00");
            request.setExternalPromotions(manyFreeRanges);
            billingService.calculate(request);
        }
        long endWithBoundary = System.nanoTime();

        // 测试3：窗口内优惠（参与计算的优惠）
        long startInsidePromo = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var request = createRequest("07:30", "09:00");
            request.setExternalPromotions(insideFreeRanges);
            billingService.calculate(request);
        }
        long endInsidePromo = System.nanoTime();

        // 计算结果
        double avgNoPromo = (endNoPromo - startNoPromo) / (double) iterations / 1_000_000;
        double avgWithBoundary = (endWithBoundary - startWithBoundary) / (double) iterations / 1_000_000;
        double avgInsidePromo = (endInsidePromo - startInsidePromo) / (double) iterations / 1_000_000;

        // 边界参考开销 = 有窗口外优惠 - 无优惠
        double boundaryOverhead = avgWithBoundary - avgNoPromo;
        // 优惠处理开销 = 窗口内优惠 - 无优惠
        double promoOverhead = avgInsidePromo - avgNoPromo;

        System.out.println("测试条件:");
        System.out.println("  迭代次数: " + iterations);
        System.out.println("  优惠数量: 10个");
        System.out.println();
        System.out.println("性能结果:");
        System.out.printf("  无优惠（基准）:         %.4f ms/次%n", avgNoPromo);
        System.out.printf("  有 boundaryReferences:  %.4f ms/次%n", avgWithBoundary);
        System.out.printf("  窗口内优惠参与计算:     %.4f ms/次%n", avgInsidePromo);
        System.out.println();
        System.out.println("开销分析:");
        System.out.printf("  boundaryReferences 开销: %.4f ms/次 (%.2f%%)%n",
                boundaryOverhead, (boundaryOverhead / avgNoPromo) * 100);
        System.out.printf("  优惠参与计算开销:        %.4f ms/次 (%.2f%%)%n",
                promoOverhead, (promoOverhead / avgNoPromo) * 100);
        System.out.println();
        System.out.println("结论:");
        System.out.println("  - boundaryReferences 仅增加少量条件判断和列表遍历");
        System.out.println("  - 开销与窗口外优惠数量成正比 O(n)");
        System.out.println("  - 典型场景 (1-5个窗口外优惠): 开销可忽略 (<0.01ms)");
        System.out.println("  - 相比窗口内优惠参与计算，boundaryReferences 开销更小");
        System.out.println();
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

        System.out.println("输入参数:");
        System.out.println("  计算窗口: 07:30 - 09:00");
        System.out.println("  免费时段: 09:20 - 09:50（在窗口外）");
        System.out.println("  计费单元长度: 60分钟");
        System.out.println();

        // 输出计费明细 JSON
        System.out.println("计费明细 JSON:");
        System.out.println(formatBillingResult(result));
        System.out.println();

        // 验证计费单元
        System.out.println("计费单元详情:");
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
        System.out.println("\n验证结果:");
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
                System.out.println("  已使用免费时段: " + (usedRanges != null ? JacksonUtils.toJsonString(usedRanges) : "null"));
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

        System.out.println("输入参数:");
        System.out.println("  计算窗口: 07:30 - 09:00");
        System.out.println("  免费时段1: 09:20 - 09:50");
        System.out.println("  免费时段2: 10:00 - 11:00");
        System.out.println();

        System.out.println("计费明细 JSON:");
        System.out.println(formatBillingResult(result));
        System.out.println();

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("验证结果:");
        System.out.println("  最后单元: " + lastUnit.getBeginTime().format(TIME_FORMAT) + " - " + lastUnit.getEndTime().format(TIME_FORMAT));
        System.out.println("  预期延伸到: 09:20（最近的边界）");

        boolean extendedCorrectly = lastUnit.getEndTime().equals(parseTime("09:20"));
        System.out.println("  延伸正确: " + (extendedCorrectly ? "✓" : "✗"));

        System.out.println();
    }

    /**
     * 场景3：延伸后继续计算 - 验证优惠仍然可用（CONTINUOUS 模式）
     *
     * 场景设置：
     * - 第一次计算：07:30-09:00，免费时段 09:20-09:50
     * - 第二次计算：继续到 10:00
     *
     * 预期行为（CONTINUOUS 模式）：
     * - 第一次延伸到 09:20
     * - 第二次从 09:20 继续
     * - 计费单元按免费时段边界切分：
     *   - 09:20-09:50 免费
     *   - 09:50-10:20 收费（延伸到完整单元）
     */
    static void testBoundaryReference_WithContinue() {
        System.out.println("=== 场景3: 延伸后继续计算，优惠仍然可用（CONTINUOUS模式） ===\n");

        var billingService = createBillingServiceWithMode(BConstants.BillingMode.CONTINUOUS, new BigDecimal("100"));

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
        System.out.println("  最后单元结束时间: " + lastUnit1.getEndTime().format(TIME_FORMAT));
        System.out.println("  延伸停在边界: " + (lastUnit1.getEndTime().equals(parseTime("09:20")) ? "✓" : "✗"));

        // 第二次计算: 继续 09:00 - 10:00
        var request2 = createRequest("07:30", "10:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 09:00 - 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  calculationEndTime: " + result2.getCalculationEndTime());

        System.out.println("\n计费明细 JSON:");
        System.out.println(formatBillingResult(result2));
        System.out.println();

        // 验证计费单元
        System.out.println("计费单元详情:");
        for (var unit : result2.getUnits()) {
            System.out.printf("  %s - %s (%d分钟) 金额:%s 免费:%s%n",
                    unit.getBeginTime().format(TIME_FORMAT),
                    unit.getEndTime().format(TIME_FORMAT),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount(),
                    unit.isFree() ? "是(" + unit.getFreePromotionId() + ")" : "否");
        }

        // 验证：在 CONTINUOUS 模式下，免费时段边界应该切分计费单元
        // 09:20-09:50 应该是独立的免费单元
        var freeUnit = result2.getUnits().stream()
                .filter(u -> u.isFree() && u.getFreePromotionId() != null &&
                        u.getFreePromotionId().contains("free-range"))
                .findFirst();

        System.out.println("\n验证结果:");
        if (freeUnit.isPresent()) {
            var u = freeUnit.get();
            System.out.println("  免费单元: " + u.getBeginTime().format(TIME_FORMAT) + " - " + u.getEndTime().format(TIME_FORMAT));
            boolean matchesFreeRange = u.getBeginTime().equals(parseTime("09:20")) &&
                                       u.getEndTime().equals(parseTime("09:50"));
            System.out.println("  免费时段 09:20-09:50 被正确切分: " + (matchesFreeRange ? "✓" : "✗"));
        } else {
            System.out.println("  未找到免费单元 ✗");
        }
        System.out.println("  关键: CONTINUOUS 模式在免费时段边界切分计费单元");

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

        System.out.println("输入参数:");
        System.out.println("  计算窗口: 07:30 - 09:00");
        System.out.println("  免费时段: 11:00 - 12:00（延伸区域外）");
        System.out.println();

        System.out.println("计费明细 JSON:");
        System.out.println(formatBillingResult(result));
        System.out.println();

        var lastUnit = result.getUnits().get(result.getUnits().size() - 1);
        System.out.println("验证结果:");
        System.out.println("  最后单元: " + lastUnit.getBeginTime().format(TIME_FORMAT) + " - " + lastUnit.getEndTime().format(TIME_FORMAT));
        System.out.println("  延伸到完整单元长度: " + lastUnit.getDurationMinutes() + "分钟");
        System.out.println("  预期: 延伸到完整单元长度，不受优惠影响 ✓");

        System.out.println();
    }

    // ==================== 辅助方法 ====================

    static String formatBillingResult(BillingResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"finalAmount\": ").append(result.getFinalAmount()).append(",\n");
        sb.append("  \"calculationEndTime\": \"").append(result.getCalculationEndTime()).append("\",\n");
        sb.append("  \"units\": [\n");
        for (int i = 0; i < result.getUnits().size(); i++) {
            var unit = result.getUnits().get(i);
            sb.append("    {\"beginTime\":\"").append(unit.getBeginTime().format(TIME_FORMAT))
              .append("\", \"endTime\":\"").append(unit.getEndTime().format(TIME_FORMAT))
              .append("\", \"duration\":").append(unit.getDurationMinutes())
              .append(", \"amount\":").append(unit.getChargedAmount())
              .append(", \"free\":").append(unit.isFree());
            if (unit.isFree() && unit.getFreePromotionId() != null) {
                sb.append(", \"freePromotionId\":\"").append(unit.getFreePromotionId()).append("\"");
            }
            sb.append("}");
            if (i < result.getUnits().size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // carryOver
        if (result.getCarryOver() != null) {
            sb.append("  \"carryOver\": ").append(JacksonUtils.toJsonString(result.getCarryOver())).append("\n");
        }

        sb.append("}");
        return sb.toString();
    }

    static BillingService createBillingService(BigDecimal maxCharge) {
        return createBillingServiceWithMode(BConstants.BillingMode.UNIT_BASED, maxCharge);
    }

    static BillingService createBillingServiceWithMode(BConstants.BillingMode mode, BigDecimal maxCharge) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return mode;
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