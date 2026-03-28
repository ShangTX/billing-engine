package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.compositetime.*;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 综合功能测试类 - 增强版
 * <p>
 * 设计原则：
 * 1. 时间使用非整点（分钟数带个位），贴近现实场景
 * 2. 重点测试 CONTINUOUS 模式
 * 3. 重点测试有优惠、多优惠组合场景
 * 4. 打印详细，不看代码即可验证
 */
public class ComprehensiveBillingTest {

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    static final DateTimeFormatter FULL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              综合功能测试 - 增强版 (CONTINUOUS模式)            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        // ==================== DayNightRule 测试 (5个) ====================
        printSection("DayNightRule 测试");
        test01_DayNight_IrregularTime_Basic();
        test02_DayNight_FreeRange_CrossDayNight();
        test03_DayNight_CapTrigger_Continue();
        test04_DayNight_CrossCycle_MultiplePromotions();
        test05_DayNight_CapWithFreeMinutes();

        // ==================== RelativeTimeRule 测试 (5个) ====================
        printSection("RelativeTimeRule 测试");
        test06_RelativeTime_IrregularTime_Basic();
        test07_RelativeTime_CycleCap_Trigger();
        test08_RelativeTime_FreeRange_Split();
        test09_RelativeTime_FreeMinutes_Deduct();
        test10_RelativeTime_CrossCycle_MultiplePromotions();

        // ==================== CompositeTimeRule 测试 (3个) ====================
        printSection("CompositeTimeRule 测试");
        test11_Composite_NaturalPeriod_Change();
        test12_Composite_FreeRange_CoverPriceChange();
        test13_Composite_Cap_CrossCycle();

        // ==================== 长期计费简化计算测试 (3个) ====================
        printSection("长期计费简化计算测试");
        test14_Simplified_Basic();
        test15_Simplified_Partial();
        test16_Simplified_ContinueMode();

        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                      所有测试执行完毕                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");
    }

    // ==================== DayNightRule 测试 ====================

    /**
     * 测试01: DayNightRule - 不规则时间日夜混合（无优惠）
     *
     * 场景：08:13-16:47，跨日夜边界
     * 配置：白天 08:00-20:00 @ 2元/时，夜间 @ 1元/时，单元60分钟，封顶30元/天
     */
    static void test01_DayNight_IrregularTime_Basic() {
        printTestHeader("DayNightRule - 不规则时间日夜混合",
                "计费时间 08:13-16:47，跨日夜边界，无优惠");

        // 输入参数
        LocalDateTime beginTime = time(3, 10, 8, 13);
        LocalDateTime endTime = time(3, 10, 16, 47);

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 08:13 - 16:47 (8小时34分钟)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  日夜配置: 白天 08:00-20:00 @ 2元/时, 夜间 20:00-08:00 @ 1元/时");
        System.out.println("  单元长度: 60分钟, 日封顶: 30元/天");
        System.out.println("  优惠配置: 无");

        System.out.println("\n【预期结果】");
        System.out.println("  从08:13开始，每单元60分钟：");
        System.out.println("  #1  08:13-09:13: 白天 60分钟 = 2元");
        System.out.println("  #2  09:13-10:13: 白天 60分钟 = 2元");
        System.out.println("  #3  10:13-11:13: 白天 60分钟 = 2元");
        System.out.println("  #4  11:13-12:13: 白天 60分钟 = 2元");
        System.out.println("  #5  12:13-13:13: 白天 60分钟 = 2元");
        System.out.println("  #6  13:13-14:13: 白天 60分钟 = 2元");
        System.out.println("  #7  14:13-15:13: 白天 60分钟 = 2元");
        System.out.println("  #8  15:13-16:13: 白天 60分钟 = 2元");
        System.out.println("  #9  16:13-16:47: 白天 34分钟(截断) = 2元");
        System.out.println("  预期金额: 9单元 × 2元 = 18元");

        BillingService service = createDayNightService(new BigDecimal("30"));
        BillingRequest request = createRequest(beginTime, endTime, new ArrayList<>());
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printVerification("金额18元", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试02: DayNightRule - 免费时段覆盖日夜边界
     *
     * 场景：免费时段 07:30-09:30，计费 06:23-11:17
     */
    static void test02_DayNight_FreeRange_CrossDayNight() {
        printTestHeader("DayNightRule - 免费时段覆盖日夜边界",
                "免费时段 07:30-09:30，计费 06:23-11:17");

        LocalDateTime beginTime = time(3, 10, 6, 23);
        LocalDateTime endTime = time(3, 10, 11, 17);

        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-range-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time(3, 10, 7, 30))
                        .endTime(time(3, 10, 9, 30))
                        .build()
        );

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 06:23 - 11:17 (4小时54分钟)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  日夜配置: 白天 08:00-20:00 @ 2元/时, 夜间 @ 1元/时");
        System.out.println("  优惠配置: 免费时段 07:30-09:30 (覆盖日夜边界 08:00)");

        System.out.println("\n【预期结果】");
        System.out.println("  CONTINUOUS模式按免费时段切分:");
        System.out.println("  片段1: 06:23-07:30 夜间 = 1小时7分钟 ≈ 1元");
        System.out.println("  片段2: 07:30-09:30 免费 = 0元");
        System.out.println("  片段3: 09:30-11:17 白天 = 1小时47分钟 ≈ 4元");
        System.out.println("  预期金额: 约 5 元");

        BillingService service = createDayNightService(new BigDecimal("50"));
        BillingRequest request = createRequest(beginTime, endTime, promos);
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printPromotionUsages(result);
        printVerification("金额约5元", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试03: DayNightRule - 日封顶触发 + 继续计算
     */
    static void test03_DayNight_CapTrigger_Continue() {
        printTestHeader("DayNightRule - 日封顶触发 + 继续计算",
                "第一次 08:23-14:37 触发封顶，第二次继续到 18:53");

        BillingService service = createDayNightService(new BigDecimal("8")); // 低封顶

        System.out.println("【输入参数】");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  日夜配置: 白天 08:00-20:00 @ 2元/时, 夜间 @ 1元/时");
        System.out.println("  单元长度: 60分钟, 日封顶: 8元/天");
        System.out.println("  优惠配置: 无");

        // 第一次计算
        LocalDateTime beginTime1 = time(3, 10, 8, 23);
        LocalDateTime endTime1 = time(3, 10, 14, 37);

        System.out.println("\n【第一次计算】");
        System.out.println("  计费时间: 03-10 08:23 - 14:37 (6小时14分钟)");
        System.out.println("\n【预期结果】");
        System.out.println("  6小时多按7个单元计算，7×2=14元，但封顶8元");
        System.out.println("  预期金额: 8元 (触发封顶)");

        BillingRequest request1 = createRequest(beginTime1, endTime1, new ArrayList<>());
        BillingResult result1 = service.calculate(request1);

        printResult(result1);
        printUnits(result1);
        System.out.println("  封顶触发: " + (result1.getFinalAmount().compareTo(new BigDecimal("8")) == 0));

        // 第二次计算（CONTINUE）
        LocalDateTime endTime2 = time(3, 10, 18, 53);

        System.out.println("\n【第二次计算 (CONTINUE)】");
        System.out.println("  计费时间: 14:37 - 18:53 (4小时16分钟)");
        System.out.println("  上次结束: " + result1.getCalculationEndTime().format(TIME_FORMAT));
        System.out.println("\n【预期结果】");
        System.out.println("  因本日已封顶，后续时间免费");
        System.out.println("  预期金额: 0元");

        BillingRequest request2 = createRequest(beginTime1, endTime2, new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());
        BillingResult result2 = service.calculate(request2);

        printResult(result2);
        printUnits(result2);

        BigDecimal total = result1.getFinalAmount().add(result2.getFinalAmount());
        System.out.println("\n  两次累计金额: " + total + "元");
        printVerification("第二次0元，累计8元", total);
        printTestEnd();
    }

    /**
     * 测试04: DayNightRule - 跨周期 + 多种优惠
     */
    static void test04_DayNight_CrossCycle_MultiplePromotions() {
        printTestHeader("DayNightRule - 跨周期 + 多种优惠",
                "跨天计费 + 免费时段 + 免费分钟数");

        BillingService service = createDayNightServiceWithFreeMinutes(new BigDecimal("20"), true);

        LocalDateTime beginTime = time(3, 10, 10, 23);
        LocalDateTime endTime = time(3, 11, 14, 37);

        List<PromotionGrant> promos = List.of(
                // 免费时段 20:00-22:00
                PromotionGrant.builder()
                        .id("free-range-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time(3, 10, 20, 0))
                        .endTime(time(3, 10, 22, 0))
                        .build(),
                // 免费分钟数 90分钟
                PromotionGrant.builder()
                        .id("free-min-1")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(2)
                        .freeMinutes(90)
                        .build()
        );

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 10:23 - 03-11 14:37 (跨天)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  日夜配置: 白天 08:00-20:00 @ 2元/时, 夜间 @ 1元/时");
        System.out.println("  日封顶: 20元/天");
        System.out.println("  优惠配置:");
        System.out.println("    - 免费时段: 20:00-22:00");
        System.out.println("    - 免费分钟数: 90分钟");
        System.out.println("    - 规则级别免费分钟数: 30分钟");

        System.out.println("\n【预期结果】");
        System.out.println("  第一天 10:23-20:00 白天 + 20:00-22:00 免费 + 22:00-次日08:00 夜间");
        System.out.println("  第二天 08:00-14:37 白天");
        System.out.println("  免费分钟数按时间顺序抵扣");

        BillingRequest request = createRequest(beginTime, endTime, promos);
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printPromotionUsages(result);
        printTestEnd();
    }

    /**
     * 测试05: DayNightRule - 封顶触发时剩余免费分钟
     */
    static void test05_DayNight_CapWithFreeMinutes() {
        printTestHeader("DayNightRule - 封顶 + 免费分钟结转",
                "封顶触发后剩余免费分钟数应正确结转");

        BillingService service = createDayNightService(new BigDecimal("5")); // 极低封顶

        // 免费分钟数 120分钟
        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-min-120")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .freeMinutes(120)
                        .build()
        );

        System.out.println("【输入参数】");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  日夜配置: 白天 08:00-20:00 @ 2元/时");
        System.out.println("  日封顶: 5元/天 (极低，快速触发)");
        System.out.println("  优惠配置: 免费分钟数 120分钟");

        // 第一次计算
        LocalDateTime beginTime = time(3, 10, 8, 17);
        LocalDateTime endTime1 = time(3, 10, 12, 43);

        System.out.println("\n【第一次计算】");
        System.out.println("  计费时间: 03-10 08:17 - 12:43 (4小时26分钟)");
        System.out.println("\n【预期结果】");
        System.out.println("  先使用免费分钟数，再用封顶");
        System.out.println("  120分钟免费可抵扣2个单元，剩余时段触发封顶5元");

        BillingRequest request1 = createRequest(beginTime, endTime1, promos);
        BillingResult result1 = service.calculate(request1);

        printResult(result1);
        printUnits(result1);
        printPromotionUsages(result1);

        // 检查剩余免费分钟数
        var segmentCarryOver = result1.getCarryOver().getSegments().values().iterator().next();
        if (segmentCarryOver.getPromotionState() != null) {
            System.out.println("  剩余免费分钟: " + segmentCarryOver.getPromotionState().getRemainingMinutes());
        }

        // 第二次计算（CONTINUE）
        LocalDateTime endTime2 = time(3, 11, 10, 23);

        System.out.println("\n【第二次计算 (CONTINUE)】");
        System.out.println("  计费时间: 继续到 03-11 10:23 (跨周期)");
        System.out.println("\n【预期结果】");
        System.out.println("  新周期重新累计，剩余免费分钟可继续使用");

        BillingRequest request2 = createRequest(beginTime, endTime2, promos);
        request2.setPreviousCarryOver(result1.getCarryOver());
        BillingResult result2 = service.calculate(request2);

        printResult(result2);
        printUnits(result2);

        printTestEnd();
    }

    // ==================== RelativeTimeRule 测试 ====================

    /**
     * 测试06: RelativeTimeRule - 不规则时间基础计费
     */
    static void test06_RelativeTime_IrregularTime_Basic() {
        printTestHeader("RelativeTimeRule - 不规则时间基础计费",
                "计费时间 08:23-14:57，多时段配置，无优惠");

        LocalDateTime beginTime = time(3, 10, 8, 23);
        LocalDateTime endTime = time(3, 10, 14, 57);

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 08:23 - 14:57 (6小时34分钟)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  时段配置:");
        System.out.println("    - 0-2小时: 单元30分钟, 1元/单元");
        System.out.println("    - 2-24小时: 单元60分钟, 2元/单元");
        System.out.println("  周期封顶: 50元/24小时");
        System.out.println("  优惠配置: 无");

        System.out.println("\n【预期结果】");
        System.out.println("  08:23-08:53: 30分钟单元 = 1元");
        System.out.println("  08:53-09:23: 30分钟单元 = 1元 (前2小时共4个30分单元=4元)");
        System.out.println("  09:23-10:23: 60分钟单元 = 2元");
        System.out.println("  ... 继续计算");
        System.out.println("  预期金额: 需计算");

        BillingService service = createRelativeTimeService(new BigDecimal("50"));
        BillingRequest request = createRequest(beginTime, endTime, new ArrayList<>());
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printTestEnd();
    }

    /**
     * 测试07: RelativeTimeRule - 周期封顶触发
     */
    static void test07_RelativeTime_CycleCap_Trigger() {
        printTestHeader("RelativeTimeRule - 周期封顶触发",
                "计费时间 07:43-16:29，触发封顶");

        BillingService service = createRelativeTimeService(new BigDecimal("10")); // 低封顶

        LocalDateTime beginTime = time(3, 10, 7, 43);
        LocalDateTime endTime = time(3, 10, 16, 29);

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 07:43 - 16:29 (8小时46分钟)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  时段配置: 0-2小时@1元/30分, 2小时后@2元/60分");
        System.out.println("  周期封顶: 10元/24小时");
        System.out.println("  优惠配置: 无");

        System.out.println("\n【预期结果】");
        System.out.println("  计算超过10元后触发封顶");
        System.out.println("  预期金额: 10元 (封顶)");

        BillingRequest request = createRequest(beginTime, endTime, new ArrayList<>());
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printVerification("封顶金额10元", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试08: RelativeTimeRule - 免费时段切分时间轴
     */
    static void test08_RelativeTime_FreeRange_Split() {
        printTestHeader("RelativeTimeRule - 免费时段切分时间轴",
                "免费时段 09:13-11:47，计费 07:37-14:23");

        LocalDateTime beginTime = time(3, 10, 7, 37);
        LocalDateTime endTime = time(3, 10, 14, 23);

        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-range-split")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time(3, 10, 9, 13))
                        .endTime(time(3, 10, 11, 47))
                        .build()
        );

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 07:37 - 14:23 (6小时46分钟)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  时段配置: 0-2小时@1元/30分, 2小时后@2元/60分");
        System.out.println("  优惠配置: 免费时段 09:13-11:47 (2小时34分钟)");

        System.out.println("\n【预期结果】");
        System.out.println("  CONTINUOUS模式按免费时段切分:");
        System.out.println("  片段1: 07:37-09:13 = 1小时36分钟 计费");
        System.out.println("  片段2: 09:13-11:47 = 2小时34分钟 免费");
        System.out.println("  片段3: 11:47-14:23 = 2小时36分钟 计费");
        System.out.println("  注意: 每个片段从起点重新计时");

        BillingService service = createRelativeTimeService(new BigDecimal("50"));
        BillingRequest request = createRequest(beginTime, endTime, promos);
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printPromotionUsages(result);
        printTestEnd();
    }

    /**
     * 测试09: RelativeTimeRule - 免费分钟数抵扣
     */
    static void test09_RelativeTime_FreeMinutes_Deduct() {
        printTestHeader("RelativeTimeRule - 免费分钟数抵扣",
                "免费分钟数120分钟，按时间顺序抵扣");

        LocalDateTime beginTime = time(3, 10, 7, 17);
        LocalDateTime endTime = time(3, 10, 15, 43);

        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-min-120")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .freeMinutes(120)
                        .build()
        );

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 07:17 - 15:43 (8小时26分钟)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  时段配置: 0-2小时@1元/30分, 2小时后@2元/60分");
        System.out.println("  优惠配置: 免费分钟数 120分钟");

        System.out.println("\n【预期结果】");
        System.out.println("  免费分钟数按时间顺序抵扣，从计费起点开始");
        System.out.println("  07:17-09:17 共120分钟被免费分钟数抵扣 = 0元");

        BillingService service = createRelativeTimeService(new BigDecimal("50"));
        BillingRequest request = createRequest(beginTime, endTime, promos);
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printPromotionUsages(result);
        printTestEnd();
    }

    /**
     * 测试10: RelativeTimeRule - 跨周期 + 多种优惠组合
     */
    static void test10_RelativeTime_CrossCycle_MultiplePromotions() {
        printTestHeader("RelativeTimeRule - 跨周期 + 多种优惠组合",
                "跨天计费 + 免费时段 + 免费分钟数");

        BillingService service = createRelativeTimeServiceWithFreeMinutes(new BigDecimal("30"), true);

        LocalDateTime beginTime = time(3, 10, 10, 37);
        LocalDateTime endTime = time(3, 11, 14, 53);

        List<PromotionGrant> promos = List.of(
                // 免费时段
                PromotionGrant.builder()
                        .id("free-range-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time(3, 10, 15, 0))
                        .endTime(time(3, 10, 17, 0))
                        .build(),
                // 免费分钟数
                PromotionGrant.builder()
                        .id("free-min-1")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(2)
                        .freeMinutes(60)
                        .build()
        );

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 10:37 - 03-11 14:53 (跨天)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  周期封顶: 30元/24小时");
        System.out.println("  优惠配置:");
        System.out.println("    - 免费时段: 15:00-17:00");
        System.out.println("    - 免费分钟数: 60分钟");
        System.out.println("    - 规则级别免费分钟数: 30分钟");

        System.out.println("\n【预期结果】");
        System.out.println("  第一周期: 10:37起算，含免费时段15:00-17:00");
        System.out.println("  第二周期: 次日10:37起，重新累计封顶");
        System.out.println("  免费分钟数在周期间正确结转");

        BillingRequest request = createRequest(beginTime, endTime, promos);
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printPromotionUsages(result);
        printTestEnd();
    }

    // ==================== CompositeTimeRule 测试 ====================

    /**
     * 测试11: CompositeTimeRule - 自然时段价格变化
     */
    static void test11_Composite_NaturalPeriod_Change() {
        printTestHeader("CompositeTimeRule - 自然时段价格变化",
                "计费时间跨不同价格的自然时段");

        LocalDateTime beginTime = time(3, 10, 6, 17);
        LocalDateTime endTime = time(3, 10, 14, 43);

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 06:17 - 14:43 (8小时26分钟)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  自然时段配置:");
        System.out.println("    - 00:00-08:00: 1元/小时 (夜间价)");
        System.out.println("    - 08:00-20:00: 3元/小时 (白天价)");
        System.out.println("    - 20:00-24:00: 1元/小时 (夜间价)");
        System.out.println("  周期封顶: 50元/24小时");
        System.out.println("  优惠配置: 无");

        System.out.println("\n【预期结果】");
        System.out.println("  06:17-08:00: 夜间价 1小时43分钟");
        System.out.println("  08:00-14:43: 白天价 6小时43分钟");
        System.out.println("  预期金额: 需计算");

        BillingService service = createCompositeTimeService(new BigDecimal("50"));
        BillingRequest request = createRequest(beginTime, endTime, new ArrayList<>());
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printTestEnd();
    }

    /**
     * 测试12: CompositeTimeRule - 免费时段覆盖不同价格时段
     */
    static void test12_Composite_FreeRange_CoverPriceChange() {
        printTestHeader("CompositeTimeRule - 免费时段覆盖价格变化",
                "免费时段跨日夜价格边界");

        LocalDateTime beginTime = time(3, 10, 7, 23);
        LocalDateTime endTime = time(3, 10, 13, 47);

        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-range-cross")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time(3, 10, 7, 30))
                        .endTime(time(3, 10, 9, 30))
                        .build()
        );

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-10 07:23 - 13:47 (6小时24分钟)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  自然时段: 00-08点@1元, 08-20点@3元");
        System.out.println("  优惠配置: 免费时段 07:30-09:30 (跨08:00价格边界)");

        System.out.println("\n【预期结果】");
        System.out.println("  CONTINUOUS模式按免费时段切分:");
        System.out.println("  片段1: 07:23-07:30 夜间价 计费");
        System.out.println("  片段2: 07:30-09:30 免费 (含07:30-08:00夜间 + 08:00-09:30白天)");
        System.out.println("  片段3: 09:30-13:47 白天价 计费");

        BillingService service = createCompositeTimeService(new BigDecimal("50"));
        BillingRequest request = createRequest(beginTime, endTime, promos);
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);
        printPromotionUsages(result);
        printTestEnd();
    }

    /**
     * 测试13: CompositeTimeRule - 封顶 + 跨周期
     */
    static void test13_Composite_Cap_CrossCycle() {
        printTestHeader("CompositeTimeRule - 封顶 + 跨周期",
                "不规则时间触发封顶后跨周期");

        BillingService service = createCompositeTimeService(new BigDecimal("15")); // 低封顶

        // 第一次计算
        LocalDateTime beginTime = time(3, 10, 9, 13);
        LocalDateTime endTime1 = time(3, 10, 16, 27);

        System.out.println("【输入参数】");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  自然时段: 00-08点@1元, 08-20点@3元");
        System.out.println("  周期封顶: 15元/24小时");

        System.out.println("\n【第一次计算】");
        System.out.println("  计费时间: 03-10 09:13 - 16:27 (7小时14分钟)");
        System.out.println("\n【预期结果】");
        System.out.println("  白天价3元/时，约7小时 = 21元，封顶15元");

        BillingRequest request1 = createRequest(beginTime, endTime1, new ArrayList<>());
        BillingResult result1 = service.calculate(request1);

        printResult(result1);
        printUnits(result1);
        System.out.println("  封顶触发: " + (result1.getFinalAmount().compareTo(new BigDecimal("15")) == 0));

        // 第二次计算（CONTINUE）
        LocalDateTime endTime2 = time(3, 11, 11, 53);

        System.out.println("\n【第二次计算 (CONTINUE)】");
        System.out.println("  计费时间: 继续到 03-11 11:53 (跨周期)");
        System.out.println("\n【预期结果】");
        System.out.println("  新周期重新累计，从次日09:13开始");

        BillingRequest request2 = createRequest(beginTime, endTime2, new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());
        BillingResult result2 = service.calculate(request2);

        printResult(result2);
        printUnits(result2);

        BigDecimal total = result1.getFinalAmount().add(result2.getFinalAmount());
        System.out.println("\n  两次累计金额: " + total + "元");
        printTestEnd();
    }

    // ==================== 长期计费简化计算测试 ====================

    /**
     * 测试14: 长期计费简化 - 基本简化
     */
    static void test14_Simplified_Basic() {
        printTestHeader("长期计费简化 - 基本简化",
                "15天无优惠，应生成简化单元");

        BillingService service = createDayNightServiceWithSimplified(7); // 阈值7天

        LocalDateTime beginTime = time(3, 1, 8, 17);
        LocalDateTime endTime = time(3, 16, 8, 17); // 15天

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-01 08:17 - 03-16 08:17 (15天)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  日夜配置: 白天@2元/时, 夜间@1元/时, 封顶10元/天");
        System.out.println("  简化阈值: 7天 (连续无优惠超过7天启用简化)");
        System.out.println("  优惠配置: 无");

        System.out.println("\n【预期结果】");
        System.out.println("  15天 > 阈值7天，应生成简化单元");
        System.out.println("  预期金额: 15天 × 10元/天 = 150元");
        System.out.println("  预期单元数: 1个简化单元（或少量单元）");

        BillingRequest request = createRequest(beginTime, endTime, new ArrayList<>());
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);

        // 检查简化单元
        boolean hasSimplified = result.getUnits().stream().anyMatch(ComprehensiveBillingTest::isSimplifiedUnit);
        System.out.println("  包含简化单元: " + hasSimplified);
        if (hasSimplified) {
            System.out.println("  [PASS] 生成了简化单元");
        }

        printVerification("金额150元", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试15: 长期计费简化 - 部分简化
     */
    static void test15_Simplified_Partial() {
        printTestHeader("长期计费简化 - 部分简化",
                "20天，中间第10天有优惠时段");

        BillingService service = createDayNightServiceWithSimplified(7);

        LocalDateTime beginTime = time(3, 1, 8, 23);
        LocalDateTime endTime = time(3, 21, 8, 23); // 20天

        // 第10天有优惠
        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-range-day10")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time(3, 11, 10, 0))
                        .endTime(time(3, 11, 12, 0))
                        .build()
        );

        System.out.println("【输入参数】");
        System.out.println("  计费时间: 03-01 08:23 - 03-21 08:23 (20天)");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  日封顶: 10元/天");
        System.out.println("  简化阈值: 7天");
        System.out.println("  优惠配置: 第11天 (3月11日) 10:00-12:00 免费");

        System.out.println("\n【预期结果】");
        System.out.println("  第1-10天: 无优惠，可简化 (10天 > 阈值7)");
        System.out.println("  第11天: 有优惠，详细计算");
        System.out.println("  第12-20天: 无优惠，可简化 (9天 > 阈值7)");
        System.out.println("  预期: 生成2个简化单元 + 第11天详细单元");

        BillingRequest request = createRequest(beginTime, endTime, promos);
        BillingResult result = service.calculate(request);

        printResult(result);
        printUnits(result);

        long simplifiedCount = result.getUnits().stream().filter(ComprehensiveBillingTest::isSimplifiedUnit).count();
        long detailedCount = result.getUnits().size() - simplifiedCount;
        System.out.println("  简化单元数: " + simplifiedCount);
        System.out.println("  详细单元数: " + detailedCount);

        if (simplifiedCount >= 2) {
            System.out.println("  [PASS] 生成了至少2个简化单元（优惠前后）");
        }

        printTestEnd();
    }

    /**
     * 测试16: 长期计费简化 - CONTINUE模式
     */
    static void test16_Simplified_ContinueMode() {
        printTestHeader("长期计费简化 - CONTINUE模式",
                "简化后继续计算，验证状态恢复");

        BillingService service = createDayNightServiceWithSimplified(7);

        // 第一次计算：15天，生成简化单元
        LocalDateTime beginTime = time(3, 1, 8, 13);
        LocalDateTime endTime1 = time(3, 16, 8, 13);

        System.out.println("【输入参数】");
        System.out.println("  计费模式: CONTINUOUS");
        System.out.println("  日封顶: 10元/天");
        System.out.println("  简化阈值: 7天");

        System.out.println("\n【第一次计算】");
        System.out.println("  计费时间: 03-01 08:13 - 03-16 08:13 (15天)");
        System.out.println("\n【预期结果】");
        System.out.println("  生成简化单元，金额 150元");

        BillingRequest request1 = createRequest(beginTime, endTime1, new ArrayList<>());
        BillingResult result1 = service.calculate(request1);

        printResult(result1);

        boolean hasSimplified = result1.getUnits().stream().anyMatch(ComprehensiveBillingTest::isSimplifiedUnit);
        System.out.println("  包含简化单元: " + hasSimplified);
        System.out.println("  calculationEndTime: " + result1.getCalculationEndTime().format(TIME_FORMAT));

        // 第二次计算（CONTINUE）：继续5天
        LocalDateTime endTime2 = time(3, 21, 8, 13);

        System.out.println("\n【第二次计算 (CONTINUE)】");
        System.out.println("  计费时间: 继续到 03-21 08:13 (再加5天)");
        System.out.println("\n【预期结果】");
        System.out.println("  状态正确恢复，继续计算5天");
        System.out.println("  增量金额: 5天 × 10元 = 50元");
        System.out.println("  累计金额: 150 + 50 = 200元");

        BillingRequest request2 = createRequest(beginTime, endTime2, new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());
        BillingResult result2 = service.calculate(request2);

        printResult(result2);
        printUnits(result2);

        BigDecimal total = result1.getFinalAmount().add(result2.getFinalAmount());
        System.out.println("\n  第一次金额: " + result1.getFinalAmount() + "元");
        System.out.println("  第二次金额: " + result2.getFinalAmount() + "元");
        System.out.println("  累计金额: " + total + "元");

        printVerification("累计200元", total);
        printTestEnd();
    }

    // ==================== 辅助方法 ====================

    static void printSection(String title) {
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("║ %-60s║%n", title);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    static void printTestHeader(String title, String desc) {
        System.out.println("\n┌─────────────────────────────────────────────────────────────┐");
        System.out.printf("│ 测试: %-52s│%n", title);
        System.out.printf("│ 场景: %-52s│%n", desc);
        System.out.println("└─────────────────────────────────────────────────────────────┘");
    }

    static void printResult(BillingResult result) {
        System.out.println("\n【实际结果】");
        System.out.println("  最终金额: " + result.getFinalAmount() + " 元");
        System.out.println("  计费单元: " + (result.getUnits() != null ? result.getUnits().size() : 0) + " 个");
        System.out.println("  计算结束时间: " + result.getCalculationEndTime().format(TIME_FORMAT));
    }

    static void printUnits(BillingResult result) {
        if (result.getUnits() == null || result.getUnits().isEmpty()) {
            System.out.println("\n【计费单元明细】无");
            return;
        }

        System.out.println("\n【计费单元明细】共 " + result.getUnits().size() + " 个单元");

        for (int i = 0; i < result.getUnits().size(); i++) {
            BillingUnit unit = result.getUnits().get(i);
            String freeMark = "";
            if (unit.isFree()) {
                freeMark = " [免费:" + unit.getFreePromotionId() + "]";
            }
            String simplifiedMark = "";
            if (isSimplifiedUnit(unit)) {
                Map<String, Object> data = (Map<String, Object>) unit.getRuleData();
                simplifiedMark = " [简化:" + data.get("simplifiedCycleCount") + "周期]";
            }

            System.out.printf("  #%d %s - %s (%dm) 单价:%s元 金额:%s元%s%s%n",
                    i + 1,
                    unit.getBeginTime().format(TIME_FORMAT),
                    unit.getEndTime().format(TIME_FORMAT),
                    unit.getDurationMinutes(),
                    unit.getUnitPrice(),
                    unit.getChargedAmount(),
                    freeMark,
                    simplifiedMark);
        }
    }

    static void printPromotionUsages(BillingResult result) {
        if (result.getPromotionUsages() == null || result.getPromotionUsages().isEmpty()) {
            System.out.println("\n【优惠使用】无");
            return;
        }

        System.out.println("\n【优惠使用】");
        for (var usage : result.getPromotionUsages()) {
            System.out.println("  - " + usage.getPromotionId() + ": 使用" + usage.getUsedMinutes() + "分钟");
        }
    }

    static void printVerification(String expected, BigDecimal actual) {
        System.out.println("\n【验证结果】");
        System.out.println("  预期: " + expected);
        System.out.println("  实际: " + actual + "元");
    }

    static void printTestEnd() {
        System.out.println();
    }

    static LocalDateTime time(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute);
    }

    static BillingRequest createRequest(LocalDateTime begin, LocalDateTime end, List<PromotionGrant> promos) {
        BillingRequest request = new BillingRequest();
        request.setId("test-" + System.currentTimeMillis());
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-default");
        request.setExternalPromotions(promos);
        return request;
    }

    @SuppressWarnings("unchecked")
    static boolean isSimplifiedUnit(BillingUnit unit) {
        if (unit.getRuleData() instanceof Map) {
            Map<String, Object> data = (Map<String, Object>) unit.getRuleData();
            return Boolean.TRUE.equals(data.get("isSimplified"));
        }
        return false;
    }

    // ==================== Service 创建方法 ====================

    static BillingService createRelativeTimeService(BigDecimal maxCharge) {
        return createRelativeTimeServiceWithFreeMinutes(maxCharge, false);
    }

    static BillingService createRelativeTimeServiceWithFreeMinutes(BigDecimal maxCharge, boolean withFreeMinutes) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
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

        return createBillingService(resolver, BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
    }

    static BillingService createDayNightService(BigDecimal maxCharge) {
        return createDayNightServiceWithFreeMinutes(maxCharge, false);
    }

    static BillingService createDayNightServiceWithFreeMinutes(BigDecimal maxCharge, boolean withFreeMinutes) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return DayNightConfig.builder()
                        .id("day-night-1")
                        .dayBeginMinute(8 * 60)
                        .dayEndMinute(20 * 60)
                        .unitMinutes(60)
                        .blockWeight(new BigDecimal("0.5"))
                        .dayUnitPrice(new BigDecimal("2"))
                        .nightUnitPrice(new BigDecimal("1"))
                        .maxChargeOneDay(maxCharge)
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

        return createBillingService(resolver, BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
    }

    static BillingService createDayNightServiceWithSimplified(int threshold) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public int getSimplifiedCycleThreshold() {
                return threshold;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return DayNightConfig.builder()
                        .id("day-night-1")
                        .dayBeginMinute(8 * 60)
                        .dayEndMinute(20 * 60)
                        .unitMinutes(60)
                        .blockWeight(new BigDecimal("0.5"))
                        .dayUnitPrice(new BigDecimal("2"))
                        .nightUnitPrice(new BigDecimal("1"))
                        .maxChargeOneDay(new BigDecimal("10"))
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new ArrayList<>();
            }
        };

        return createBillingService(resolver, BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
    }

    static BillingService createCompositeTimeService(BigDecimal maxCharge) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return CompositeTimeConfig.builder()
                        .id("composite-1")
                        .maxChargeOneCycle(maxCharge)
                        .periods(List.of(
                                CompositePeriod.builder()
                                        .beginMinute(0)
                                        .endMinute(1440)
                                        .unitMinutes(60)
                                        .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                        .naturalPeriods(List.of(
                                                NaturalPeriod.builder()
                                                        .beginMinute(0)
                                                        .endMinute(8 * 60)
                                                        .unitPrice(new BigDecimal("1"))
                                                        .build(),
                                                NaturalPeriod.builder()
                                                        .beginMinute(8 * 60)
                                                        .endMinute(20 * 60)
                                                        .unitPrice(new BigDecimal("3"))
                                                        .build(),
                                                NaturalPeriod.builder()
                                                        .beginMinute(20 * 60)
                                                        .endMinute(1440)
                                                        .unitPrice(new BigDecimal("1"))
                                                        .build()
                                        ))
                                        .build()
                        ))
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new ArrayList<>();
            }
        };

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.COMPOSITE_TIME, new CompositeTimeRule());

        return createBillingService(resolver, ruleRegistry);
    }

    static BillingService createBillingService(BillingConfigResolver resolver, String ruleType, BillingRule rule) {
        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(ruleType, rule);
        return createBillingService(resolver, ruleRegistry);
    }

    static BillingService createBillingService(BillingConfigResolver resolver, BillingRuleRegistry ruleRegistry) {
        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}