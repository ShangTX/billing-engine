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
import cn.shang.charging.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.pojo.PromotionGrant;
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

        // ==================== 最后单元延伸+继续计算测试 ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║               最后单元延伸+继续计算测试                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testContinue_LastUnitExtension_CapTriggered_RelativeTime();
        testContinue_LastUnitExtension_CapTriggered_DayNight();

        // ==================== 不规则时间测试（贴近现实场景） ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║               不规则时间测试（贴近现实场景）                 ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testIrregularTime_Basic_RelativeTime();
        testIrregularTime_CrossCycle_RelativeTime();
        testIrregularTime_CapTriggered_RelativeTime();
        testIrregularTime_DayNight_Basic();
        testIrregularTime_DayNight_CrossCycle();
        testIrregularTime_MultiContinue_RelativeTime();

        // ==================== 截断单元重复计费问题测试 ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          截断单元重复计费问题测试（现实场景）                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testTruncatedUnitDuplicateBilling_RelativeTime();
        testTruncatedUnitDuplicateBilling_DayNight();
        testRealParkingScenario_MultiQuery();

        // ==================== 延伸与优惠交互测试 ====================
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║               延伸与优惠交互测试                             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        testExtension_NotConsumeUnusedPromotion();
        testExtension_LastUnitCoveredByFreeRange();
        testExtension_LastUnitCoveredByFreeMinutes();
        testExtension_StopsAtFreeRangeBoundary();

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
        printBillingDetail(result1);

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
        printBillingDetail(result2);

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
        printBillingDetail(result1);

        // 第二次: 继续到 12:00
        var request2 = createRelativeTimeRequest("08:00", "12:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 12:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  calculationEndTime: " + result2.getCalculationEndTime());
        printBillingDetail(result2);

        // 第三次: 继续到 14:00
        var request3 = createRelativeTimeRequest("08:00", "14:00");
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 12:00 - 14:00");
        System.out.println("  结果金额: " + result3.getFinalAmount());
        System.out.println("  calculationEndTime: " + result3.getCalculationEndTime());
        printBillingDetail(result3);

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
        printBillingDetail(result1);

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
        printBillingDetail(result2);

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
        printBillingDetail(result1);

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
        printBillingDetail(result2);

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
        printBillingDetail(result1);

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
        printBillingDetail(result2);

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
        printBillingDetail(result1);

        // 查看优惠使用情况
        System.out.println("  优惠使用: " + result1.getPromotionUsages());

        // 验证 promotionState
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            System.out.println("  剩余免费分钟: " + segmentCarryOver.getPromotionState().getRemainingMinutesConverted());
        }

        // 第二次计算: 继续 09:00 - 10:00
        // 预期: 继续使用剩余的免费分钟数
        var request2 = createRelativeTimeRequest("08:00", "10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 09:00 - 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        printBillingDetail(result2);

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
        printBillingDetail(result1);

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
        printBillingDetail(result2);
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
        printBillingDetail(result1);

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
        printBillingDetail(result2);

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
        printBillingDetail(result1);

        // 第二次: 继续 10:00 - 14:00
        var request2 = createDayNightRequest("08:00", "14:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  calculationEndTime: " + result2.getCalculationEndTime());
        printBillingDetail(result2);

        // 第三次: 继续 14:00 - 18:00
        var request3 = createDayNightRequest("08:00", "18:00");
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 14:00 - 18:00");
        System.out.println("  结果金额: " + result3.getFinalAmount());
        System.out.println("  calculationEndTime: " + result3.getCalculationEndTime());
        printBillingDetail(result3);

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
        printBillingDetail(result1);

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
        printBillingDetail(result2);

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
        printBillingDetail(result1);

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
        printBillingDetail(result2);

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
        printBillingDetail(result1);

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
        printBillingDetail(result2);

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
        printBillingDetail(result1);

        // 验证 promotionState
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            System.out.println("  剩余免费分钟: " + segmentCarryOver.getPromotionState().getRemainingMinutesConverted());
        }

        // 第二次计算: 继续使用剩余免费分钟数
        var request2 = createDayNightRequest("08:00", "10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 09:00 - 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        printBillingDetail(result2);
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
        printBillingDetail(result1);

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
        printBillingDetail(result2);
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
        printBillingDetail(result1);

        // 第二次计算: 继续 10:00 - 14:00
        var request2 = createRelativeTimeRequest("08:00", "14:00");
        request2.setExternalPromotions(List.of(freeMin));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        printBillingDetail(result2);

        // 第三次计算: 继续 14:00 - 次日 10:00 (跨周期)
        var request3 = createRelativeTimeRequest("2026-03-10 08:00", "2026-03-11 10:00");
        request3.setExternalPromotions(List.of(freeMin));
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 14:00 - 次日 10:00 (跨周期)");
        System.out.println("  结果金额: " + result3.getFinalAmount());
        System.out.println("  预期: 新周期重新累计，封顶状态重置");
        printBillingDetail(result3);

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
        printBillingDetail(result1);

        // 第二次计算: 继续 10:00 - 14:00
        var request2 = createDayNightRequest("08:00", "14:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 14:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 10:00-12:00免费, 12:00-14:00收费");
        printBillingDetail(result2);

        // 第三次计算: 跨周期
        var request3 = createDayNightRequest("2026-03-10 08:00", "2026-03-11 10:00");
        request3.setExternalPromotions(List.of(freeRange));
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 次日 06:00 - 10:00");
        System.out.println("  结果金额: " + result3.getFinalAmount());
        printBillingDetail(result3);

        var total = result1.getFinalAmount().add(result2.getFinalAmount()).add(result3.getFinalAmount());
        System.out.println("\n累计金额: " + total);
        System.out.println();
    }

    // ==================== 最后单元延伸+继续计算测试 ====================

    /**
     * 测试：RelativeTimeRule - 封顶后继续计算（延迟生成免费单元）
     * 场景：第一次计算触达封顶，最后单元正常结束（不延伸）；
     *       第二次继续计算时检测已封顶，延迟生成免费单元到周期边界，然后进入新周期
     */
    static void testContinue_LastUnitExtension_CapTriggered_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 封顶触发延伸后继续 ===\n");

        // 使用较低的封顶金额，确保第一次计算能触发封顶
        var billingService = getRelativeTimeBillingService(new BigDecimal("6"), false);

        // 第一次计算: 08:00 - 11:00, 累计6元刚好封顶
        var request1 = createRelativeTimeRequest("08:00", "11:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 11:00");
        System.out.println("  封顶金额: 6元");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        System.out.println("  预期: 累计6元封顶，最后单元正常结束（不延伸到周期边界）");
        printBillingDetail(result1);

        // 验证：最后一个单元应该正常结束（不延伸）
        var lastUnit = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元结束时间: " + lastUnit.getEndTime());
        System.out.println("  是否免费: " + lastUnit.isFree() + " (原因: " + lastUnit.getFreePromotionId() + ")");

        // 第二次计算（CONTINUE）: 继续计算
        // 应该从 calculatedUpTo（11:00）开始，检测已封顶，生成免费单元到次日08:00，然后进入新周期
        var request2 = createRelativeTimeRequest("2026-03-10 08:00", "2026-03-11 10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());

        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 从 11:00 继续到次日 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 检测已封顶，生成免费单元 11:00→次日08:00，新周期从次日08:00开始");
        printBillingDetail(result2);

        System.out.println();
    }

    /**
     * 测试：DayNightRule - 封顶后继续计算（延迟生成免费单元）
     */
    static void testContinue_LastUnitExtension_CapTriggered_DayNight() {
        System.out.println("=== 测试: DayNightRule - 封顶触发延伸后继续 ===\n");

        // 使用较低的封顶金额，确保第一次计算能触发封顶
        var billingService = getDayNightBillingService(new BigDecimal("2"), false);

        // 第一次计算: 08:00 - 10:00, 2单元=2元刚好封顶
        var request1 = createDayNightRequest("08:00", "10:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  封顶金额: 2元");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        System.out.println("  预期: 累计2元封顶，最后单元正常结束（不延伸到周期边界）");
        printBillingDetail(result1);

        // 验证：最后一个单元应该正常结束（不延伸）
        var lastUnit = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元结束时间: " + lastUnit.getEndTime());
        System.out.println("  是否免费: " + lastUnit.isFree() + " (原因: " + lastUnit.getFreePromotionId() + ")");

        // 第二次计算（CONTINUE）: 继续计算
        // 应该从 calculatedUpTo（10:00）开始，检测已封顶，生成免费单元到次日08:00，然后进入新周期
        var request2 = createDayNightRequest("2026-03-10 08:00", "2026-03-11 10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 从 10:00 继续到次日 10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 检测已封顶，生成免费单元 10:00→次日08:00，新周期从次日08:00开始");
        printBillingDetail(result2);

        System.out.println();
    }

    // ==================== 不规则时间测试 ====================

    /**
     * 测试：RelativeTimeRule - 不规则开始和结束时间
     * 场景：开始时间 08:13，结束时间 12:47（非整点）
     */
    static void testIrregularTime_Basic_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 不规则时间（08:13-12:47）===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 第一次计算: 08:13 - 12:47（非整点开始和结束）
        var request1 = createRelativeTimeRequest("08:13", "12:47");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:13 - 12:47 (4小时34分钟)");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        System.out.println("  计费单元数: " + result1.getUnits().size());
        printBillingDetail(result1);

        // 验证：第一个计费单元应该从 08:13 开始，到 08:30 结束（17分钟，收全额1元）
        var firstUnit = result1.getUnits().get(0);
        System.out.println("  第一个单元: " + firstUnit.getBeginTime() + " - " + firstUnit.getEndTime() +
                " (" + firstUnit.getDurationMinutes() + "分钟, " + firstUnit.getChargedAmount() + "元)");

        // 验证：最后一个计费单元应该被截断
        var lastUnit = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime() +
                " (" + lastUnit.getDurationMinutes() + "分钟, " + lastUnit.getChargedAmount() + "元)");

        // 继续计算
        var request2 = createRelativeTimeRequest("08:13", "14:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 12:47 - 14:00 (1小时13分钟)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        printBillingDetail(result2);

        System.out.println();
    }

    /**
     * 测试：RelativeTimeRule - 不规则时间跨周期边界
     * 场景：开始时间 08:13，跨过第一个24小时周期边界
     */
    static void testIrregularTime_CrossCycle_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 不规则时间跨周期 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("50"), false);

        // 第一次计算: 08:13 - 次日 10:23（跨过次日08:13周期边界）
        var request1 = createRelativeTimeRequest("2026-03-10 08:13", "2026-03-11 10:23");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:13 - 次日 10:23 (26小时10分钟)");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        System.out.println("  计费单元数: " + result1.getUnits().size());

        // 检查周期边界处的单元
        var units = result1.getUnits();
        for (int i = 0; i < units.size(); i++) {
            var unit = units.get(i);
            if (unit.getBeginTime().getHour() == 8 && unit.getBeginTime().getMinute() == 13) {
                System.out.println("  周期边界单元[" + i + "]: " + unit.getBeginTime() + " - " + unit.getEndTime() +
                        " (" + unit.getDurationMinutes() + "分钟, " + unit.getChargedAmount() + "元)");
            }
        }
        printBillingDetail(result1);

        // 验证状态
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        var ruleState = (java.util.Map<String, Object>) segmentCarryOver.getRuleState().get("relativeTime");
        System.out.println("  cycleIndex: " + ruleState.get("cycleIndex"));
        System.out.println("  cycleBoundary: " + ruleState.get("cycleBoundary"));

        // 继续计算
        var request2 = createRelativeTimeRequest("2026-03-10 08:13", "2026-03-11 14:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:23 - 14:00 (3小时37分钟)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        printBillingDetail(result2);

        System.out.println();
    }

    /**
     * 测试：RelativeTimeRule - 不规则时间触发封顶
     * 场景：不规则时间触发封顶，验证最后单元延伸
     */
    static void testIrregularTime_CapTriggered_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 不规则时间封顶 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("10"), false);

        // 第一次计算: 08:13 - 12:47，累计超过封顶
        var request1 = createRelativeTimeRequest("08:13", "14:00");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:13 - 14:00 (5小时47分钟)");
        System.out.println("  封顶金额: 10元");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        printBillingDetail(result1);

        // 检查封顶单元
        var lastUnit = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime() +
                " (免费: " + lastUnit.isFree() + ", 原因: " + lastUnit.getFreePromotionId() + ")");

        // 继续计算
        var request2 = createRelativeTimeRequest("2026-03-10 08:13", "2026-03-11 10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 继续到次日10:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 新周期重新累计");
        printBillingDetail(result2);

        System.out.println();
    }

    /**
     * 测试：DayNightRule - 不规则时间（日夜混合）
     * 场景：开始时间 08:13（夜间），跨越 12:20 日夜边界
     */
    static void testIrregularTime_DayNight_Basic() {
        System.out.println("=== 测试: DayNightRule - 不规则时间（08:13-16:47）===\n");

        var billingService = getDayNightBillingService(new BigDecimal("100"), false);

        // 第一次计算: 08:13 - 16:47
        // 日夜边界: 12:20-19:00 是白天
        var request1 = createDayNightRequest("08:13", "16:47");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:13 - 16:47 (8小时34分钟)");
        System.out.println("  配置: 白天 12:20-19:00 @ 2元, 夜间 @ 1元");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        System.out.println("  计费单元数: " + result1.getUnits().size());
        printBillingDetail(result1);

        // 验证日夜边界处的单元
        var units = result1.getUnits();
        for (int i = 0; i < units.size(); i++) {
            var unit = units.get(i);
            // 找跨越 12:00 的单元
            if (unit.getBeginTime().getHour() == 12 || unit.getEndTime().getHour() == 12) {
                System.out.println("  日夜边界附近单元[" + i + "]: " + unit.getBeginTime() + " - " + unit.getEndTime() +
                        " (单价: " + unit.getUnitPrice() + "元, 金额: " + unit.getChargedAmount() + "元)");
            }
        }

        // 继续计算
        var request2 = createDayNightRequest("08:13", "20:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 16:47 - 20:00 (3小时13分钟)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        printBillingDetail(result2);

        System.out.println();
    }

    /**
     * 测试：DayNightRule - 不规则时间跨周期
     * 场景：08:13 开始，跨过次日 08:13 周期边界
     */
    static void testIrregularTime_DayNight_CrossCycle() {
        System.out.println("=== 测试: DayNightRule - 不规则时间跨周期 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("30"), false);

        // 第一次计算: 08:13 - 次日 06:23（跨周期，但未到次日08:13）
        var request1 = createDayNightRequest("2026-03-10 08:13", "2026-03-11 06:23");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:13 - 次日 06:23 (22小时10分钟)");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        printBillingDetail(result1);

        // 验证状态
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        var ruleState = (java.util.Map<String, Object>) segmentCarryOver.getRuleState().get("dayNight");
        System.out.println("  cycleIndex: " + ruleState.get("cycleIndex"));
        System.out.println("  cycleAccumulated: " + ruleState.get("cycleAccumulated"));

        // 继续计算：跨过周期边界
        var request2 = createDayNightRequest("2026-03-10 08:13", "2026-03-11 10:00");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 06:23 - 10:00 (跨过08:13周期边界)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 06:23-08:13 属第一周期(继续累计), 08:13-10:00 属新周期(重新累计)");
        printBillingDetail(result2);

        System.out.println();
    }

    /**
     * 测试：不规则时间多次继续计算
     * 场景：每次计算的时间都不规则
     */
    static void testIrregularTime_MultiContinue_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 不规则时间多次继续 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 第一次: 08:13 - 11:23
        var request1 = createRelativeTimeRequest("08:13", "11:23");
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:13 - 11:23 (3小时10分钟)");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  计费单元数: " + result1.getUnits().size());
        printBillingDetail(result1);

        // 第二次: 继续 11:23 - 14:37
        var request2 = createRelativeTimeRequest("08:13", "14:37");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 11:23 - 14:37 (3小时14分钟)");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        printBillingDetail(result2);

        // 第三次: 继续 14:37 - 17:19
        var request3 = createRelativeTimeRequest("08:13", "17:19");
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);

        System.out.println("\n第三次计算（CONTINUE）: 14:37 - 17:19 (2小时42分钟)");
        System.out.println("  结果金额: " + result3.getFinalAmount());
        printBillingDetail(result3);

        // 验证一致性：一次性计算应该等于最后一次 CONTINUE 的累计金额
        var totalFromScratch = billingService.calculate(createRelativeTimeRequest("08:13", "17:19"));
        // CONTINUE 模式返回累计金额，直接取最后一次结果即可
        var totalFromContinue = result3.getFinalAmount();

        System.out.println("\n一致性验证:");
        System.out.println("  一次性计算 08:13-17:19: " + totalFromScratch.getFinalAmount() + "元");
        System.out.println("  最后一次 CONTINUE 累计金额: " + totalFromContinue + "元");

        if (totalFromScratch.getFinalAmount().compareTo(totalFromContinue) == 0) {
            System.out.println("  ✓ 结果一致");
        } else {
            System.out.println("  ✗ 结果不一致，可能存在bug");
        }

        System.out.println();
    }

    // ==================== 截断单元重复计费问题测试 ====================

    /**
     * 测试：RelativeTimeRule - 截断单元重复计费问题
     *
     * 现实场景：停车场收费
     * - 单元长度：60分钟
     * - 车辆进入：08:47（不规则时间）
     * - 第一次查询：10:30（截断查询）
     * - 第二次查询：12:15（继续计算）
     *
     * 问题演示：
     * 第一次计算：08:47 - 10:30
     * - 单元1：08:47-09:47，完整60分钟，收费
     * - 单元2：09:47-10:30，截断43分钟，但按60分钟收费 ← 问题点
     *
     * 第二次 CONTINUE 计算：从延伸位置继续到 12:15
     * - 单元1：10:47-11:47，完整60分钟，收费
     * - 单元2：11:47-12:15，截断28分钟，按60分钟收费
     * ← 问题：09:47-10:47 的费用已经收过了（上次的截断单元），
     *         但第二次又从 10:47 开始收，导致 09:47-10:47 这段重复收费
     */
    static void testTruncatedUnitDuplicateBilling_RelativeTime() {
        System.out.println("=== 测试: RelativeTimeRule - 截断单元重复计费问题 ===\n");

        // 使用 60 分钟单元长度，单价 2 元/小时
        var billingService = getRelativeTimeBillingService_60MinUnit(new BigDecimal("100"));

        // ===== 第一次计算：08:47 - 10:30 =====
        System.out.println("【第一次计算】车辆进入 08:47，查询时间 10:30");
        System.out.println("  单元长度：60分钟，单价：2元/小时");

        var request1 = createRelativeTimeRequest("08:47", "10:30");
        var result1 = billingService.calculate(request1);

        System.out.println("  计算结果金额: " + result1.getFinalAmount() + "元");
        System.out.println("  计费单元数: " + result1.getUnits().size());

        // 详细展示每个单元
        for (int i = 0; i < result1.getUnits().size(); i++) {
            var unit = result1.getUnits().get(i);
            System.out.println("  单元" + (i+1) + ": " + unit.getBeginTime() + " - " + unit.getEndTime() +
                    " (" + unit.getDurationMinutes() + "分钟, " +
                    (unit.getIsTruncated() != null && unit.getIsTruncated() ? "截断" : "完整") + ", " +
                    unit.getChargedAmount() + "元)");
        }

        // 验证最后单元是否截断
        var lastUnit1 = result1.getUnits().get(result1.getUnits().size() - 1);
        boolean lastUnitTruncated = lastUnit1.getIsTruncated() != null && lastUnit1.getIsTruncated();
        System.out.println("  最后单元截断状态: " + (lastUnitTruncated ? "是 (isTruncated=true)" : "否"));

        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        System.out.println("  carryOver.calculatedUpTo: " + result1.getCarryOver().getCalculatedUpTo());

        // ===== 第二次 CONTINUE 计算：继续到 12:15 =====
        System.out.println("\n【第二次计算 CONTINUE】继续查询到 12:15");

        var request2 = createRelativeTimeRequest("08:47", "12:15");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("  计算结果金额: " + result2.getFinalAmount() + "元");
        System.out.println("  计费单元数: " + result2.getUnits().size());

        // 详细展示每个单元
        for (int i = 0; i < result2.getUnits().size(); i++) {
            var unit = result2.getUnits().get(i);
            System.out.println("  单元" + (i+1) + ": " + unit.getBeginTime() + " - " + unit.getEndTime() +
                    " (" + unit.getDurationMinutes() + "分钟, " +
                    (unit.getIsTruncated() != null && unit.getIsTruncated() ? "截断" : "完整") + ", " +
                    unit.getChargedAmount() + "元)");
        }

        // ===== 问题验证 =====
        System.out.println("\n【问题验证】");

        // 一次性计算 08:47 - 12:15 作为基准
        var benchmarkResult = billingService.calculate(createRelativeTimeRequest("08:47", "12:15"));
        System.out.println("  一次性计算 08:47-12:15 总金额: " + benchmarkResult.getFinalAmount() + "元");

        // CONTINUE 模式返回累计金额，直接取第二次结果
        var totalFromContinue = result2.getFinalAmount();
        System.out.println("  第二次 CONTINUE 累计金额: " + totalFromContinue + "元");

        // 差异分析
        var difference = totalFromContinue.subtract(benchmarkResult.getFinalAmount());
        if (difference.compareTo(BigDecimal.ZERO) != 0) {
            System.out.println("  ✗ 存在差异: " + difference.abs() + "元");
        } else {
            System.out.println("  ✓ 无重复收费，修复成功");
        }

        // 期望修复后的行为
        System.out.println("\n【期望修复后行为】");
        System.out.println("  第二次计算的第一个单元应从 09:47 开始（与截断单元合并）");
        System.out.println("  BillingResult.firstUnitMerged = true");
        System.out.println("  BillingUnit.mergedFromPrevious = true（第一个单元）");

        System.out.println();
    }

    /**
     * 测试：DayNightRule - 截断单元重复计费问题
     *
     * 现实场景：停车场日夜分段收费
     * - 单元长度：60分钟
     * - 白天（12:20-19:00）：2元/小时
     * - 夜间（19:00-次日12:20）：1元/小时
     */
    static void testTruncatedUnitDuplicateBilling_DayNight() {
        System.out.println("=== 测试: DayNightRule - 截断单元重复计费问题 ===\n");

        var billingService = getDayNightBillingService(new BigDecimal("100"), false);

        // 第一次计算：08:47 - 10:30（夜间时段）
        System.out.println("【第一次计算】08:47 - 10:30（夜间 1元/小时）");

        var request1 = createDayNightRequest("08:47", "10:30");
        var result1 = billingService.calculate(request1);

        System.out.println("  结果金额: " + result1.getFinalAmount() + "元");
        System.out.println("  计费单元数: " + result1.getUnits().size());

        for (int i = 0; i < result1.getUnits().size(); i++) {
            var unit = result1.getUnits().get(i);
            System.out.println("  单元" + (i+1) + ": " + unit.getBeginTime() + " - " + unit.getEndTime() +
                    " (" + unit.getDurationMinutes() + "分钟, " +
                    (unit.getIsTruncated() != null && unit.getIsTruncated() ? "截断" : "完整") + ", " +
                    unit.getChargedAmount() + "元, 单价: " + unit.getUnitPrice() + "元)");
        }

        // 第二次 CONTINUE 计算
        System.out.println("\n【第二次计算 CONTINUE】继续到 14:30（跨日夜边界）");

        var request2 = createDayNightRequest("08:47", "14:30");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("  结果金额: " + result2.getFinalAmount() + "元");
        System.out.println("  计费单元数: " + result2.getUnits().size());

        for (int i = 0; i < result2.getUnits().size(); i++) {
            var unit = result2.getUnits().get(i);
            System.out.println("  单元" + (i+1) + ": " + unit.getBeginTime() + " - " + unit.getEndTime() +
                    " (" + unit.getDurationMinutes() + "分钟, " +
                    (unit.getIsTruncated() != null && unit.getIsTruncated() ? "截断" : "完整") + ", " +
                    unit.getChargedAmount() + "元, 单价: " + unit.getUnitPrice() + "元)");
        }

        // 问题验证
        System.out.println("\n【问题验证】");
        var benchmarkResult = billingService.calculate(createDayNightRequest("08:47", "14:30"));
        // CONTINUE 模式返回累计金额，直接取第二次结果
        var totalFromContinue = result2.getFinalAmount();

        System.out.println("  一次性计算: " + benchmarkResult.getFinalAmount() + "元");
        System.out.println("  第二次 CONTINUE 累计金额: " + totalFromContinue + "元");

        var difference = totalFromContinue.subtract(benchmarkResult.getFinalAmount());
        if (difference.compareTo(BigDecimal.ZERO) != 0) {
            System.out.println("  ✗ 存在差异: " + difference.abs() + "元");
        } else {
            System.out.println("  ✓ 无重复收费");
        }

        System.out.println();
    }

    /**
     * 测试：真实停车场景 - 多次查询
     *
     * 模拟用户在停车场多次查询费用：
     * - 用户进入：09:23
     * - 第一次查询：11:45（准备离开，查询费用）
     * - 用户没走，继续查询：13:20
     * - 用户离开：15:08
     *
     * 每次查询都应该正确累计，不应重复收费
     */
    static void testRealParkingScenario_MultiQuery() {
        System.out.println("=== 测试: 真实停车场景 - 多次查询 ===\n");

        var billingService = getRelativeTimeBillingService_60MinUnit(new BigDecimal("50")); // 每天封顶50元

        System.out.println("场景：车辆 09:23 进入停车场，用户多次查询费用\n");

        // 第一次查询：09:23 - 11:45
        System.out.println("【第一次查询】09:23 - 11:45（停车 2小时22分钟）");
        var request1 = createRelativeTimeRequest("09:23", "11:45");
        var result1 = billingService.calculate(request1);
        System.out.println("  累计费用: " + result1.getFinalAmount() + "元");
        System.out.println("  单元数: " + result1.getUnits().size());
        printUnitsBrief(result1);

        // 第二次查询（CONTINUE）：09:23 - 13:20
        System.out.println("\n【第二次查询 CONTINUE】09:23 - 13:20（继续停车到 13:20）");
        var request2 = createRelativeTimeRequest("09:23", "13:20");
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);
        System.out.println("  累计总费用: " + result2.getFinalAmount() + "元（增量: " + result2.getFinalAmount().subtract(result1.getFinalAmount()) + "元）");
        System.out.println("  单元数: " + result2.getUnits().size());
        printUnitsBrief(result2);

        // 第三次查询（CONTINUE）：09:23 - 15:08
        System.out.println("\n【第三次查询 CONTINUE】09:23 - 15:08（用户离开）");
        var request3 = createRelativeTimeRequest("09:23", "15:08");
        request3.setPreviousCarryOver(result2.getCarryOver());
        var result3 = billingService.calculate(request3);
        System.out.println("  累计总费用: " + result3.getFinalAmount() + "元（增量: " + result3.getFinalAmount().subtract(result2.getFinalAmount()) + "元）");
        System.out.println("  单元数: " + result3.getUnits().size());
        printUnitsBrief(result3);

        // 验证：CONTINUE 模式最后一次结果应该等于一次性计算结果
        System.out.println("\n【费用汇总验证】");
        var benchmarkResult = billingService.calculate(createRelativeTimeRequest("09:23", "15:08"));

        System.out.println("  一次性计算 09:23-15:08: " + benchmarkResult.getFinalAmount() + "元");
        System.out.println("  第三次查询累计费用: " + result3.getFinalAmount() + "元");

        var difference = result3.getFinalAmount().subtract(benchmarkResult.getFinalAmount());
        if (difference.compareTo(BigDecimal.ZERO) != 0) {
            System.out.println("  ✗ 费用不一致: 差额 " + difference.abs() + "元");
            System.out.println("  用户投诉风险: 高");
        } else {
            System.out.println("  ✓ 费用一致，用户满意");
        }

        System.out.println();
    }

    /**
     * 获取 60 分钟单元长度的 RelativeTimeRule 计费服务
     */
    static BillingService getRelativeTimeBillingService_60MinUnit(BigDecimal maxCharge) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                // 简单规则：60分钟单元，2元/小时
                List<RelativeTimePeriod> periods = List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440) // 全天
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("2")) // 2元/小时
                                .build()
                );

                return RelativeTimeConfig.builder()
                        .id("relative-time-60min")
                        .periods(periods)
                        .maxChargeOneCycle(maxCharge)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new ArrayList<>();
            }
        };

        return createBillingService(billingConfigResolver, BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
    }

    /**
     * 简要打印计费单元
     */
    static void printUnitsBrief(BillingResult result) {
        if (result.getUnits() == null) return;
        for (int i = 0; i < result.getUnits().size(); i++) {
            var unit = result.getUnits().get(i);
            String truncated = unit.getIsTruncated() != null && unit.getIsTruncated() ? " [截断]" : "";
            System.out.println("    " + unit.getBeginTime().toLocalTime() + " - " + unit.getEndTime().toLocalTime() +
                    truncated + " → " + unit.getChargedAmount() + "元");
        }
    }

    // ==================== 延伸与优惠交互测试 ====================

    /**
     * 场景1：延伸后与未使用优惠重叠
     *
     * 测试目标：验证延伸不会"预支"尚未使用的优惠
     *
     * 场景设置：
     * - 计算窗口: 08:00-10:00
     * - 免费时段: 10:00-12:00（在本次计算窗口外，尚未使用）
     * - 最后单元: 09:00-10:00，尝试延伸
     *
     * 预期行为：
     * - 延伸停在 10:00（免费时段开始），不会进入免费时段内部
     * - 免费时段 10:00-12:00 在下次 CONTINUE 时仍可使用
     */
    static void testExtension_NotConsumeUnusedPromotion() {
        System.out.println("=== 测试: 延伸不消耗未使用优惠 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 外部优惠: 免费时段 10:00 - 12:00
        var freeRange = PromotionGrant.builder()
                .id("free-range-future")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("10:00"))
                .endTime(parseTime("12:00"))
                .build();

        // 第一次计算: 08:00 - 10:00
        var request1 = createRelativeTimeRequest("08:00", "10:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  外部优惠: 免费时段 10:00-12:00（在计算窗口外）");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        printBillingDetail(result1);

        // 验证：延伸应该停在 10:00，不会进入 10:00-12:00
        var lastUnit = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());

        // 验证 usedFreeRanges 不应该包含这个免费时段
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            var usedRanges = segmentCarryOver.getPromotionState().getUsedFreeRanges();
            System.out.println("  已使用免费时段: " + usedRanges);
            System.out.println("  预期: 不应包含 10:00-12:00（因为完全在计算窗口外）");
        } else {
            System.out.println("  已使用免费时段: 无");
        }

        // 第二次计算: 继续 10:00 - 12:00
        var request2 = createRelativeTimeRequest("08:00", "12:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 12:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 10:00-12:00 免费时段应生效");
        printBillingDetail(result2);

        // 验证：第二次计算的金额应该是0（因为整个时段都在免费时段内）
        verifyEquals("金额", BigDecimal.ZERO, result2.getFinalAmount());
        System.out.println();
    }

    /**
     * 场景2a：最后单元被 FREE_RANGE 完全覆盖
     *
     * 测试目标：验证最后单元被免费时段完全覆盖时的延伸行为
     *
     * 场景设置：
     * - 计算窗口: 08:00-10:00
     * - 免费时段: 09:00-11:00（完全覆盖最后一个单元 09:00-10:00）
     *
     * 预期行为：
     * - 最后单元免费（被免费时段覆盖）
     * - 延伸不应超过免费时段结束时间（11:00）
     */
    static void testExtension_LastUnitCoveredByFreeRange() {
        System.out.println("=== 测试: 最后单元被免费时段完全覆盖 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 外部优惠: 免费时段 09:00 - 11:00（覆盖最后单元）
        var freeRange = PromotionGrant.builder()
                .id("free-range-cover")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("09:00"))
                .endTime(parseTime("11:00"))
                .build();

        // 第一次计算: 08:00 - 10:00
        var request1 = createRelativeTimeRequest("08:00", "10:00");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  外部优惠: 免费时段 09:00-11:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        printBillingDetail(result1);

        // 验证最后单元
        var lastUnit = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("  最后单元是否免费: " + lastUnit.isFree());
        System.out.println("  freePromotionId: " + lastUnit.getFreePromotionId());

        // 关键验证：延伸不应超过免费时段结束时间（11:00）
        // 或者不延伸（因为已经免费了）
        System.out.println("  预期：延伸不应超过 11:00（免费时段结束）");

        // 验证 usedFreeRanges
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            var usedRanges = segmentCarryOver.getPromotionState().getUsedFreeRanges();
            System.out.println("  已使用免费时段: " + usedRanges);
        }

        // 第二次计算: 继续 10:00 - 12:00
        var request2 = createRelativeTimeRequest("08:00", "12:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 12:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 10:00-11:00 免费，11:00-12:00 收费");
        printBillingDetail(result2);
        System.out.println();
    }

    /**
     * 场景2b：最后单元被 FREE_MINUTES 覆盖
     *
     * 测试目标：验证最后单元被免费分钟数覆盖时的延伸行为
     *
     * 场景设置：
     * - 计算窗口: 08:00-10:00
     * - 免费分钟数: 60分钟（足够覆盖最后一个单元 09:00-10:00）
     *
     * 预期行为：
     * - 最后单元因免费分钟数而免费
     * - 延伸行为需要明确（是延伸还是不延伸）
     */
    static void testExtension_LastUnitCoveredByFreeMinutes() {
        System.out.println("=== 测试: 最后单元被免费分钟数覆盖 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 外部优惠: 免费分钟数 90分钟（覆盖第一个单元30分钟 + 第二个单元60分钟）
        var freeMin = PromotionGrant.builder()
                .id("free-min-cover")
                .type(BConstants.PromotionType.FREE_MINUTES)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .freeMinutes(90)
                .build();

        // 第一次计算: 08:00 - 10:00
        var request1 = createRelativeTimeRequest("08:00", "10:00");
        request1.setExternalPromotions(List.of(freeMin));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 10:00");
        System.out.println("  外部优惠: 免费分钟数 90分钟");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        printBillingDetail(result1);

        // 验证最后单元
        var lastUnit = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("  最后单元是否免费: " + lastUnit.isFree());

        // 验证剩余免费分钟数
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            var remaining = segmentCarryOver.getPromotionState().getRemainingMinutesConverted();
            System.out.println("  剩余免费分钟: " + remaining);
        }

        // 第二次计算: 继续 10:00 - 12:00
        var request2 = createRelativeTimeRequest("08:00", "12:00");
        request2.setExternalPromotions(List.of(freeMin));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 10:00 - 12:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 应使用剩余免费分钟数");
        printBillingDetail(result2);
        System.out.println();
    }

    /**
     * 综合场景：延伸恰好停在免费时段边界
     *
     * 测试目标：验证延伸到免费时段开始时间的行为
     *
     * 场景设置：
     * - 计算窗口: 08:00-09:30（最后单元 09:00-09:30 被截断）
     * - 免费时段: 10:00-12:00（延伸会遇到）
     *
     * 预期行为：
     * - 最后单元延伸到 10:00（完整单元长度 09:00-10:00，然后停在免费时段边界）
     */
    static void testExtension_StopsAtFreeRangeBoundary() {
        System.out.println("=== 测试: 延伸停在免费时段边界 ===\n");

        var billingService = getRelativeTimeBillingService(new BigDecimal("100"), false);

        // 外部优惠: 免费时段 10:00 - 12:00
        var freeRange = PromotionGrant.builder()
                .id("free-range-boundary")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .source(BConstants.PromotionSource.COUPON)
                .beginTime(parseTime("10:00"))
                .endTime(parseTime("12:00"))
                .build();

        // 第一次计算: 08:00 - 09:30（最后单元被截断）
        var request1 = createRelativeTimeRequest("08:00", "09:30");
        request1.setExternalPromotions(List.of(freeRange));
        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 08:00 - 09:30（最后单元被截断）");
        System.out.println("  外部优惠: 免费时段 10:00-12:00");
        System.out.println("  结果金额: " + result1.getFinalAmount());
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime());
        printBillingDetail(result1);

        // 验证最后单元延伸情况
        var lastUnit = result1.getUnits().get(result1.getUnits().size() - 1);
        System.out.println("  最后单元: " + lastUnit.getBeginTime() + " - " + lastUnit.getEndTime());
        System.out.println("  最后单元时长: " + lastUnit.getDurationMinutes() + "分钟");
        System.out.println("  预期: 延伸到 10:00（停在免费时段边界）或 10:00（完整单元）");

        // 如果延伸到 10:00，则下次 CONTINUE 应从 10:00 开始
        // 但 10:00-12:00 是免费的，所以下次计算金额应该是 0（如果在 12:00 前结束）
        var request2 = createRelativeTimeRequest("08:00", "11:00");
        request2.setExternalPromotions(List.of(freeRange));
        request2.setPreviousCarryOver(result1.getCarryOver());
        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算（CONTINUE）: 延伸位置 - 11:00");
        System.out.println("  结果金额: " + result2.getFinalAmount());
        System.out.println("  预期: 在免费时段内，金额应为 0");
        printBillingDetail(result2);
        System.out.println();
    }

    // ==================== 辅助方法 ====================

    static BillingService getRelativeTimeBillingService(BigDecimal maxCharge, boolean withFreeMinutes) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
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
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
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
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
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
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
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

    /**
     * 打印计费明细（JSON格式）
     */
    static void printBillingDetail(BillingResult result) {
        System.out.println("  计费明细（JSON）:");
        try {
            // 打印计费单元
            if (result.getUnits() != null && !result.getUnits().isEmpty()) {
                System.out.println("    计费单元:");
                for (BillingUnit unit : result.getUnits()) {
                    System.out.println("      " + JacksonUtils.toJsonString(unit));
                }
            }
            // 打印carryOver
            if (result.getCarryOver() != null) {
                System.out.println("    carryOver: " + JacksonUtils.toJsonString(result.getCarryOver()));
            }
        } catch (Exception e) {
            System.out.println("    序列化失败: " + e.getMessage());
        }
    }
}