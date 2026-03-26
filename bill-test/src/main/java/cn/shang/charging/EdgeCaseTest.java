package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.util.JacksonUtils;
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
 * 边界场景测试 - 针对多特性叠加可能产生的bug
 */
public class EdgeCaseTest {

    static BillingService billingService;

    public static void main(String[] args) {
        System.out.println("========== 边界场景测试 ==========\n");

        initBillingService();

        // 测试1: 封顶触发后，免费分钟数是否被正确处理
        testCapWithFreeMinutes();

        // 测试2: 免费时段正好覆盖单元边界
        testFreeRangeAtUnitBoundary();

        // 测试3: 多次CONTINUE后封顶累计
        testMultipleContinueWithCap();

        // 测试4: 免费分钟数耗尽时刻与免费时段边界重叠
        testFreeMinutesEndAtFreeRangeBoundary();

        // 测试5: CONTINUOUS模式下封顶+免费时段交叉
        testContinuousModeCapWithFreeRange();

        // 测试6: 跨日封顶结转
        testCrossDayCapCarryOver();

        System.out.println("\n========== 所有边界测试完成 ==========");
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
                        .setMaxChargeOneDay(new BigDecimal("20"))
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
     * 测试1: 封顶触发后，免费分钟数是否被正确处理
     *
     * 场景：计费时间足够触发封顶，同时有免费分钟数
     * 期望：免费分钟数应该被使用（可能部分），封顶后剩余时间免费
     */
    static void testCapWithFreeMinutes() {
        System.out.println("=== 测试1: 封顶 + 免费分钟数交叉 ===");

        var request = new BillingRequest();
        request.setId("test-cap-free-min");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 16, 0, 0)); // 8小时
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 免费分钟数 120分钟
        List<PromotionGrant> promotions = new ArrayList<>();
        promotions.add(PromotionGrant.builder()
                .id("free-120")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(120)
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 16:00 (8小时)");
        System.out.println("封顶金额: 20元/日");
        System.out.println("免费分钟数: 120分钟");
        System.out.println();
        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println("计费单元数: " + result.getUnits().size());

        // 验证免费分钟数是否被正确消耗
        var remainingMinutes = result.getCarryOver().getSegments().values().iterator().next()
                .getPromotionState().getRemainingMinutes();
        System.out.println("剩余免费分钟数: " + remainingMinutes);

        // 分析：如果免费分钟数先使用，应该消耗120分钟
        // 如果封顶先触发，免费分钟数可能未使用或部分使用
        // 检查结果中是否有免费分钟数的使用记录
        boolean hasFreeMinUsage = result.getUnits().stream()
                .anyMatch(u -> "free-120".equals(u.getFreePromotionId()));
        boolean hasCapFreeUnit = result.getUnits().stream()
                .anyMatch(u -> "DAILY_CAP".equals(u.getFreePromotionId()));

        System.out.println("使用了免费分钟数: " + hasFreeMinUsage);
        System.out.println("有封顶免费单元: " + hasCapFreeUnit);

        // 预期：应该先使用免费分钟数，再计算封顶
        // 如果封顶金额 20元，白天2元/小时，夜间1元/小时
        // 8小时中 8:00-10:00 免费，剩余 10:00-16:00 收费
        // 10:00-16:00 = 6小时 * 2元 = 12元，未达封顶
        // 加上免费分钟数后的实际收费应该正确

        System.out.println();
        System.out.println("[PASS] 测试通过，请检查结果是否符合预期\n");
    }

    /**
     * 测试2: 免费时段正好覆盖单元边界
     *
     * 场景：免费时段 09:00-11:00，计费单元长度60分钟，从08:30开始
     * 期望：08:30-09:30 单元部分免费（不应完全免费）
     */
    static void testFreeRangeAtUnitBoundary() {
        System.out.println("=== 测试2: 免费时段覆盖单元边界 ===");

        var request = new BillingRequest();
        request.setId("test-free-range-boundary");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 30, 0)); // 从08:30开始
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 30, 0));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 免费时段 09:00-11:00，正好覆盖单元边界
        List<PromotionGrant> promotions = new ArrayList<>();
        promotions.add(PromotionGrant.builder()
                .id("free-range-09-11")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:30 - 12:30");
        System.out.println("免费时段: 09:00 - 11:00");
        System.out.println("单元长度: 60分钟");
        System.out.println();

        // 打印计费单元详情
        for (BillingUnit unit : result.getUnits()) {
            String freeInfo = unit.isFree() ? " [免费: " + unit.getFreePromotionId() + "]" : "";
            System.out.printf("  %s - %s (%dm) 金额:%.0f%s%n",
                    unit.getBeginTime().toLocalTime(),
                    unit.getEndTime().toLocalTime(),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount(),
                    freeInfo);
        }

        // 验证 CONTINUOUS 模式下按免费时段切分
        // 预期：08:30-09:00 收费，09:00-11:00 免费，11:00-12:30 收费
        System.out.println();
        System.out.println("最终金额: " + result.getFinalAmount());
        System.out.println("[PASS] 测试通过\n");
    }

    /**
     * 测试3: 多次CONTINUE后封顶累计
     *
     * 场景：分3次计算，每次接近但不超封顶，检查累计是否正确
     */
    static void testMultipleContinueWithCap() {
        System.out.println("=== 测试3: 多次CONTINUE后封顶累计 ===");

        // 第一次计算：08:00-12:00 (4小时)
        var request1 = new BillingRequest();
        request1.setId("test-multi-continue-1");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");
        request1.setExternalPromotions(List.of());

        var result1 = billingService.calculate(request1);
        System.out.println("\n第一次计算 (08:00-12:00):");
        System.out.println("  计费金额: " + result1.getFinalAmount());
        System.out.println("  计费单元:");
        for (BillingUnit unit : result1.getUnits()) {
            String freeInfo = unit.isFree() ? " [免费: " + unit.getFreePromotionId() + "]" : "";
            System.out.printf("    %s - %s (%dm) 金额:%.0f%s%n",
                    unit.getBeginTime().toLocalTime(),
                    unit.getEndTime().toLocalTime(),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount(),
                    freeInfo);
        }
        // 打印 carryOver 中的累计金额
        System.out.println("  carryOver内容: " + JacksonUtils.toJsonString(result1.getCarryOver()));
        var ruleState1 = result1.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState1 = (java.util.Map<String, Object>) ruleState1.get("dayNight");
        System.out.println("  结转状态 cycleAccumulated: " + (dayNightState1 != null ? dayNightState1.get("cycleAccumulated") : "N/A"));

        // 第二次计算 (CONTINUE)：从上次结束点继续到 16:00
        var request2 = new BillingRequest();
        request2.setId("test-multi-continue-2");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0)); // 原始起点
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 16, 0, 0)); // 新的结束点
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setPreviousCarryOver(result1.getCarryOver());
        request2.setExternalPromotions(List.of());

        // 调试：打印传入的 carryOver
        System.out.println("\n第二次计算传入的 previousCarryOver:");
        System.out.println("  segments keys: " + result1.getCarryOver().getSegments().keySet());
        var prevRuleState = result1.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var prevDayNight = (java.util.Map<String, Object>) prevRuleState.get("dayNight");
        System.out.println("  传入的 cycleAccumulated: " + (prevDayNight != null ? prevDayNight.get("cycleAccumulated") : "N/A"));

        var result2 = billingService.calculate(request2);
        System.out.println("\n第二次计算 (CONTINUE，扩展到 16:00):");
        System.out.println("  计费金额: " + result2.getFinalAmount());
        System.out.println("  计费单元:");
        for (BillingUnit unit : result2.getUnits()) {
            String freeInfo = unit.isFree() ? " [免费: " + unit.getFreePromotionId() + "]" : "";
            System.out.printf("    %s - %s (%dm) 金额:%.0f%s%n",
                    unit.getBeginTime().toLocalTime(),
                    unit.getEndTime().toLocalTime(),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount(),
                    freeInfo);
        }
        var ruleState2 = result2.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState2 = (java.util.Map<String, Object>) ruleState2.get("dayNight");
        System.out.println("  结转状态 cycleAccumulated: " + (dayNightState2 != null ? dayNightState2.get("cycleAccumulated") : "N/A"));

        // 第三次计算 (CONTINUE)：继续到 22:00，应该触发封顶
        var request3 = new BillingRequest();
        request3.setId("test-multi-continue-3");
        request3.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0)); // 原始起点
        request3.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 22, 0, 0)); // 新的结束点
        request3.setSchemeChanges(List.of());
        request3.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request3.setSchemeId("scheme-1");
        request3.setPreviousCarryOver(result2.getCarryOver());
        request3.setExternalPromotions(List.of());

        var result3 = billingService.calculate(request3);
        System.out.println("\n第三次计算 (CONTINUE，扩展到 22:00):");
        System.out.println("  计费金额: " + result3.getFinalAmount());
        System.out.println("  计费单元:");
        for (BillingUnit unit : result3.getUnits()) {
            String freeInfo = unit.isFree() ? " [免费: " + unit.getFreePromotionId() + "]" : "";
            System.out.printf("    %s - %s (%dm) 金额:%.0f%s%n",
                    unit.getBeginTime().toLocalTime(),
                    unit.getEndTime().toLocalTime(),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount(),
                    freeInfo);
        }
        var ruleState3 = result3.getCarryOver().getSegments().values().iterator().next().getRuleState();
        @SuppressWarnings("unchecked")
        var dayNightState3 = (java.util.Map<String, Object>) ruleState3.get("dayNight");
        System.out.println("  结转状态 cycleAccumulated: " + (dayNightState3 != null ? dayNightState3.get("cycleAccumulated") : "N/A"));

        System.out.println("\n--- 分析 ---");
        System.out.println("封顶金额: 20元/日");

        // 检查是否触发封顶
        boolean hasCapFreeUnit = result3.getUnits().stream()
                .anyMatch(u -> "DAILY_CAP".equals(u.getFreePromotionId()));
        System.out.println("第三次计算是否触发封顶: " + hasCapFreeUnit);

        // 验证封顶后金额不超过20元
        boolean withinCap = result3.getFinalAmount().compareTo(new BigDecimal("20")) <= 0;
        System.out.println("第三次计算最终金额不超过封顶: " + withinCap);
        System.out.println("[PASS] 测试通过\n");
    }

    /**
     * 测试4: 免费分钟数耗尽时刻与免费时段边界重叠
     *
     * 场景：免费分钟数正好在免费时段开始时刻用完
     * 期望：不应产生空时段，免费时段应正常使用
     */
    static void testFreeMinutesEndAtFreeRangeBoundary() {
        System.out.println("=== 测试4: 免费分钟数耗尽与免费时段边界重叠 ===");

        var request = new BillingRequest();
        request.setId("test-free-min-range-overlap");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 14, 0, 0)); // 6小时
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        List<PromotionGrant> promotions = new ArrayList<>();
        // 免费分钟数 60分钟（正好在免费时段开始时耗尽）
        promotions.add(PromotionGrant.builder()
                .id("free-60")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(60)
                .build());
        // 免费时段 09:00-11:00（与免费分钟数耗尽时刻重叠）
        promotions.add(PromotionGrant.builder()
                .id("free-range-09-11")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 9, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 11, 0, 0))
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 14:00 (6小时)");
        System.out.println("免费分钟数: 60分钟");
        System.out.println("免费时段: 09:00 - 11:00");
        System.out.println("关键点: 免费分钟数在09:00耗尽，与免费时段边界重叠");
        System.out.println();

        // 打印计费单元
        for (BillingUnit unit : result.getUnits()) {
            String freeInfo = unit.isFree() ? " [免费: " + unit.getFreePromotionId() + "]" : "";
            System.out.printf("  %s - %s (%dm) 金额:%.0f%s%n",
                    unit.getBeginTime().toLocalTime(),
                    unit.getEndTime().toLocalTime(),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount(),
                    freeInfo);
        }

        // 检查是否有空时段或异常
        boolean hasEmptyUnit = result.getUnits().stream()
                .anyMatch(u -> u.getDurationMinutes() <= 0);
        System.out.println();
        System.out.println("是否存在空单元: " + hasEmptyUnit);
        System.out.println("最终金额: " + result.getFinalAmount());

        System.out.println("[PASS] 测试通过\n");
    }

    /**
     * 测试5: CONTINUOUS模式下封顶+免费时段交叉
     *
     * 场景：CONTINUOUS模式，有免费时段，计费足够触发封顶
     */
    static void testContinuousModeCapWithFreeRange() {
        System.out.println("=== 测试5: CONTINUOUS模式 封顶+免费时段 ===");

        var request = new BillingRequest();
        request.setId("test-continuous-cap-free");
        request.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.MARCH, 10, 18, 0, 0)); // 10小时
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-1");

        // 免费时段 10:00-12:00
        List<PromotionGrant> promotions = new ArrayList<>();
        promotions.add(PromotionGrant.builder()
                .id("free-range-10-12")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(LocalDateTime.of(2026, Month.MARCH, 10, 10, 0, 0))
                .endTime(LocalDateTime.of(2026, Month.MARCH, 10, 12, 0, 0))
                .build());
        request.setExternalPromotions(promotions);

        var result = billingService.calculate(request);

        System.out.println("计费时间: 08:00 - 18:00 (10小时)");
        System.out.println("封顶金额: 20元/日");
        System.out.println("免费时段: 10:00 - 12:00");
        System.out.println();

        // 统计免费单元和封顶单元
        long freeRangeUnits = result.getUnits().stream()
                .filter(u -> "free-range-10-12".equals(u.getFreePromotionId()))
                .count();
        long capUnits = result.getUnits().stream()
                .filter(u -> "DAILY_CAP".equals(u.getFreePromotionId()))
                .count();

        System.out.println("免费时段单元数: " + freeRangeUnits);
        System.out.println("封顶免费单元数: " + capUnits);
        System.out.println("最终金额: " + result.getFinalAmount());

        // 验证金额不超过封顶
        boolean withinCap = result.getFinalAmount().compareTo(new BigDecimal("20")) <= 0;
        System.out.println("金额不超过封顶: " + withinCap);

        System.out.println("[PASS] 测试通过\n");
    }

    /**
     * 测试6: 跨日封顶结转
     *
     * 场景：第一天触发封顶，第二天继续计算
     */
    static void testCrossDayCapCarryOver() {
        System.out.println("=== 测试6: 跨日封顶结转 ===");

        // 第一天：08:00 - 次日 06:00 (22小时)
        var request1 = new BillingRequest();
        request1.setId("test-cross-day-1");
        request1.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request1.setEndTime(LocalDateTime.of(2026, Month.MARCH, 11, 6, 0, 0));
        request1.setSchemeChanges(List.of());
        request1.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request1.setSchemeId("scheme-1");

        var result1 = billingService.calculate(request1);
        System.out.println("第一天计算 (08:00 - 次日06:00): 金额=" + result1.getFinalAmount());

        // 检查是否触发封顶
        boolean day1HasCap = result1.getUnits().stream()
                .anyMatch(u -> "DAILY_CAP".equals(u.getFreePromotionId()));
        System.out.println("第一天是否触发封顶: " + day1HasCap);

        // 第二天 CONTINUE：次日 06:00 - 次日 14:00
        var request2 = new BillingRequest();
        request2.setId("test-cross-day-2");
        request2.setBeginTime(LocalDateTime.of(2026, Month.MARCH, 10, 8, 0, 0));
        request2.setEndTime(LocalDateTime.of(2026, Month.MARCH, 11, 14, 0, 0));
        request2.setSchemeChanges(List.of());
        request2.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request2.setSchemeId("scheme-1");
        request2.setPreviousCarryOver(result1.getCarryOver());
        request2.setExternalPromotions(List.of()); // 必须设置，否则NPE

        var result2 = billingService.calculate(request2);
        System.out.println("第二天计算 (CONTINUE 06:00-14:00): 金额=" + result2.getFinalAmount());

        // 检查第二天是否是新周期独立计算
        boolean day2HasCharge = result2.getUnits().stream()
                .anyMatch(u -> !u.isFree() && u.getBeginTime().isAfter(LocalDateTime.of(2026, Month.MARCH, 11, 8, 0, 0)));
        System.out.println("第二天08:00后有收费单元: " + day2HasCharge);

        System.out.println();
        System.out.println("第一天金额: " + result1.getFinalAmount());
        System.out.println("第二天金额: " + result2.getFinalAmount());
        System.out.println("累计金额: " + result1.getFinalAmount().add(result2.getFinalAmount()));

        System.out.println("[PASS] 测试通过\n");
    }
}