package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.util.JacksonUtils;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingResultViewer;
import cn.shang.charging.wrapper.QuerySummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 条件免费特性测试
 * <p>
 * 测试 StartFreePromotionConfig.validateQueryTime=true 时的"超过即失效"行为。
 * <p>
 * 核心设计：
 * - 计费引擎（core）产生确定性的计费单元集合，标记 conditionalFree=true
 * - 结果视图层（billing-api）根据 queryTime 决定是否恢复原价
 * - queryTime ≤ conditionalFreeUntil：免费有效
 * - queryTime > conditionalFreeUntil：恢复原价
 */
public class ConditionalFreePromotionTest {

    public static void main(String[] args) {
        System.out.println("========== 条件免费特性测试 ==========\n");

        // 场景1: 基础条件免费 - 不同时间点查询
        testBasicConditionalFree();

        // 场景2: 条件免费 + 外部免费时段重叠
        testConditionalFreeWithExternalRange();

        // 场景3: 条件免费 + 免费分钟数共存
        testConditionalFreeWithFreeMinutes();

        // 场景4: CONTINUE 模式下的条件免费
        testConditionalFreeContinueMode();

        // 场景5: 边界条件 - queryTime 恰好等于 conditionalFreeUntil
        testExactBoundaryTime();

        // 场景6: 多个条件免费单元（跨周期场景）
        testMultipleConditionalFreeUnits();

        System.out.println("\n========== 测试完成 ==========");
    }

    // ==================== 测试用例 ====================

    /**
     * 场景1: 基础条件免费
     * 规则: 前60分钟免费（条件免费），00:00开始，每45分钟2元
     * 验证不同 queryTime 的费用变化
     */
    static void testBasicConditionalFree() {
        System.out.println("=== 场景1: 基础条件免费 ===");
        System.out.println("规则配置:");
        System.out.println("  - 类型: StartFreePromotionConfig");
        System.out.println("  - 免费时长: 60分钟");
        System.out.println("  - validateQueryTime: true（条件免费）");
        System.out.println("  - 计费规则: DayNight, 45分钟/单元, 2元/单元");
        System.out.println("  - 计费窗口: 00:00 - 03:00");
        System.out.println();

        var billingService = getBillingService(60, true);
        var request = new BillingRequest();
        request.setId("conditional-free-basic");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 3, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        // 输出完整计算结果
        System.out.println("完整计费结果:");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        // 不同时间点查询
        LocalDateTime[] queryTimes = {
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 15, 0),  // 00:15 - 免费窗口内
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 59, 0),  // 00:59 - 免费窗口内（临界）
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 0, 0),   // 01:00 - 恰好边界
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 1, 0),   // 01:01 - 超出免费窗口
                LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0),   // 02:00 - 远离免费窗口
                LocalDateTime.of(2026, Month.MARCH, 10, 3, 0, 0),   // 03:00 - 窗口结束
        };

        var viewer = new BillingResultViewer();
        System.out.println("不同时间点查询结果:");
        for (LocalDateTime qt : queryTimes) {
            var summary = viewer.createQuerySummary(result, qt);
            System.out.printf("  queryTime=%s → amount=%s, effectiveTo=%s%n",
                    qt, summary.getAmount(), summary.getEffectiveTo());
        }
        System.out.println();
    }

    /**
     * 场景2: 条件免费 + 外部免费时段重叠
     * 规则: 前60分钟免费（条件免费），外部优惠: 00:30-02:00 免费
     * 验证合并后的条件免费行为
     */
    static void testConditionalFreeWithExternalRange() {
        System.out.println("=== 场景2: 条件免费 + 外部免费时段重叠 ===");
        System.out.println("规则配置:");
        System.out.println("  - 规则: 前60分钟免费（条件免费，00:00-01:00）");
        System.out.println("  - 外部: 免费时段 00:30-02:00（永久免费）");
        System.out.println("  - 合并后: 00:00-02:00 免费");
        System.out.println("  - 其中 00:00-01:00 是条件免费，01:00-02:00 是永久免费");
        System.out.println("  - 计费窗口: 00:00 - 04:00");
        System.out.println();

        var billingService = getBillingService(60, true);
        var request = new BillingRequest();
        request.setId("conditional-free-with-external");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 4, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部永久免费时段
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("external-free-0030-0200")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 30, 0))
                        .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0))
                        .build()
        ));

        var result = billingService.calculate(request);

        System.out.println("完整计费结果:");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        var viewer = new BillingResultViewer();
        LocalDateTime[] queryTimes = {
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 30, 0),  // 00:30 - 条件免费窗口内
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 0, 0),   // 01:00 - 条件免费边界
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 30, 0),  // 01:30 - 超出条件免费但在永久免费内
                LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0),   // 02:00 - 永久免费边界
                LocalDateTime.of(2026, Month.MARCH, 10, 2, 30, 0),  // 02:30 - 超出所有免费
                LocalDateTime.of(2026, Month.MARCH, 10, 4, 0, 0),   // 04:00 - 窗口结束
        };

        System.out.println("不同时间点查询结果:");
        for (LocalDateTime qt : queryTimes) {
            var summary = viewer.createQuerySummary(result, qt);
            System.out.printf("  queryTime=%s → amount=%s, effectiveTo=%s%n",
                    qt, summary.getAmount(), summary.getEffectiveTo());
        }
        System.out.println();
    }

    /**
     * 场景3: 条件免费 + 免费分钟数共存
     * 规则: 前30分钟免费（条件免费）+ 外部20分钟免费
     * 验证两种优惠类型的交互
     */
    static void testConditionalFreeWithFreeMinutes() {
        System.out.println("=== 场景3: 条件免费 + 免费分钟数共存 ===");
        System.out.println("规则配置:");
        System.out.println("  - 规则: 前30分钟免费（条件免费，00:00-00:30）");
        System.out.println("  - 外部: 20分钟免费分钟数");
        System.out.println("  - 计费窗口: 00:00 - 02:00");
        System.out.println();

        var billingService = getBillingService(30, true);
        var request = new BillingRequest();
        request.setId("conditional-free-with-minutes");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 外部免费分钟数
        request.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("external-free-minutes-20")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .priority(2)
                        .source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(20)
                        .build()
        ));

        var result = billingService.calculate(request);

        System.out.println("完整计费结果:");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        var viewer = new BillingResultViewer();
        LocalDateTime[] queryTimes = {
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 20, 0),  // 00:20 - 条件免费窗口内
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 30, 0),  // 00:30 - 条件免费边界
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 50, 0),  // 00:50 - 免费分钟数覆盖范围内
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 0, 0),   // 01:00 - 后续时间
                LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0),   // 02:00 - 窗口结束
        };

        System.out.println("不同时间点查询结果:");
        for (LocalDateTime qt : queryTimes) {
            var summary = viewer.createQuerySummary(result, qt);
            System.out.printf("  queryTime=%s → amount=%s, effectiveTo=%s%n",
                    qt, summary.getAmount(), summary.getEffectiveTo());
        }
        System.out.println();
    }

    /**
     * 场景4: CONTINUE 模式下的条件免费
     * 验证从上次计算结果继续计算时，条件免费的处理
     */
    static void testConditionalFreeContinueMode() {
        System.out.println("=== 场景4: CONTINUE 模式下的条件免费 ===");
        System.out.println("规则配置:");
        System.out.println("  - 规则: 前30分钟免费（条件免费）");
        System.out.println("  - 计费窗口: 00:00 - 02:00");
        System.out.println();

        var billingService = getBillingService(30, true);

        // 第一次计算: 00:00-01:00
        var request1 = new BillingRequest();
        request1.setId("continue-test");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 1, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");
        request1.setExternalPromotions(new ArrayList<>());

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算 (00:00-01:00):");
        System.out.println(JacksonUtils.toJsonString(result1));
        System.out.println();

        var viewer = new BillingResultViewer();
        System.out.println("第一次计算查询:");
        LocalDateTime[] queryTimes1 = {
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 20, 0),  // 免费窗口内
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 31, 0),  // 超出条件免费
        };
        for (LocalDateTime qt : queryTimes1) {
            var summary = viewer.createQuerySummary(result1, qt);
            System.out.printf("  queryTime=%s → amount=%s%n", qt, summary.getAmount());
        }
        System.out.println();

        // 第二次计算: 继续到 03:00
        var request2 = new BillingRequest();
        request2.setId("continue-test");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 3, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setExternalPromotions(new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());

        var result2 = billingService.calculate(request2);

        System.out.println("第二次计算 (CONTINUE, 00:00-03:00):");
        System.out.println(JacksonUtils.toJsonString(result2));
        System.out.println();

        System.out.println("第二次计算查询:");
        LocalDateTime[] queryTimes2 = {
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 50, 0),  // 第1个单元内（00:45-01:30）
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 30, 0),  // 第2个单元内（01:30-02:15）
                LocalDateTime.of(2026, Month.MARCH, 10, 2, 30, 0),  // 第3个单元内（02:15-03:00）
                LocalDateTime.of(2026, Month.MARCH, 10, 3, 0, 0),   // 窗口结束
        };
        for (LocalDateTime qt : queryTimes2) {
            var summary = viewer.createQuerySummary(result2, qt);
            System.out.printf("  queryTime=%s → amount=%s%n", qt, summary.getAmount());
        }
        System.out.println();
    }

    /**
     * 场景5: 边界条件 - queryTime 恰好等于 conditionalFreeUntil
     */
    static void testExactBoundaryTime() {
        System.out.println("=== 场景5: 边界条件测试 ===");
        System.out.println("规则配置:");
        System.out.println("  - 规则: 前60分钟免费（条件免费）");
        System.out.println("  - conditionalFreeUntil: 01:00");
        System.out.println("  - 验证 queryTime = 01:00 时的行为（应该免费有效）");
        System.out.println();

        var billingService = getBillingService(60, true);
        var request = new BillingRequest();
        request.setId("boundary-test");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        var viewer = new BillingResultViewer();
        LocalDateTime[] queryTimes = {
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 59, 0),  // 00:59 - 边界前
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 0, 0),   // 01:00:00 - 恰好边界（queryTime <= endTime，仍在窗口内）
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 1, 0),   // 01:01 - 边界后1分钟（超出窗口）
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 30, 0),  // 01:30 - 远离免费窗口
                LocalDateTime.of(2026, Month.MARCH, 10, 2, 0, 0),   // 02:00 - 窗口结束
        };

        System.out.println("边界查询结果:");
        for (LocalDateTime qt : queryTimes) {
            var summary = viewer.createQuerySummary(result, qt);
            System.out.printf("  queryTime=%s → amount=%s, effectiveTo=%s%n",
                    qt, summary.getAmount(), summary.getEffectiveTo());
        }
        System.out.println();
    }

    /**
     * 场景6: 多个条件免费单元
     * 当免费时段跨越多个计费单元时，验证所有单元的 conditionalFree 标记
     */
    static void testMultipleConditionalFreeUnits() {
        System.out.println("=== 场景6: 多个条件免费单元 ===");
        System.out.println("规则配置:");
        System.out.println("  - 规则: 前90分钟免费（条件免费）");
        System.out.println("  - 计费单元: 30分钟/单元");
        System.out.println("  - 预期: 前3个单元都是条件免费");
        System.out.println("  - 计费窗口: 00:00 - 03:00");
        System.out.println();

        var billingService = getBillingServiceWithUnitMinutes(90, true, 30);
        var request = new BillingRequest();
        request.setId("multiple-units-test");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 3, 0, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");
        request.setExternalPromotions(new ArrayList<>());

        var result = billingService.calculate(request);

        System.out.println("完整计费结果:");
        System.out.println(JacksonUtils.toJsonString(result));
        System.out.println();

        var viewer = new BillingResultViewer();
        LocalDateTime[] queryTimes = {
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 20, 0),  // 第1个单元内，免费窗口
                LocalDateTime.of(2026, Month.MARCH, 10, 0, 45, 0),  // 第2个单元内，免费窗口
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 20, 0),  // 第3个单元内，免费窗口
                LocalDateTime.of(2026, Month.MARCH, 10, 1, 31, 0),  // 第4个单元，超出免费窗口
                LocalDateTime.of(2026, Month.MARCH, 10, 2, 30, 0),  // 远离免费窗口
                LocalDateTime.of(2026, Month.MARCH, 10, 3, 0, 0),   // 窗口结束
        };

        System.out.println("不同时间点查询结果:");
        for (LocalDateTime qt : queryTimes) {
            var summary = viewer.createQuerySummary(result, qt);
            System.out.printf("  queryTime=%s → amount=%s, effectiveTo=%s%n",
                    qt, summary.getAmount(), summary.getEffectiveTo());
        }
        System.out.println();
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建带 StartFree 规则的 BillingService
     *
     * @param startFreeMinutes  前N分钟免费
     * @param validateQueryTime 是否启用条件免费
     */
    static BillingService getBillingService(int startFreeMinutes, boolean validateQueryTime) {
        return getBillingServiceWithUnitMinutes(startFreeMinutes, validateQueryTime, 45);
    }

    /**
     * 创建带 StartFree 规则和指定单元时长的 BillingService
     *
     * @param startFreeMinutes  前N分钟免费
     * @param validateQueryTime 是否启用条件免费
     * @param unitMinutes       计费单元时长（分钟）
     */
    static BillingService getBillingServiceWithUnitMinutes(int startFreeMinutes, boolean validateQueryTime, int unitMinutes) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740)
                        .setDayEndMinute(1140)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("100"))
                        .setUnitMinutes(unitMinutes);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of(
                        new StartFreePromotionConfig()
                                .setId("start-free-conditional")
                                .setMinutes(startFreeMinutes)
                                .setPriority(1)
                                .setValidateQueryTime(validateQueryTime)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
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
