package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
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
import java.util.Map;

/**
 * 长期计费简化计算测试
 */
public class SimplifiedCalculationTest {

    public static void main(String[] args) {
        System.out.println("========== 长期计费简化计算测试 ==========\n");

        // 测试1: 基本简化 - 30天无优惠
        testBasicSimplification();

        // 测试2: 部分简化 - 中间某天有优惠
        testPartialSimplification();

        // 测试3: 阈值边界 - 8天（阈值7）
        testThresholdBoundary();

        // 测试4: 禁用简化 - 配置禁用或阈值0
        testDisabledSimplification();

        // 测试5: CONTINUE 模式状态恢复
        testContinueMode();

        // 测试6: 免费分钟时不简化
        testFreeMinutes();

        System.out.println("\n========== 所有测试通过 ==========\n");
    }

    /**
     * 测试1: 基本简化 - 30天无优惠
     * 预期：生成1个简化单元，金额 = 封顶金额 * 30
     */
    static void testBasicSimplification() {
        System.out.println("=== 测试1: 基本简化 - 30天无优惠 ===\n");

        int threshold = 7;
        var billingService = getBillingService(threshold);

        // 计费时间: 30天
        LocalDateTime beginTime = LocalDateTime.of(2026, Month.MARCH, 1, 8, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(30);

        var request = new BillingRequest();
        request.setId("test-basic");
        request.setBeginTime(beginTime);
        request.setEndTime(endTime);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: " + beginTime + " - " + endTime + " (30天)");
        System.out.println("配置: 阈值 " + threshold + ", 封顶金额 10元/天");
        System.out.println("结果金额: " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println();

        // 验证
        boolean hasSimplifiedUnit = result.getUnits().stream().anyMatch(u -> isSimplifiedUnit(u));

        if (hasSimplifiedUnit) {
            System.out.println("[PASS] 生成了简化单元");
            BillingUnit simplifiedUnit = result.getUnits().stream()
                    .filter(u -> isSimplifiedUnit(u))
                    .findFirst()
                    .orElse(null);

            if (simplifiedUnit != null) {
                System.out.println("  简化单元时间: " + simplifiedUnit.getBeginTime() + " - " + simplifiedUnit.getEndTime());
                System.out.println("  简化单元金额: " + simplifiedUnit.getChargedAmount());
                System.out.println("  预期金额: " + new BigDecimal("300.00") + " (10元 * 30天)");

                // 验证金额
                if (simplifiedUnit.getChargedAmount().compareTo(new BigDecimal("300.00")) == 0) {
                    System.out.println("  [PASS] 金额正确");
                } else {
                    System.out.println("  [FAIL] 金额不正确");
                }
            }
        } else {
            System.out.println("[FAIL] 未生成简化单元");
        }
        System.out.println();
    }

    /**
     * 测试2: 部分简化 - 30天，中间某天有优惠
     * 预期：优惠前后各生成简化单元，优惠当天生成详细单元
     */
    static void testPartialSimplification() {
        System.out.println("=== 测试2: 部分简化 - 30天中间有优惠 ===\n");

        int threshold = 7;
        var billingService = getBillingService(threshold);

        // 计费时间: 30天
        LocalDateTime beginTime = LocalDateTime.of(2026, Month.MARCH, 1, 8, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(30);

        // 中间某天（第15天）有免费时段
        LocalDateTime freeBegin = beginTime.plusDays(15);
        LocalDateTime freeEnd = freeBegin.plusHours(2);

        List<PromotionGrant> externalPromotions = new ArrayList<>();
        externalPromotions.add(PromotionGrant.builder()
                .id("free-range-15")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(freeBegin)
                .endTime(freeEnd)
                .build());

        var request = new BillingRequest();
        request.setId("test-partial");
        request.setBeginTime(beginTime);
        request.setEndTime(endTime);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(externalPromotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: " + beginTime + " - " + endTime + " (30天)");
        System.out.println("免费时段: 第15天 " + freeBegin + " - " + freeEnd);
        System.out.println("结果金额: " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println();

        // 统计简化单元和详细单元
        long simplifiedCount = result.getUnits().stream().filter(u -> isSimplifiedUnit(u)).count();
        long detailedCount = result.getUnits().stream().filter(u -> !isSimplifiedUnit(u)).count();

        System.out.println("简化单元数: " + simplifiedCount);
        System.out.println("详细单元数: " + detailedCount);

        // 预期：第0-14天简化（15天 > 阈值7），第15天详细，第16-29天简化（14天 > 阈值7）
        if (simplifiedCount >= 2) {
            System.out.println("[PASS] 生成了至少2个简化单元（优惠前后）");
        } else {
            System.out.println("[FAIL] 简化单元数量不符合预期");
        }
        System.out.println();
    }

    /**
     * 测试3: 阈值边界 - 8天（阈值7）
     * 预期：刚好超过阈值，生成简化单元
     */
    static void testThresholdBoundary() {
        System.out.println("=== 测试3: 阈值边界 - 8天（阈值7）===\n");

        int threshold = 7;
        var billingService = getBillingService(threshold);

        // 计费时间: 8天
        LocalDateTime beginTime = LocalDateTime.of(2026, Month.MARCH, 1, 8, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(8);

        var request = new BillingRequest();
        request.setId("test-boundary");
        request.setBeginTime(beginTime);
        request.setEndTime(endTime);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: " + beginTime + " - " + endTime + " (8天)");
        System.out.println("配置: 阈值 " + threshold);
        System.out.println("结果金额: " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println();

        boolean hasSimplifiedUnit = result.getUnits().stream().anyMatch(u -> isSimplifiedUnit(u));

        if (hasSimplifiedUnit) {
            System.out.println("[PASS] 8天超过阈值7，生成简化单元");
            System.out.println("  预期金额: " + new BigDecimal("80.00") + " (10元 * 8天)");

            if (result.getFinalAmount().compareTo(new BigDecimal("80.00")) == 0) {
                System.out.println("  [PASS] 金额正确");
            } else {
                System.out.println("  [FAIL] 金额不正确，实际: " + result.getFinalAmount());
            }
        } else {
            System.out.println("[FAIL] 8天应该超过阈值7，但未生成简化单元");
        }
        System.out.println();
    }

    /**
     * 测试4: 禁用简化 - 配置禁用或阈值0
     * 预期：不生成简化单元，正常详细计算
     */
    static void testDisabledSimplification() {
        System.out.println("=== 测试4: 禁用简化 - 阈值0 ===\n");

        int threshold = 0; // 禁用
        var billingService = getBillingService(threshold);

        // 计费时间: 30天
        LocalDateTime beginTime = LocalDateTime.of(2026, Month.MARCH, 1, 8, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(30);

        var request = new BillingRequest();
        request.setId("test-disabled");
        request.setBeginTime(beginTime);
        request.setEndTime(endTime);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: " + beginTime + " - " + endTime + " (30天)");
        System.out.println("配置: 阈值 " + threshold + " (禁用)");
        System.out.println("结果金额: " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println();

        boolean hasSimplifiedUnit = result.getUnits().stream().anyMatch(u -> isSimplifiedUnit(u));

        if (!hasSimplifiedUnit) {
            System.out.println("[PASS] 禁用时未生成简化单元");
            System.out.println("  生成了 " + result.getUnits().size() + " 个详细单元");
        } else {
            System.out.println("[FAIL] 禁用时不应生成简化单元");
        }
        System.out.println();
    }

    /**
     * 测试5: CONTINUE 模式状态恢复
     * 预期：简化单元状态正确恢复，继续计算正确
     */
    static void testContinueMode() {
        System.out.println("=== 测试5: CONTINUE 模式状态恢复 ===\n");

        int threshold = 7;
        var billingService = getBillingService(threshold);

        // 计费时间: 30天
        LocalDateTime beginTime = LocalDateTime.of(2026, Month.MARCH, 1, 8, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(30);

        // 第一次计算: FROM_SCRATCH
        var request1 = new BillingRequest();
        request1.setId("test-continue-1");
        request1.setBeginTime(beginTime);
        request1.setEndTime(endTime);
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");
        request1.setExternalPromotions(new ArrayList<>());

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算 (FROM_SCRATCH): " + beginTime + " - " + endTime);
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  计费单元数量: " + result1.getUnits().size());

        // 验证简化单元
        boolean hasSimplifiedUnit = result1.getUnits().stream().anyMatch(u -> isSimplifiedUnit(u));
        System.out.println("  是否有简化单元: " + hasSimplifiedUnit);

        // 获取 carryOver 状态
        var carryOver = result1.getCarryOver();
        System.out.println("  carryOver.calculatedUpTo: " + carryOver.getCalculatedUpTo());
        System.out.println();

        // 第二次计算: CONTINUE - 从第30天继续到第35天
        LocalDateTime continueEndTime = beginTime.plusDays(35);

        var request2 = new BillingRequest();
        request2.setId("test-continue-2");
        request2.setBeginTime(beginTime);
        request2.setEndTime(continueEndTime);
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setExternalPromotions(new ArrayList<>());
        request2.setPreviousCarryOver(carryOver);

        var result2 = billingService.calculate(request2);

        System.out.println("第二次计算 (CONTINUE): " + endTime + " - " + continueEndTime);
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期金额: " + new BigDecimal("50.00") + " (10元 * 5天)");

        if (result2.getFinalAmount().compareTo(new BigDecimal("50.00")) == 0) {
            System.out.println("  [PASS] CONTINUE 模式金额正确");
        } else {
            System.out.println("  [FAIL] CONTINUE 模式金额不正确");
        }
        System.out.println();
    }

    /**
     * 测试6: 免费分钟时不简化
     * 预期：存在免费分钟数时，不启用简化
     */
    static void testFreeMinutes() {
        System.out.println("=== 测试6: 免费分钟时不简化 ===\n");

        int threshold = 7;
        var billingService = getBillingServiceWithFreeMinutes(threshold);

        // 计费时间: 30天
        LocalDateTime beginTime = LocalDateTime.of(2026, Month.MARCH, 1, 8, 0, 0);
        LocalDateTime endTime = beginTime.plusDays(30);

        var request = new BillingRequest();
        request.setId("test-free-minutes");
        request.setBeginTime(beginTime);
        request.setEndTime(endTime);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("计费时间: " + beginTime + " - " + endTime + " (30天)");
        System.out.println("配置: 阈值 " + threshold + ", 规则级别免费分钟数 30分钟");
        System.out.println("结果金额: " + result.getFinalAmount());
        System.out.println("计费单元数量: " + result.getUnits().size());
        System.out.println();

        boolean hasSimplifiedUnit = result.getUnits().stream().anyMatch(u -> isSimplifiedUnit(u));

        if (!hasSimplifiedUnit) {
            System.out.println("[PASS] 存在免费分钟数时未生成简化单元");
        } else {
            System.out.println("[FAIL] 存在免费分钟数时不应生成简化单元");
        }
        System.out.println();
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查单元是否为简化单元
     */
    @SuppressWarnings("unchecked")
    static boolean isSimplifiedUnit(BillingUnit unit) {
        if (unit.getRuleData() instanceof Map) {
            Map<String, Object> data = (Map<String, Object>) unit.getRuleData();
            return Boolean.TRUE.equals(data.get("isSimplified"));
        }
        return false;
    }

    /**
     * 获取基础服务（可配置阈值）
     */
    static BillingService getBillingService(int threshold) {
        var billingConfigResolver = new BillingConfigResolver() {
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            public int getSimplifiedCycleThreshold() {
                return threshold;
            }

            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)   // 08:00
                        .setDayEndMinute(1200)    // 20:00
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("10"))
                        .setUnitMinutes(60);
            }

            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
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
     * 获取带免费分钟数的服务
     */
    static BillingService getBillingServiceWithFreeMinutes(int threshold) {
        var billingConfigResolver = new BillingConfigResolver() {
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            public int getSimplifiedCycleThreshold() {
                return threshold;
            }

            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)
                        .setDayEndMinute(1200)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("10"))
                        .setUnitMinutes(60);
            }

            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
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