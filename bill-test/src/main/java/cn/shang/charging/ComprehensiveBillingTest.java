package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
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

/**
 * 综合功能测试类
 * <p>
 * 覆盖场景：
 * 1. 基础计费 - FROM_SCRATCH 模式
 * 2. CONTINUE 模式 - 继续计算
 * 3. UNIT_BASED vs CONTINUOUS 模式对比
 * 4. 免费时段优惠 (FREE_RANGE)
 * 5. 免费分钟数优惠 (FREE_MINUTES)
 * 6. 封顶机制 - 日封顶/周期封顶
 * 7. 跨日计费 - 多周期
 * 8. 日夜分时段计费 (DayNightRule)
 * 9. 组合时段计费 (CompositeTimeRule)
 */
public class ComprehensiveBillingTest {

    static final LocalDateTime BASE_DATE = LocalDateTime.of(2026, 3, 10, 0, 0);
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    static final DateTimeFormatter FULL_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    综合功能测试                              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        // 基础功能
        test01_BasicBilling();
        test02_ContinueMode();
        test03_BillingMode_Comparison();

        // 优惠功能
        test04_FreeRangePromotion();
        test05_FreeMinutesPromotion();
        test06_MultiplePromotions();

        // 封顶功能
        test07_DailyCap();
        test08_CycleCap();

        // 复杂场景
        test09_MultiDayBilling();
        test10_DayNightRule();
        test11_CompositeTimeRule();
    }

    // ==================== 测试场景 ====================

    /**
     * 测试01: 基础计费 - FROM_SCRATCH 模式
     * 场景：停车3小时，按小时计费，每小时2元
     */
    static void test01_BasicBilling() {
        printTestHeader("基础计费", "停车3小时，按小时计费，每小时2元");

        BillingService service = createRelativeTimeService(new BigDecimal("100"));
        BillingRequest request = createRequest("08:00", "11:00", new ArrayList<>());

        printRuleInfo("RelativeTimeRule", "单元长度: 60分钟, 单价: 2元/小时, 周期封顶: 100元");
        BillingResult result = service.calculate(request);

        printBillingResult(result);
        printVerification("预期金额", "6元 (3小时 × 2元)", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试02: CONTINUE 模式 - 继续计算
     * 场景：先算3小时，再继续算2小时
     */
    static void test02_ContinueMode() {
        printTestHeader("CONTINUE模式", "先算3小时，再继续算2小时");

        BillingService service = createRelativeTimeService(new BigDecimal("100"));

        // 第一次计算
        BillingRequest request1 = createRequest("08:00", "11:00", new ArrayList<>());
        printRuleInfo("RelativeTimeRule", "第一次计算: 08:00-11:00");
        BillingResult result1 = service.calculate(request1);
        printBillingResult(result1);

        // 第二次继续计算
        BillingRequest request2 = createRequest("08:00", "13:00", new ArrayList<>());
        request2.setPreviousCarryOver(result1.getCarryOver());
        printRuleInfo("RelativeTimeRule", "继续计算: 11:00-13:00");
        BillingResult result2 = service.calculate(request2);
        printBillingResult(result2);

        printVerification("第一次金额", "6元", result1.getFinalAmount());
        printVerification("第二次金额", "10元 (6+4)", result2.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试03: UNIT_BASED vs CONTINUOUS 模式对比
     * 场景：相同输入，不同计费模式
     */
    static void test03_BillingMode_Comparison() {
        printTestHeader("计费模式对比", "UNIT_BASED vs CONTINUOUS");

        // 免费时段：10:00-11:00
        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time("10:00"))
                        .endTime(time("11:00"))
                        .build()
        );

        // UNIT_BASED 模式
        BillingService unitBasedService = createRelativeTimeServiceWithMode(
                BConstants.BillingMode.UNIT_BASED, new BigDecimal("100"));
        BillingRequest request1 = createRequest("08:00", "12:00", promos);
        printRuleInfo("UNIT_BASED", "免费时段 10:00-11:00，计费 08:00-12:00");
        BillingResult result1 = unitBasedService.calculate(request1);
        printBillingResult(result1);

        // CONTINUOUS 模式
        BillingService continuousService = createRelativeTimeServiceWithMode(
                BConstants.BillingMode.CONTINUOUS, new BigDecimal("100"));
        BillingRequest request2 = createRequest("08:00", "12:00", promos);
        printRuleInfo("CONTINUOUS", "免费时段 10:00-11:00，计费 08:00-12:00");
        BillingResult result2 = continuousService.calculate(request2);
        printBillingResult(result2);

        printVerification("UNIT_BASED", "免费时段完全覆盖单元才免费", result1.getFinalAmount());
        printVerification("CONTINUOUS", "按免费时段边界切分，免费部分不计费", result2.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试04: 免费时段优惠 (FREE_RANGE)
     */
    static void test04_FreeRangePromotion() {
        printTestHeader("免费时段优惠", "免费时段 09:30-10:30");

        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-range-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time("09:30"))
                        .endTime(time("10:30"))
                        .build()
        );

        BillingService service = createRelativeTimeServiceWithMode(
                BConstants.BillingMode.CONTINUOUS, new BigDecimal("100"));
        BillingRequest request = createRequest("08:00", "12:00", promos);

        printRuleInfo("CONTINUOUS模式", "计费 08:00-12:00，免费 09:30-10:30");
        BillingResult result = service.calculate(request);
        printBillingResult(result);

        // 计算预期: 08:00-09:30 (1.5小时=3元) + 10:30-12:00 (1.5小时=3元) = 6元
        printVerification("预期金额", "6元 (免费1小时)", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试05: 免费分钟数优惠 (FREE_MINUTES)
     */
    static void test05_FreeMinutesPromotion() {
        printTestHeader("免费分钟数优惠", "免费60分钟");

        List<PromotionGrant> promos = List.of(
                PromotionGrant.builder()
                        .id("free-minutes-1")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .freeMinutes(60)
                        .build()
        );

        BillingService service = createRelativeTimeService(new BigDecimal("100"));
        BillingRequest request = createRequest("08:00", "11:00", promos);

        printRuleInfo("RelativeTimeRule", "计费 08:00-11:00，免费60分钟");
        BillingResult result = service.calculate(request);
        printBillingResult(result);

        printVerification("预期金额", "4元 (3小时-1小时免费=2小时)", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试06: 多种优惠组合
     */
    static void test06_MultiplePromotions() {
        printTestHeader("多种优惠组合", "免费时段 + 免费分钟数");

        List<PromotionGrant> promos = List.of(
                // 免费时段 09:00-10:00
                PromotionGrant.builder()
                        .id("free-range-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(1)
                        .beginTime(time("09:00"))
                        .endTime(time("10:00"))
                        .build(),
                // 免费分钟数 60分钟
                PromotionGrant.builder()
                        .id("free-minutes-1")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .priority(2)
                        .freeMinutes(60)
                        .build()
        );

        BillingService service = createRelativeTimeServiceWithMode(
                BConstants.BillingMode.CONTINUOUS, new BigDecimal("100"));
        BillingRequest request = createRequest("08:00", "12:00", promos);

        printRuleInfo("CONTINUOUS模式", "计费 08:00-12:00，免费时段 09:00-10:00，免费分钟 60分钟");
        BillingResult result = service.calculate(request);
        printBillingResult(result);

        // 预期: 08:00-09:00收费(2元), 09:00-10:00免费, 10:00-12:00扣减60分钟免费分钟
        // 实际取决于优惠处理逻辑
        printVerification("优惠组合", "多种优惠组合处理", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试07: 日封顶 (DayNightRule)
     */
    static void test07_DailyCap() {
        printTestHeader("日封顶", "停车超过封顶金额");

        // DayNightRule: 白天 08:00-20:00, 3元/小时; 夜间 20:00-08:00, 1元/小时; 封顶 20元/天
        BillingService service = createDayNightService(new BigDecimal("20"));
        BillingRequest request = createRequest("08:00", "20:00", new ArrayList<>());

        printRuleInfo("DayNightRule", "白天 08:00-20:00 (3元/时), 封顶 20元/天");
        BillingResult result = service.calculate(request);
        printBillingResult(result);

        // 12小时 × 3元 = 36元，但封顶20元
        printVerification("预期金额", "20元 (封顶)", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试08: 周期封顶 (RelativeTimeRule)
     */
    static void test08_CycleCap() {
        printTestHeader("周期封顶", "停车超过周期封顶金额");

        BillingService service = createRelativeTimeService(new BigDecimal("10")); // 封顶10元
        BillingRequest request = createRequest("08:00", "18:00", new ArrayList<>());

        printRuleInfo("RelativeTimeRule", "单元 60分钟, 2元/时, 周期封顶 10元");
        BillingResult result = service.calculate(request);
        printBillingResult(result);

        // 10小时 × 2元 = 20元，但封顶10元
        printVerification("预期金额", "10元 (封顶)", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试09: 跨日计费 - 多周期
     */
    static void test09_MultiDayBilling() {
        printTestHeader("跨日计费", "停车超过24小时");

        BillingService service = createRelativeTimeService(new BigDecimal("30")); // 每周期封顶30元
        BillingRequest request = createRequest("08:00", LocalDateTime.of(2026, 3, 11, 12, 0), new ArrayList<>());

        printRuleInfo("RelativeTimeRule", "28小时，周期封顶 30元/24小时");
        BillingResult result = service.calculate(request);
        printBillingResult(result);

        // 28小时，跨越两个周期，第一个周期封顶30元
        printVerification("跨日计费", "多周期独立封顶", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试10: 日夜分时段计费 (DayNightRule)
     */
    static void test10_DayNightRule() {
        printTestHeader("日夜分时段", "白天/夜间不同价格");

        // 白天 08:00-20:00 (3元/时)，夜间 20:00-08:00 (1元/时)
        BillingService service = createDayNightService(new BigDecimal("50"));
        BillingRequest request = createRequest("10:00", "22:00", new ArrayList<>());

        printRuleInfo("DayNightRule", "白天 08:00-20:00 (3元/时), 夜间 20:00-08:00 (1元/时)");
        BillingResult result = service.calculate(request);
        printBillingResult(result);

        // 10:00-20:00 白天 10小时 = 30元，20:00-22:00 夜间 2小时 = 2元，总计 32元
        printVerification("预期金额", "32元 (白天30 + 夜间2)", result.getFinalAmount());
        printTestEnd();
    }

    /**
     * 测试11: 组合时段计费 (CompositeTimeRule)
     */
    static void test11_CompositeTimeRule() {
        printTestHeader("组合时段计费", "相对时段 + 自然时段价格");

        BillingService service = createCompositeTimeService();
        BillingRequest request = createRequest("08:00", "14:00", new ArrayList<>());

        printRuleInfo("CompositeTimeRule", "相对时段 + 自然时段价格");
        BillingResult result = service.calculate(request);
        printBillingResult(result);

        printVerification("组合计费", "按自然时段价格计算", result.getFinalAmount());
        printTestEnd();
    }

    // ==================== 辅助方法 ====================

    static void printTestHeader(String title, String desc) {
        System.out.println("\n┌─────────────────────────────────────────────────────────────┐");
        System.out.printf("│ 测试: %-52s│%n", title);
        System.out.printf("│ 场景: %-52s│%n", desc);
        System.out.println("└─────────────────────────────────────────────────────────────┘");
    }

    static void printRuleInfo(String rule, String info) {
        System.out.println("\n📋 计费规则: " + rule);
        System.out.println("   " + info);
    }

    static void printBillingResult(BillingResult result) {
        System.out.println("\n📊 计费结果:");
        System.out.println("   ├─ 最终金额: " + result.getFinalAmount() + " 元");
        System.out.println("   ├─ 计算结束: " + result.getCalculationEndTime().format(TIME_FORMAT));

        if (result.getUnits() != null && !result.getUnits().isEmpty()) {
            System.out.println("   └─ 计费单元 (" + result.getUnits().size() + " 个):");

            // 最多显示前10个单元
            int showCount = Math.min(result.getUnits().size(), 10);
            for (int i = 0; i < showCount; i++) {
                BillingUnit unit = result.getUnits().get(i);
                String freeMark = unit.isFree() ? " [免费:" + unit.getFreePromotionId() + "]" : "";
                System.out.printf("      %s - %s (%dm) %s元%s%n",
                        unit.getBeginTime().format(TIME_FORMAT),
                        unit.getEndTime().format(TIME_FORMAT),
                        unit.getDurationMinutes(),
                        unit.getChargedAmount(),
                        freeMark);
            }
            if (result.getUnits().size() > 10) {
                System.out.println("      ... 还有 " + (result.getUnits().size() - 10) + " 个单元");
            }
        }
    }

    static void printVerification(String item, String expected, BigDecimal actual) {
        System.out.println("\n✅ 验证: " + item);
        System.out.println("   预期: " + expected);
        System.out.println("   实际: " + actual + " 元");
    }

    static void printTestEnd() {
        System.out.println();
    }

    static LocalDateTime time(String timeStr) {
        String[] parts = timeStr.split(":");
        return BASE_DATE.withHour(Integer.parseInt(parts[0])).withMinute(Integer.parseInt(parts[1]));
    }

    static BillingRequest createRequest(String begin, String end, List<PromotionGrant> promos) {
        return createRequest(begin, time(end), promos);
    }

    static BillingRequest createRequest(String begin, LocalDateTime end, List<PromotionGrant> promos) {
        BillingRequest request = new BillingRequest();
        request.setId("test-" + System.currentTimeMillis());
        request.setBeginTime(time(begin));
        request.setEndTime(end);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("scheme-default");
        request.setExternalPromotions(promos);
        return request;
    }

    // ==================== Service 创建方法 ====================

    static BillingService createRelativeTimeService(BigDecimal maxCharge) {
        return createRelativeTimeServiceWithMode(BConstants.BillingMode.UNIT_BASED, maxCharge);
    }

    static BillingService createRelativeTimeServiceWithMode(BConstants.BillingMode mode, BigDecimal maxCharge) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
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
                                        .unitPrice(new BigDecimal("2"))
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

        return createBillingService(resolver);
    }

    static BillingService createDayNightService(BigDecimal maxCharge) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.UNIT_BASED;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return DayNightConfig.builder()
                        .id("day-night-1")
                        .dayBeginMinute(8 * 60)   // 08:00
                        .dayEndMinute(20 * 60)    // 20:00
                        .unitMinutes(60)
                        .blockWeight(new BigDecimal("0.5"))
                        .dayUnitPrice(new BigDecimal("3"))
                        .nightUnitPrice(new BigDecimal("1"))
                        .maxChargeOneDay(maxCharge)
                        .build();
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
            }
        };

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return createBillingService(resolver, ruleRegistry);
    }

    static BillingService createCompositeTimeService() {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.UNIT_BASED;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return CompositeTimeConfig.builder()
                        .id("composite-1")
                        .maxChargeOneCycle(new BigDecimal("50"))
                        .periods(List.of(
                                CompositePeriod.builder()
                                        .beginMinute(0)
                                        .endMinute(720)   // 0-12小时
                                        .unitMinutes(60)
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
                                        .build(),
                                CompositePeriod.builder()
                                        .beginMinute(720)
                                        .endMinute(1440)
                                        .unitMinutes(60)
                                        .naturalPeriods(List.of(
                                                NaturalPeriod.builder()
                                                        .beginMinute(0)
                                                        .endMinute(8 * 60)
                                                        .unitPrice(new BigDecimal("1"))
                                                        .build(),
                                                NaturalPeriod.builder()
                                                        .beginMinute(8 * 60)
                                                        .endMinute(20 * 60)
                                                        .unitPrice(new BigDecimal("2"))
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
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new ArrayList<>();
            }
        };

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.COMPOSITE_TIME, new CompositeTimeRule());

        return createBillingService(resolver, ruleRegistry);
    }

    static BillingService createBillingService(BillingConfigResolver resolver) {
        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
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