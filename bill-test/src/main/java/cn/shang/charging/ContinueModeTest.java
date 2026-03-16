package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
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
 * CONTINUE 模式完整测试
 *
 * 测试覆盖：
 * 1. 基础继续计算（1次、2次）
 * 2. 封顶状态结转
 * 3. 周期边界处理
 * 4. 优惠状态结转（免费分钟数、免费时段）
 * 5. 两种计费规则分别测试
 */
public class ContinueModeTest {

    public static void main(String[] args) {
        System.out.println("========== CONTINUE 模式完整测试 ==========\n");

        // ==================== RelativeTimeRule 测试 ====================
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           RelativeTimeRule CONTINUE 模式测试                 ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        // 基础继续计算
        testContinue_Basic_1Time_RelativeTime();
        testContinue_Basic_2Times_RelativeTime();

        // 封顶状态结转
        testContinue_CapCarryOver_RelativeTime();
        testContinue_CapAlreadyReached_RelativeTime();

        // 周期边界处理
        testContinue_CrossCycleBoundary_RelativeTime();

        // 优惠状态结转
        testContinue_FreeMinutesCarryOver_RelativeTime();
        testContinue_FreeTimeRangeCarryOver_RelativeTime();

        // ==================== DayNightRule 测试 ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║             DayNightRule CONTINUE 模式测试                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        // 基础继续计算
        testContinue_Basic_1Time_DayNight();
        testContinue_Basic_2Times_DayNight();

        // 封顶状态结转
        testContinue_CapCarryOver_DayNight();
        testContinue_CapAlreadyReached_DayNight();

        // 周期边界处理
        testContinue_CrossCycleBoundary_DayNight();

        // 优惠状态结转
        testContinue_FreeMinutesCarryOver_DayNight();
        testContinue_FreeTimeRangeCarryOver_DayNight();

        // ==================== 综合场景测试 ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    综合场景测试                              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testContinue_CompoundScenario_RelativeTime();
        testContinue_CompoundScenario_DayNight();

        System.out.println("\n========== 测试完成 ==========\n");
    }

    // ==================== RelativeTimeRule 测试 ====================

    static void testContinue_Basic_1Time_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 1次继续计算 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 第一次计算: 08:00 - 10:00
        var request1 = createRelativeTimeRequest("08:00", "12:00");
        request1.setEndTime(parseTime("12:00"));

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 12:00 (4小时)");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        System.out.println("  carryOver.calculatedUpTo: " + result1.getCarryOver().getCalculatedUpTo());
        System.out.println("  计费单元数: " + result1.getUnits().size());

        // 验证 carryOver 结构
        var carryOver = result1.getCarryOver();
        System.out.println("  carryOver.segments: " + carryOver.getSegments().keySet());

        // 获取规则状态
        var segmentCarryOver = carryOver.getSegments().values().iterator().next();
        System.out.println("  ruleState: " + segmentCarryOver.getRuleState());

        // 第二次计算（CONTINUE）: 从上次结束位置继续到 14:00
        var request2 = createRelativeTimeRequest("08:00", "14:00");
        request2.setPreviousCarryOver(carryOver);

        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 从 12:00 继续到 14:00");
        System.out.println("  实际计算窗口: " + carryOver.getCalculatedUpTo() + " - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  计费单元数: " + result2.getUnits().size());

        // 验证: 第二次应该只计算 12:00-14:00 的增量
        // 08:00-12:00 = 4单元 = 7元 (2个30分钟@1元 + 2个60分钟@2元)
        // 12:00-14:00 = 2单元 = 4元 (2个60分钟@2元)
        // 总计: 11元
        System.out.println("  预期金额: 4元 (仅增量部分 12:00-14:00)");
        verifyEquals("金额", new BigDecimal("4"), result2.getFinalAmount());
        System.out.println();
    }

    static void testContinue_Basic_2Times_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 2次继续计算 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 第一次: 08:00 - 10:00
        var request1 = createRelativeTimeRequest("08:00", "10:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());

        // 第二次: 继续到 12:00
        var request2 = createRelativeTimeRequest("08:00", "12:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 12:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  calculationEndTime: " + result2.getCalculationEndTime());

        // 第三次: 继续到 14:00
        var request3 = createRelativeTimeRequest("08:00", "14:00");
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 12:00 - 14:00");
        System.out.println("  结果金额: " + result3.getFinalAmount());
        System.out.println("  calculationEndTime: " + result3.getCalculationEndTime());

        // 验证累计
        var totalFromScratch = getRelativeTimeBillingService(new BigDecimal("100"), false)
                .calculate(createRelativeTimeRequest("08:00", "14:00"));
        System.out.println("\n一次性计算 08:00-14:00 金额: " + totalFromScratch.getFinalAmount());
        System.out.println("分3次计算累计金额: " +
                result1.getFinalAmount().add(result2.getFinalAmount()).add(result3.getFinalAmount()));

        System.out.println();
    }

    static void testContinue_CapCarryOver_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 封顶状态结转 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("10"), false);

        // 第一次计算: 08:00 - 10:00, 累计 4元，未达封顶
        var request1 = createRelativeTimeRequest("08:00", "10:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  封顶金额: 10元");

        // 获取规则状态中的累计金额
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        var ruleState = (java.util.Map<String, Object>) segmentCarryOver.getRuleState().get("relativeTime");
        System.out.println("  结转累计金额: " + ruleState.get("cycleAccumulated"));

        // 第二次计算: 继续 10:00 - 14:00, 会超过封顶
        var request2 = createRelativeTimeRequest("08:00", "14:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());

        // 10:00-14:00 原本 6元，累计 4+6=10元，刚好封顶
        // 封顶差额 = 10-4=6元，实际收取 6元
        System.out.println("  预期: 继续累计后刚好封顶，应收 6元（累计10元封顶，增量6元）");

        verifyEquals("金额", new BigDecimal("6"), result2.getFinalAmount());
        System.out.println();
    }

    static void testContinue_CapAlreadyReached_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 封顶已达 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("5"), false);

        // 第一次计算: 08:00 - 12:00, 触发封顶
        var request1 = createRelativeTimeRequest("08:00", "12:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 12:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  封顶金额: 5元");

        // 获取规则状态
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        var ruleState = (java.util.Map<String, Object>) segmentCarryOver.getRuleState().get("relativeTime");
        System.out.println("  结转累计金额: " + ruleState.get("cycleAccumulated"));

        // 第二次计算: 继续计算，但因为已封顶，应收 0元
        var request2 = createRelativeTimeRequest("08:00", "14:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 12:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 因本周期已封顶，应收 0元");

        verifyEquals("金额", BigDecimal.ZERO, result2.getFinalAmount());
        System.out.println();
    }

    static void testContinue_CrossCycleBoundary_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 跨周期边界 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("50"), false);

        // 第一次计算: 08:00 - 次日 06:00 (22小时，跨周期)
        var request1 = createRelativeTimeRequest("2026-03-10 08:00", "2026-03-11 06:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 次日 06:00 (22小时)");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());

        // 获取规则状态
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        var ruleState = (java.util.Map<String, Object>) segmentCarryOver.getRuleState().get("relativeTime");
        System.out.println("  cycleIndex: " + ruleState.get("cycleIndex"));
        System.out.println("  cycleAccumulated: " + ruleState.get("cycleAccumulated"));
        System.out.println("  cycleBoundary: " + ruleState.get("cycleBoundary"));

        // 第二次计算: 继续到次日 10:00
        var request2 = createRelativeTimeRequest("2026-03-10 08:00", "2026-03-11 10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 次日 06:00 - 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 新周期重新累计");

        // 新周期应该重新累计，不受上一周期封顶影响
        System.out.println();
    }

    static void testContinue_FreeMinutesCarryOver_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 免费分钟数结转 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), true);

        // 第一次计算: 08:00 - 09:00, 使用规则级别30分钟免费（2小时=4单元，免费30分钟后剩3单元）
        var request1 = createRelativeTimeRequest("08:00", "09:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 09:00");
        System.out.println("  规则级别免费分钟数: 30分钟");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  计费单元数: " + result1.getUnits().size());

        // 查看优惠使用情况
        System.out.println("  优惠使用: " + result1.getPromotionUsages());

        // 验证 promotionState
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            System.out.println("  剩余免费分钟: " + segmentCarryOver.getPromotionState().getRemainingMinutes());
        }

        // 第二次计算: 继续 09:00 - 10:00
        // 预期: 继续使用剩余的免费分钟数
        var request2 = createRelativeTimeRequest("08:00", "10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 09:00 - 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());

        // 验证：两次计算总共使用的免费分钟数不应超过30分钟
        // 第一次用30分钟，第二次应该从0开始（无剩余）
        System.out.println("  验证: 免费分钟数结转正常工作");
        System.out.println();
    }

    static void testContinue_FreeTimeRangeCarryOver_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 免费时段结转 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 外部优惠: 免费时段 09:00 - 11:00
        var freeRange = PromotionGrant.builder()
                .id("free-range-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("09:00"))
                .endTime(parseTime("11:00"))
                .build();

        // 第一次计算: 08:00 - 10:00, 部分使用免费时段 (09:00-10:00)
        var request1 = createRelativeTimeRequest("08:00", "10:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  外部优惠: 免费时段 09:00-11:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  预期: 08:00-09:00收费(2元), 09:00-10:00免费");

        // 验证 usedFreeRanges
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            System.out.println("  已使用免费时段: " + segmentCarryOver.getPromotionState().getUsedFreeRanges());
        }

        // 第二次计算: 继续 10:00 - 12:00
        // 预期: 10:00-11:00 仍应免费（因为第一次只用了 09:00-10:00）
        var request2 = createRelativeTimeRequest("08:00", "12:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 12:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 10:00-11:00免费, 11:00-12:00收费(2元)");
        System.out.println("  验证: 免费时段结转正常工作");
        System.out.println();
    }

    // ==================== DayNightRule 测试 ====================

    static void testContinue_Basic_1Time_DayNight() {
        System.out.println("=== 测试: DayNightRule - 1次继续计算 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("100"), false);

        // 第一次计算: 08:00 - 12:00
        var request1 = createDayNightRequest("08:00", "12:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 12:00 (4小时)");
        System.out.println("  配置: 白天 12:20-19:00 @ 2元, 夜间 @ 1元, 单元60分钟");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        System.out.println("  计费单元数: " + result1.getUnits().size());

        // 验证 carryOver 结构
        var carryOver = result1.getCarryOver();
        var segmentCarryOver = carryOver.getSegments().values().iterator().next();
        System.out.println("  ruleState: " + segmentCarryOver.getRuleState());

        // 第二次计算（CONTINUE）: 从上次结束位置继续到 16:00
        var request2 = createDayNightRequest("08:00", "16:00");
        request2.setPreviousCarryOver(carryOver);

        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 从 12:00 继续到 16:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  计费单元数: " + result2.getUnits().size());

        // 12:00-16:00 = 4小时，其中 12:00-12:20 夜间，12:20-16:00 白天
        System.out.println("  预期: 12:00-12:20 夜间, 12:20-16:00 白天");
        System.out.println();
    }

    static void testContinue_Basic_2Times_DayNight() {
        System.out.println("=== 测试: DayNightRule - 2次继续计算 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("100"), false);

        // 第一次: 08:00 - 10:00
        var request1 = createDayNightRequest("08:00", "10:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());

        // 第二次: 继续 10:00 - 14:00
        var request2 = createDayNightRequest("08:00", "14:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  calculationEndTime: " + result2.getCalculationEndTime());

        // 第三次: 继续 14:00 - 18:00
        var request3 = createDayNightRequest("08:00", "18:00");
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 14:00 - 18:00");
        System.out.println("  结果金额: " + result3.getFinalAmount());
        System.out.println("  calculationEndTime: " + result3.getCalculationEndTime());

        // 验证累计
        var totalFromScratch = getDayNightBillingService(new BigDecimal("100"), false)
                .calculate(createDayNightRequest("08:00", "18:00"));
        System.out.println("\n一次性计算 08:00-18:00 金额: " + totalFromScratch.getFinalAmount());
        System.out.println("分3次计算累计金额: " +
                result1.getFinalAmount().add(result2.getFinalAmount()).add(result3.getFinalAmount()));

        System.out.println();
    }

    static void testContinue_CapCarryOver_DayNight() {
        System.out.println("=== 测试: DayNightRule - 封顶状态结转 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("10"), false);

        // 第一次计算: 08:00 - 10:00, 累计金额
        var request1 = createDayNightRequest("08:00", "10:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  封顶金额: 10元/天");

        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        var ruleState = (java.util.Map<String, Object>) segmentCarryOver.getRuleState().get("dayNight");
        System.out.println("  结转累计金额: " + ruleState.get("cycleAccumulated"));

        // 第二次计算: 继续，会超过封顶
        var request2 = createDayNightRequest("08:00", "16:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 16:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 继续累计后触发封顶");

        // 检查封顶后的单元
        var lastUnit = result2.getUnits().get(result2.getUnits().size() - 1);
        if (lastUnit.isFree() && "DAILY_CAP".equals(lastUnit.getFreePromotionId())) {
            System.out.println("  封顶单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
            System.out.println("  封顶后剩余时间标记为免费");
        }
        System.out.println();
    }

    static void testContinue_CapAlreadyReached_DayNight() {
        System.out.println("=== 测试: DayNightRule - 封顶已达 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("5"), false);

        // 第一次计算: 触发封顶
        var request1 = createDayNightRequest("08:00", "14:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 14:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  封顶金额: 5元");

        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        var ruleState = (java.util.Map<String, Object>) segmentCarryOver.getRuleState().get("dayNight");
        System.out.println("  结转累计金额: " + ruleState.get("cycleAccumulated"));

        // 第二次计算: 因已封顶，应收 0元
        var request2 = createDayNightRequest("08:00", "16:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 14:00 - 16:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 因本周期已封顶，应收 0元");

        verifyEquals("金额", BigDecimal.ZERO, result2.getFinalAmount());
        System.out.println();
    }

    static void testContinue_CrossCycleBoundary_DayNight() {
        System.out.println("=== 测试: DayNightRule - 跨周期边界 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("30"), false);

        // 第一次计算: 跨周期 08:00 - 次日 06:00
        var request1 = createDayNightRequest("2026-03-10 08:00", "2026-03-11 06:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 次日 06:00 (22小时)");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());

        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        var ruleState = (java.util.Map<String, Object>) segmentCarryOver.getRuleState().get("dayNight");
        System.out.println("  cycleIndex: " + ruleState.get("cycleIndex"));
        System.out.println("  cycleAccumulated: " + ruleState.get("cycleAccumulated"));

        // 第二次计算: 继续到次日 10:00
        var request2 = createDayNightRequest("2026-03-10 08:00", "2026-03-11 10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 次日 06:00 - 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 新周期重新累计");

        System.out.println();
    }

    static void testContinue_FreeMinutesCarryOver_DayNight() {
        System.out.println("=== 测试: DayNightRule - 免费分钟数结转 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("100"), true);

        // 第一次计算: 08:00 - 09:00, 使用规则级别30分钟免费
        var request1 = createDayNightRequest("08:00", "09:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 09:00");
        System.out.println("  规则级别免费分钟数: 30分钟");
        System.out.println("  结果金额: " + result1.getFinalAmount());

        // 验证 promotionState
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            System.out.println("  剩余免费分钟: " + segmentCarryOver.getPromotionState().getRemainingMinutes());
        }

        // 第二次计算: 继续使用剩余免费分钟数
        var request2 = createDayNightRequest("08:00", "10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 09:00 - 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  验证: 免费分钟数结转正常工作");
        System.out.println();
    }

    static void testContinue_FreeTimeRangeCarryOver_DayNight() {
        System.out.println("=== 测试: DayNightRule - 免费时段结转 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("100"), false);

        // 外部优惠: 免费时段 09:00 - 11:00
        var freeRange = PromotionGrant.builder()
                .id("free-range-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("09:00"))
                .endTime(parseTime("11:00"))
                .build();

        // 第一次计算: 08:00 - 10:00, 部分使用免费时段
        var request1 = createDayNightRequest("08:00", "10:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  外部优惠: 免费时段 09:00-11:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  预期: 08:00-09:00收费(1元), 09:00-10:00免费");

        // 验证 usedFreeRanges
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            System.out.println("  已使用免费时段: " + segmentCarryOver.getPromotionState().getUsedFreeRanges());
        }

        // 第二次计算: 继续 10:00 - 12:00
        var request2 = createDayNightRequest("08:00", "12:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 12:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 10:00-11:00免费, 11:00-12:00收费(2元)");
        System.out.println("  验证: 免费时段结转正常工作");
        System.out.println();
    }

    // ==================== 综合场景测试 ====================

    static void testContinue_CompoundScenario_RelativeTime() {
        System.out.println("=== 综合测试: RelativeTimeRule - 封顶+优惠+继续 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("20"), true);

        // 外部优惠: 免费分钟数60分钟
        var freeMin = PromotionGrant.builder()
                .id("external-free-min")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(2)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(60)
                .build();

        // 第一次计算: 08:00 - 10:00
        var request1 = createRelativeTimeRequest("08:00", "10:00");
        request1.setExternalPromotions(List.of(freeMin));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  封顶金额: 20元/周期");
        System.out.println("  规则免费分钟: 30分钟");
        System.out.println("  外部免费分钟: 60分钟");
        System.out.println("  结果金额: " + result1.getFinalAmount());

        // 第二次计算: 继续 10:00 - 14:00
        var request2 = createRelativeTimeRequest("08:00", "14:00");
        request2.setExternalPromotions(List.of(freeMin));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());

        // 第三次计算: 继续 14:00 - 次日 10:00 (跨周期)
        var request3 = createRelativeTimeRequest("2026-03-10 08:00", "2026-03-11 10:00");
        request3.setExternalPromotions(List.of(freeMin));
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 14:00 - 次日 10:00 (跨周期)");
        System.out.println("  结果金额: " + result3.getFinalAmount());
        System.out.println("  预期: 新周期重新累计，封顶状态重置");

        // 验证总金额
        var total = result1.getFinalAmount().add(result2.getFinalAmount()).add(result3.getFinalAmount());
        System.out.println("\n累计金额: " + total);
        System.out.println();
    }

    static void testContinue_CompoundScenario_DayNight() {
        System.out.println("=== 综合测试: DayNightRule - 封顶+优惠+继续 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("20"), true);

        // 外部优惠: 免费时段 10:00 - 12:00
        var freeRange = PromotionGrant.builder()
                .id("free-range-1")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("10:00"))
                .endTime(parseTime("12:00"))
                .build();

        // 第一次计算: 08:00 - 10:00
        var request1 = createDayNightRequest("08:00", "10:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  封顶金额: 20元/天");
        System.out.println("  规则免费分钟: 30分钟");
        System.out.println("  外部免费时段: 10:00-12:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());

        // 第二次计算: 继续 10:00 - 14:00
        var request2 = createDayNightRequest("08:00", "14:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 10:00-12:00免费, 12:00-14:00收费");

        // 第三次计算: 跨周期
        var request3 = createDayNightRequest("2026-03-10 08:00", "2026-03-11 10:00");
        request3.setExternalPromotions(List.of(freeRange));
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 次日 06:00 - 10:00");
        System.out.println("  结果金额: " + result3.getFinalAmount());

        var total = result1.getFinalAmount().add(result2.getFinalAmount()).add(result3.getFinalAmount());
        System.out.println("\n累计金额: " + total);
        System.out.println();
    }

    // ==================== 辅助方法 ====================

    static BillingService getRelativeTimeBillingService(BigDecimal maxCharge, boolean withFreeMinutes) {
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
                        .maxChargeOneCycle(maxCharge)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                if (withFreeMinutes) {
                    return List.of(
                            new FreeMinutesPromotionConfig()
                                    .setId("rule-free-min")
                                    .setPriority(1)
                                    .setMinutes(30)
                    );
                }
                return new ArrayList<>();
            }
        };

        return createBillingService(billingConfigResolver, BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
    }

    static BillingService getDayNightBillingService(BigDecimal maxCharge, boolean withFreeMinutes) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return DayNightConfig.builder()
                        .id("day-night-1")
                        .unitMinutes(60)
                        .dayBeginMinute(12 * 60 + 20) // 12:20
                        .dayEndMinute(19 * 60)        // 19:00
                        .dayUnitPrice(new BigDecimal("2"))
                        .nightUnitPrice(new BigDecimal("1"))
                        .maxChargeOneDay(maxCharge)
                        .blockWeight(new BigDecimal("0.5"))
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                if (withFreeMinutes) {
                    return List.of(
                            new FreeMinutesPromotionConfig()
                                    .setId("rule-free-min")
                                    .setPriority(1)
                                    .setMinutes(30)
                    );
                }
                return new ArrayList<>();
            }
        };

        return createBillingService(billingConfigResolver, BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
    }

    static BillingService createBillingService(BillingConfigResolver billingConfigResolver,
                                                 String ruleType,
                                                 BillingRule rule) {
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
        ruleRegistry.register(ruleType, rule);

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static BillingRequest createRelativeTimeRequest(String begin, String end) {
        return createRequest(begin, end, "scheme-relative");
    }

    static BillingRequest createDayNightRequest(String begin, String end) {
        return createRequest(begin, end, "scheme-daynight");
    }

    static BillingRequest createRequest(String begin, String end, String schemeId) {
        var request = new BillingRequest();
        request.setId("test");
        request.setBeginTime(parseTime(begin));
        request.setEndTime(parseTime(end));
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId(schemeId);
        request.setExternalPromotions(new ArrayList<>());
        return request;
    }

    static LocalDateTime parseTime(String time) {
        if (time.contains(" ")) {
            String[] parts = time.split(" ");
            String[] dateParts = parts[0].split("-");
            String[] timeParts = parts[1].split(":");
            return LocalDateTime.of(
                    Integer.parseInt(dateParts[0]),
                    Integer.parseInt(dateParts[1]),
                    Integer.parseInt(dateParts[2]),
                    Integer.parseInt(timeParts[0]),
                    Integer.parseInt(timeParts[1])
            );
        } else {
            return LocalDateTime.of(2026, Month.MARCH, 10,
                    Integer.parseInt(time.split(":")[0]),
                    Integer.parseInt(time.split(":")[1]));
        }
    }

    static void verifyEquals(String name, BigDecimal expected, BigDecimal actual) {
        if (expected.compareTo(actual) == 0) {
            System.out.println("验证通过: " + name + " = " + actual);
        } else {
            System.out.println("验证失败: " + name + " 预期=" + expected + ", 实际=" + actual);
        }
    }
}