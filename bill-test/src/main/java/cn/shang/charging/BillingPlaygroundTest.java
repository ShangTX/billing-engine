package cn.shang.charging;

import cn.shang.charging.billing.*;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.compositetime.*;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.flatfree.FlatFreeRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.util.JacksonUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 计费测试运行器 —— 方便指定计费参数与规则配置，运行得到完整计费结果。
 * <p>
 * 用法：
 * <ol>
 *   <li>修改下方 {@link #main} 中的场景配置（时间、模式、规则、优惠）</li>
 *   <li>运行 main 方法，控制台输出完整计费明细</li>
 * </ol>
 * <p>
 * 支持全部 4 种计算模式（{@link BConstants.CalculationMode}）、3 种规则族
 * （dayNight / relativeTime / compositeTime）、
 * 外部优惠（免费时段 / 免费分钟 / 智能免费分钟）、优惠规则配置（前N分钟免费）、
 * 方案切换、等效金额计算。
 * <p>
 * 新增自定义场景：复制 {@link #scenario1_dayNight_continuous()} 改参数即可，
 * 或调用 {@link #run(Scenario)} 传入自定义 {@link Scenario}。
 */
public class BillingPlaygroundTest {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static void main(String[] args) {
        // Windows 命令行默认 GBK，强制 stdout 用 UTF-8，避免中文乱码（IDE 运行不受影响）
        try {
            System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException ignored) {
        }

        System.out.println("=".repeat(72));
        System.out.println("  计费测试运行器 — 修改 main 方法中的场景配置后运行");
        System.out.println("=".repeat(72));

        // ▼▼▼ 在这里选择/修改要运行的场景 ▼▼▼
//        run(scenario1_dayNight_continuous());        // 日夜分时段 + 连续计费 + 免费时段
//        run(scenario2_dayNight_durationGlobal());   // 日夜 + 全局时长计费 + 智能免费分钟
//        run(scenario3_compositeTime_period());      // 混合时段 + 周期时长计费 + 免费分钟
//        run(scenario4_schemeSwitch());              // 方案切换（多段计费）
//        run(scenario5_withEquivalentAmount());      // 等效金额计算
//        run(scenario6_startFree());                   // 前N分钟免费（START_FREE）
//        run(scenario_cust());                         // 常规金额计算

        // Snap测试场景
        System.out.println("\n" + "=".repeat(72));
        System.out.println("  Snap算法测试场景");
        System.out.println("=".repeat(72));
//        run(scenario_snap1_exactBoundary());         // Snap测试1：边界恰好落在单元边界
        run(scenario_snap2_dayBegin_belongToDay());  // Snap测试2：dayBegin跨单元归属day
//        run(scenario_snap3_dayBegin_belongToNight()); // Snap测试3：dayBegin跨单元归属night
//        run(scenario_snap4_dayEnd_belongToDay());    // Snap测试4：dayEnd跨单元归属day
//        run(scenario_snap5_dayEnd_belongToNight());  // Snap测试5：dayEnd跨单元归属night
        // ▲▲▲ 在这里选择/修改要运行的场景 ▲▲▲

        // 无优惠snap边界测试场景
        System.out.println("\n" + "=".repeat(72));
        System.out.println("  无优惠Snap边界测试场景");
        System.out.println("=".repeat(72));
//        run(scenario_noPromo_dayBegin_belongToDay());    // 无优惠：dayBegin归属day
//        run(scenario_noPromo_dayBegin_belongToNight());   // 无优惠：dayBegin归属night
//        run(scenario_noPromo_dayEnd_belongToDay());       // 无优惠：dayEnd归属day
//        run(scenario_noPromo_dayEnd_belongToNight());     // 无优惠：dayEnd归属night

//        run(scenario_simplify_dayNight());     // 长期计费简化
    }

    // ==================== 预设场景（复制改参数即可） ====================

    /**
     * 场景1：日夜分时段 + CONTINUOUS 模式 + 免费时段优惠。
     * <p>
     * 白天 08:00-20:00 @ 2元/时，夜间 @ 1元/时，60 分钟单元，每日封顶 50 元。
     * 计费 09:00-15:30，中间 12:00-13:00 免费。
     */
    static Scenario scenario1_dayNight_continuous() {
        return Scenario.builder()
                .name("日夜分时段 + CONTINUOUS + 免费时段")
                .beginTime(LocalDateTime.of(2026, 7, 7, 9, 0))
                .endTime(LocalDateTime.of(2026, 7, 7, 15, 30))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-1")
                        .setDayBeginMinute(8 * 60)
                        .setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60)
                        .setMaxChargeOneDay(new BigDecimal("15"))
                        .setBlockWeight(new BigDecimal("0.5")))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("lunch-free")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(1)
                                .beginTime(LocalDateTime.of(2026, 7, 7, 12, 0))
                                .endTime(LocalDateTime.of(2026, 7, 7, 13, 0))
                                .build()))
                .build();
    }

    /**
     * 场景2：日夜分时段 + DURATION_GLOBAL 模式 + 智能免费分钟。
     * <p>
     * SMART_FREE_MINUTES 仅在 DURATION_GLOBAL 模式下消费，按单价降序优先覆盖高价时段。
     */
    static Scenario scenario2_dayNight_durationGlobal() {
        return Scenario.builder()
                .name("日夜分时段 + DURATION_GLOBAL + 智能免费分钟")
                .beginTime(LocalDateTime.of(2026, 7, 7, 8, 0))
                .endTime(LocalDateTime.of(2026, 7, 8, 8, 0))  // 跨天 24 小时
                .calculationMode(BConstants.CalculationMode.DURATION_GLOBAL)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-global")
                        .setDayBeginMinute(8 * 60)
                        .setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60)
                        .setMaxChargeOneDay(new BigDecimal("30"))
                        .setBlockWeight(new BigDecimal("0.5")))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("smart-60")
                                .type(BConstants.PromotionType.SMART_FREE_MINUTES)
                                .source(BConstants.PromotionSource.COUPON)
                                .freeMinutes(60)
                                .priority(1)
                                .build()))
                .build();
    }

    /**
     * 场景3：混合时段 + DURATION_PERIOD 模式 + 免费分钟数。
     */
    static Scenario scenario3_compositeTime_period() {
        return Scenario.builder()
                .name("混合时段 + DURATION_PERIOD + 免费分钟")
                .beginTime(LocalDateTime.of(2026, 7, 7, 7, 0))
                .endTime(LocalDateTime.of(2026, 7, 7, 19, 0))
                .calculationMode(BConstants.CalculationMode.DURATION_PERIOD)
                .ruleConfig(CompositeTimeConfig.builder()
                        .id("comp-1")
                        .maxChargeOneCycle(new BigDecimal("40"))
                        .periods(List.of(
                                CompositePeriod.builder()
                                        .beginMinute(0)
                                        .endMinute(1440)
                                        .unitMinutes(60)
                                        .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                                        .naturalPeriods(List.of(
                                                NaturalPeriod.builder()
                                                        .beginMinute(0).endMinute(8 * 60)
                                                        .unitPrice(new BigDecimal("1")).build(),
                                                NaturalPeriod.builder()
                                                        .beginMinute(8 * 60).endMinute(20 * 60)
                                                        .unitPrice(new BigDecimal("3")).build(),
                                                NaturalPeriod.builder()
                                                        .beginMinute(20 * 60).endMinute(1440)
                                                        .unitPrice(new BigDecimal("1")).build()))
                                        .build()))
                        .build())
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("free-90min")
                                .type(BConstants.PromotionType.FREE_MINUTES)
                                .source(BConstants.PromotionSource.COUPON)
                                .freeMinutes(90)
                                .priority(1)
                                .build()))
                .build();
    }

    /**
     * 场景4：方案切换（多段计费）。09:00-12:00 用 scheme-a，12:00-18:00 用 scheme-b。
     */
    static Scenario scenario4_schemeSwitch() {
        // 两套规则通过 schemeId 区分，resolveChargingRule 按 schemeId 返回不同配置
        Scenario scenario = Scenario.builder()
                .name("方案切换（多段计费）")
                .beginTime(LocalDateTime.of(2026, 7, 7, 9, 0))
                .endTime(LocalDateTime.of(2026, 7, 7, 18, 0))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .schemeChanges(List.of(
                        buildSchemeChange("scheme-a", "scheme-b",
                                LocalDateTime.of(2026, 7, 7, 12, 0))))
                .build();
        // scheme-a：白天 2 元/时；scheme-b：白天 3 元/时（下午更贵）
        scenario.ruleConfigByScheme = Map.of(
                "scheme-a", new DayNightConfig()
                        .setId("dn-a")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5")),
                "scheme-b", new DayNightConfig()
                        .setId("dn-b")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("3")).setNightUnitPrice(new BigDecimal("1.5"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("60"))
                        .setBlockWeight(new BigDecimal("0.5")));
        return scenario;
    }

    /**
     * 场景5：开启等效金额计算（消去法精确计算每个优惠使金额减少多少）。
     */
    static Scenario scenario5_withEquivalentAmount() {
        return Scenario.builder()
                .name("等效金额计算（消去法）")
                .beginTime(LocalDateTime.of(2026, 7, 7, 9, 0))
                .endTime(LocalDateTime.of(2026, 7, 7, 15, 0))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-eq")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5")))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("free-range-2h")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(1)
                                .beginTime(LocalDateTime.of(2026, 7, 7, 11, 0))
                                .endTime(LocalDateTime.of(2026, 7, 7, 13, 0))
                                .build(),
                        PromotionGrant.builder()
                                .id("free-min-30")
                                .type(BConstants.PromotionType.FREE_MINUTES)
                                .source(BConstants.PromotionSource.COUPON)
                                .freeMinutes(30)
                                .priority(2)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())  // null = 不限，全部参与
                .build();
    }

    /**
     * 场景6：前N分钟免费（START_FREE）优惠规则。
     * <p>
     * START_FREE 从计费起点生成免费时段，与 FREE_RANGE 按优先级合并，
     * 不会像 FREE_MINUTES 那样避开已有免费时段。
     */
    static Scenario scenario6_startFree() {
        return Scenario.builder()
                .name("日夜分时段 + CONTINUOUS + 前30分钟免费")
                .beginTime(LocalDateTime.of(2026, 7, 7, 8, 12))
                .endTime(LocalDateTime.of(2026, 7, 7, 21, 36))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-startfree")
                        .setDayBeginMinute(8 * 60)
                        .setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60)
                        .setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5")))
                .promotionRuleConfigs(List.of(
                        new FreeMinutesPromotionConfig().setMinutes(20)
                                .setId("fn-min-20").setPriority(2)
                ))
                .promotionRuleConfigs(List.of(
                        StartFreePromotionConfig.builder()
                                .id("start-free-30")
                                .minutes(30)
                                .priority(1)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())  // null = 不限，全部参与
                .build();
    }


    /**
     * 场景5：开启等效金额计算（消去法精确计算每个优惠使金额减少多少）。
     */
    static Scenario scenario_cust() {
        return Scenario.builder()
                .name("常规金额计算")
                .beginTime(LocalDateTime.of(2026, 7, 7, 5, 13))
                .endTime(LocalDateTime.of(2026, 7, 7, 21, 36))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-eq")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("free-range-2h")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(1)
                                .beginTime(LocalDateTime.of(2026, 7, 7, 11, 0))
                                .endTime(LocalDateTime.of(2026, 7, 7, 13, 0))
                                .build()))
                .promotionRuleConfigs(List.of(
                        StartFreePromotionConfig.builder()
                                .id("start-free-30")
                                .minutes(30)
                                .priority(1)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())
                .build();
    }

    /**
     * Snap测试1：dayBegin边界恰好落在单元边界 + 多种优惠混合。
     * 计费：06:47-11:23，包含免费时段和前N分钟免费。
     * dayBegin=08:00从06:47对齐：下一个单元边界是07:47, 08:47, 09:47...
     * 但08:00不在单元边界上（06:47+120=08:47），所以会snap。
     * 简化：06:00-11:23，从06:00对齐，08:00恰好落在单元边界（06:00+120=08:00）。
     */
    static Scenario scenario_snap1_exactBoundary() {
        return Scenario.builder()
                .name("Snap测试1：边界恰好落在单元边界 + 优惠混合")
                .beginTime(LocalDateTime.of(2026, 7, 7, 6, 0))
                .endTime(LocalDateTime.of(2026, 7, 7, 11, 23))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-snap1")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("morning-free")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(2)
                                .beginTime(LocalDateTime.of(2026, 7, 7, 9, 17))
                                .endTime(LocalDateTime.of(2026, 7, 7, 10, 0))
                                .build()))
                .promotionRuleConfigs(List.of(
                        StartFreePromotionConfig.builder()
                                .id("start-free-25")
                                .minutes(25)
                                .priority(1)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())
                .build();
    }

    /**
     * Snap测试2：dayBegin跨单元归属day + 免费时段和START_FREE混合。
     * 计费：06:23-12:37，从06:23对齐单元。
     * 单元：07:23-08:23包含dayBegin，day=23分钟不够。
     * 改用：05:47-12:19，从05:47对齐。
     * 单元：05:47-06:47, 06:47-07:47, 07:47-08:47（包含dayBegin=08:00）。
     * day时段：08:00-08:47=47分钟>=30，归属day，snap到08:47。
     */
    static Scenario scenario_snap2_dayBegin_belongToDay() {
        return Scenario.builder()
                .name("Snap测试2：dayBegin跨单元归属day + 优惠混合")
                .beginTime(LocalDateTime.of(2026, 7, 7, 5, 47))
                .endTime(LocalDateTime.of(2026, 7, 7, 12, 19))
                .calculationMode(BConstants.CalculationMode.DURATION_GLOBAL)
                .ruleConfig(new DayNightConfig()
                        .setId("dnx2")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("15"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(true)
                        .setIncompleteUnitChargeMode(BConstants.IncompleteUnitChargeMode.PROPORTIONAL))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("mid-free")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(2)
                                .beginTime(LocalDateTime.of(2026, 7, 7, 10, 13))
                                .endTime(LocalDateTime.of(2026, 7, 7, 11, 7))
                                .build()))
                .promotionRuleConfigs(List.of(
                        StartFreePromotionConfig.builder()
                                .id("start-free-33")
                                .minutes(33)
                                .priority(1)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())
                .build();
    }

    /**
     * Snap测试3：dayBegin跨单元归属night + 多种优惠。
     * 计费：04:51-13:28，从04:51对齐单元。
     * 单元：07:51-08:51包含dayBegin=08:00，day=09:00-08:51=9分钟不够...
     * 简化：04:32-13:14，从04:32对齐。
     * 单元：04:32-05:32, ..., 07:32-08:32（包含dayBegin）。
     * day时段：08:00-08:32=28分钟<30，归属night，snap到07:32。
     */
    static Scenario scenario_snap3_dayBegin_belongToNight() {
        return Scenario.builder()
                .name("Snap测试3：dayBegin跨单元归属night + 优惠混合")
                .beginTime(LocalDateTime.of(2026, 7, 7, 4, 32))
                .endTime(LocalDateTime.of(2026, 7, 7, 13, 14))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-snap3")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("noon-free")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(2)
                                .beginTime(LocalDateTime.of(2026, 7, 7, 11, 23))
                                .endTime(LocalDateTime.of(2026, 7, 7, 12, 17))
                                .build()))
                .promotionRuleConfigs(List.of(
                        StartFreePromotionConfig.builder()
                                .id("start-free-28")
                                .minutes(28)
                                .priority(1)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())
                .build();
    }

    /**
     * Snap测试4：dayEnd跨单元归属day + 复杂优惠组合。
     * 计费：17:19-23:42，从17:19对齐单元。
     * 单元：19:19-20:19包含dayEnd=20:00，day=19:19-20:00=41分钟>=30。
     * 归属day，snap到20:19。
     */
    static Scenario scenario_snap4_dayEnd_belongToDay() {
        return Scenario.builder()
                .name("Snap测试4：dayEnd跨单元归属day + 优惠混合")
                .beginTime(LocalDateTime.of(2026, 7, 7, 17, 19))
                .endTime(LocalDateTime.of(2026, 7, 7, 23, 42))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-snap4")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("evening-free")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(2)
                                .beginTime(LocalDateTime.of(2026, 7, 7, 18, 37))
                                .endTime(LocalDateTime.of(2026, 7, 7, 19, 25))
                                .build()))
                .promotionRuleConfigs(List.of(
                        StartFreePromotionConfig.builder()
                                .id("start-free-22")
                                .minutes(22)
                                .priority(1)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())
                .build();
    }

    /**
     * Snap测试5：dayEnd跨单元归属night + 实际场景混合优惠。
     * 计费：18:41-22:57，从18:41对齐单元。
     * 单元：19:41-20:41包含dayEnd=20:00，day=18:41-20:00=19分钟<30。
     * 归属night，snap到19:41。
     */
    static Scenario scenario_snap5_dayEnd_belongToNight() {
        return Scenario.builder()
                .name("Snap测试5：dayEnd跨单元归属night + 优惠混合")
                .beginTime(LocalDateTime.of(2026, 7, 7, 18, 41))
                .endTime(LocalDateTime.of(2026, 7, 7, 22, 57))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-snap5")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("late-free")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(2)
                                .beginTime(LocalDateTime.of(2026, 7, 7, 19, 53))
                                .endTime(LocalDateTime.of(2026, 7, 7, 20, 31))
                                .build()))
                .promotionRuleConfigs(List.of(
                        StartFreePromotionConfig.builder()
                                .id("start-free-19")
                                .minutes(19)
                                .priority(1)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())
                .build();
    }

    /**
     * 计费简化测试
     * 计费：1.1日-1.10日
     */
    static Scenario scenario_simplify_dayNight() {
        return Scenario.builder()
                .name("计费简化测试 + 优惠混合")
                .beginTime(LocalDateTime.of(2026, 1, 1, 18, 41))
                .endTime(LocalDateTime.of(2026, 1, 12, 22, 57))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-x")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("15"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false).setSimplifiedSupported(true))
                .externalPromotions(List.of(
                        PromotionGrant.builder()
                                .id("late-free")
                                .type(BConstants.PromotionType.FREE_RANGE)
                                .source(BConstants.PromotionSource.COUPON)
                                .priority(2)
                                .beginTime(LocalDateTime.of(2026, 1, 7, 19, 53))
                                .endTime(LocalDateTime.of(2026, 1, 7, 20, 31))
                                .build()))
                .promotionRuleConfigs(List.of(
                        StartFreePromotionConfig.builder()
                                .id("start-free-19")
                                .minutes(19)
                                .priority(1)
                                .build()))
                .equivalentAmountSpec(EquivalentAmountSpec.builder().build())
                .build();
    }

    // ==================== 无优惠snap边界测试 ====================

    /**
     * 无优惠测试1：dayBegin边界，归属day。
     * 计费：07:30-10:00，从07:30对齐单元。
     * 单元：07:30-08:30包含dayBegin=08:00，day=08:00-08:30=30分钟>=30，归属day。
     * Snap到08:30，整个单元用day价格。
     * 预期：07:30-08:30(day价) + 08:30-09:30(day) + 09:30-10:00(day截断)
     */
    static Scenario scenario_noPromo_dayBegin_belongToDay() {
        return Scenario.builder()
                .name("无优惠：dayBegin跨单元归属day")
                .beginTime(LocalDateTime.of(2026, 7, 7, 7, 30))
                .endTime(LocalDateTime.of(2026, 7, 7, 10, 0))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-nopromo1")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .build();
    }

    /**
     * 无优惠测试2：dayBegin边界，归属night。
     * 计费：07:20-10:00，从07:20对齐单元。
     * 单元：07:20-08:20包含dayBegin=08:00，day=08:00-08:20=20分钟<30，归属night。
     * Snap到08:20，整个单元用night价格。
     * 预期：07:20-08:20(night价) + 08:20-09:20(day) + 09:20-10:00(day截断)
     */
    static Scenario scenario_noPromo_dayBegin_belongToNight() {
        return Scenario.builder()
                .name("无优惠：dayBegin跨单元归属night")
                .beginTime(LocalDateTime.of(2026, 7, 7, 7, 20))
                .endTime(LocalDateTime.of(2026, 7, 7, 10, 0))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-nopromo2")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .build();
    }

    /**
     * 无优惠测试3：dayEnd边界，归属day。
     * 计费：19:30-22:00，从19:30对齐单元。
     * 单元：19:30-20:30包含dayEnd=20:00，day=19:30-20:00=30分钟>=30，归属day。
     * Snap到19:30，整个单元用day价格。
     * 预期：19:30-20:30(day价) + 20:30-21:30(night) + 21:30-22:00(night截断)
     */
    static Scenario scenario_noPromo_dayEnd_belongToDay() {
        return Scenario.builder()
                .name("无优惠：dayEnd跨单元归属day")
                .beginTime(LocalDateTime.of(2026, 7, 7, 19, 30))
                .endTime(LocalDateTime.of(2026, 7, 7, 22, 0))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-nopromo3")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .build();
    }

    /**
     * 无优惠测试4：dayEnd边界，归属night。
     * 计费：19:40-22:00，从19:40对齐单元。
     * 单元：19:40-20:40包含dayEnd=20:00，day=19:40-20:00=20分钟<30，归属night。
     * Snap到20:40，整个单元用night价格。
     * 预期：19:40-20:40(night价) + 20:40-21:40(night) + 21:40-22:00(night截断)
     */
    static Scenario scenario_noPromo_dayEnd_belongToNight() {
        return Scenario.builder()
                .name("无优惠：dayEnd跨单元归属night")
                .beginTime(LocalDateTime.of(2026, 7, 7, 19, 40))
                .endTime(LocalDateTime.of(2026, 7, 7, 22, 0))
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .ruleConfig(new DayNightConfig()
                        .setId("dn-nopromo4")
                        .setDayBeginMinute(8 * 60).setDayEndMinute(20 * 60)
                        .setDayUnitPrice(new BigDecimal("2")).setNightUnitPrice(new BigDecimal("1"))
                        .setUnitMinutes(60).setMaxChargeOneDay(new BigDecimal("50"))
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setSplitDayNightBoundary(false))
                .build();
    }

    // ==================== 引擎执行与结果打印 ====================

    /**
     * 执行一个计费场景并打印完整结果。
     */
    static void run(Scenario scenario) {
        System.out.println();
        System.out.println("┌" + "─".repeat(70) + "┐");
        System.out.printf("│ 场景: %-66s│%n", scenario.name);
        System.out.println("└" + "─".repeat(70) + "┘");

        // 1. 构建配置解析器（支持单方案 / 方案切换）
        BillingConfigResolver resolver = buildResolver(scenario);

        // 2. 组装引擎
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        // 构造函数已注册 dayNight/relativeTime/compositeTime，flatFree 按需补充
        ruleRegistry.register(BConstants.ChargeRuleType.FLAT_FREE, new FlatFreeRule());

        PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
        promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        promotionRuleRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        PromotionEngine promotionEngine = new PromotionEngine(
                resolver, new FreeTimeRangeMerger(), promotionRuleRegistry);

        BillingService billingService = new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());

        // 3. 构建请求
        BillingRequest request = new BillingRequest();
        request.setId("playground-" + System.currentTimeMillis());
        request.setBeginTime(scenario.beginTime);
        request.setEndTime(scenario.endTime);
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        if (scenario.schemeChanges != null && !scenario.schemeChanges.isEmpty()) {
            request.setSchemeChanges(scenario.schemeChanges);
        } else {
            request.setSchemeChanges(List.of());
            request.setSchemeId(scenario.schemeId != null ? scenario.schemeId : "scheme-1");
        }
        if (scenario.externalPromotions != null && !scenario.externalPromotions.isEmpty()) {
            request.setExternalPromotions(scenario.externalPromotions);
        }
        if (scenario.timeRoundingMode != null) {
            request.setTimeRoundingMode(scenario.timeRoundingMode);
        }
        if (scenario.equivalentAmountSpec != null) {
            request.setEquivalentAmountSpec(scenario.equivalentAmountSpec);
        }

        // 4. 打印输入
        printInput(scenario, request);

        // 5. 执行计费
        BillingResult result = billingService.calculate(request);

        // 6. 打印结果
        printResult(scenario, request, result);
    }

    static BillingConfigResolver buildResolver(Scenario scenario) {
        return new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return scenario.calculationMode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId,
                                                  LocalDateTime segmentStart,
                                                  LocalDateTime segmentEnd,
                                                  Map<String, Object> context) {
                // 方案切换场景：按 schemeId 返回不同规则
                if (scenario.ruleConfigByScheme != null
                        && scenario.ruleConfigByScheme.containsKey(schemeId)) {
                    return scenario.ruleConfigByScheme.get(schemeId);
                }
                return scenario.ruleConfig;
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId,
                                                                   LocalDateTime segmentStart,
                                                                   LocalDateTime segmentEnd,
                                                                   Map<String, Object> context) {
                return scenario.promotionRuleConfigs != null ? scenario.promotionRuleConfigs : List.of();
            }

            @Override
            public int getSimplifiedCycleThreshold() {
                return 2;
            }
        };
    }

    static void printInput(Scenario scenario, BillingRequest request) {
        long minutes = Duration.between(scenario.beginTime, scenario.endTime).toMinutes();
        System.out.println("\n【输入参数】");
        System.out.println("  计费时间: " + scenario.beginTime.format(FMT) + " — " + scenario.endTime.format(FMT)
                + " (" + minutes + " 分钟)");
        System.out.println("  计算模式: " + scenario.calculationMode);
        if (scenario.schemeChanges != null && !scenario.schemeChanges.isEmpty()) {
            System.out.println("  方案切换: " + scenario.schemeChanges.size() + " 次");
            for (SchemeChange sc : scenario.schemeChanges) {
                System.out.println("    " + sc.getLastSchemeId() + " → " + sc.getNextSchemeId()
                        + " @ " + sc.getChangeTime().format(FMT));
            }
        } else {
            System.out.println("  方案ID: " + request.getSchemeId());
        }
        if (scenario.ruleConfig != null) {
            System.out.println("  规则类型: " + scenario.ruleConfig.getType());
        } else if (scenario.ruleConfigByScheme != null) {
            System.out.println("  规则类型: 多方案（dayNight）");
        }
        if (scenario.externalPromotions != null && !scenario.externalPromotions.isEmpty()) {
            System.out.println("  外部优惠: " + scenario.externalPromotions.size() + " 个");
            for (PromotionGrant p : scenario.externalPromotions) {
                System.out.println("    - " + p.getId() + " [" + p.getType() + "]"
                        + (p.getFreeMinutes() != null ? " freeMinutes=" + p.getFreeMinutes() : "")
                        + (p.getBeginTime() != null ? " " + p.getBeginTime().format(FMT) : "")
                        + (p.getEndTime() != null ? "—" + p.getEndTime().format(FMT) : ""));
            }
        }
        if (scenario.equivalentAmountSpec != null) {
            System.out.println("  等效金额: 开启（" + scenario.equivalentAmountSpec + "）");
        }
    }

    static void printResult(Scenario scenario, BillingRequest request, BillingResult result) {
        System.out.println("\n【计费结果】");
        System.out.println("  ★ 最终应收金额: " + result.getFinalAmount() + " 元");
        System.out.println("  计算结束时间: " + (result.getCalculationEndTime() != null
                ? result.getCalculationEndTime().format(FMT) : "无"));
        if (result.getTotalEquivalentAmount() != null) {
            System.out.println("  等效优惠金额汇总: " + result.getTotalEquivalentAmount() + " 元");
        }

        // 计费单元明细（CONTINUOUS/UNIT_BASED）
        if (result.getUnits() != null && !result.getUnits().isEmpty()) {
            System.out.println("\n【计费单元明细】共 " + result.getUnits().size() + " 个");
            System.out.printf("  %-3s %-16s %-11s %6s %8s %9s %7s %s%n",
                    "#", "开始", "结束", "分钟", "单价", "原始", "实收", "标记");
            for (int i = 0; i < result.getUnits().size(); i++) {
                BillingUnit u = result.getUnits().get(i);
                String mark = "";
                if (u.isFree()) mark += " [免费:" + u.getFreePromotionId() + "]";
                if (Boolean.TRUE.equals(u.getIsTruncated())) mark += " [截断]";
                if (u.isCompact()) mark += " [compact×" + u.getCount() + "]";
                if (isSimplified(u)) mark += " [简化]";
                System.out.printf("  %-3d %-16s %-16s %6d %8s %10s %10s %s%n",
                        i + 1,
                        u.getBeginTime().format(FMT),
                        u.getEndTime().format(FMT),
                        u.getDurationMinutes(),
                        u.getUnitPrice(),
                        u.getOriginalAmount(),
                        u.getChargedAmount(),
                        mark);
            }
        }

        // 时长计费段明细（DURATION_PERIOD/DURATION_GLOBAL）
        if (result.getDurationSegments() != null && !result.getDurationSegments().isEmpty()) {
            System.out.println("\n【时长计费段明细】共 " + result.getDurationSegments().size() + " 段");
            System.out.printf("  %-3s %-16s %-16s %-10s %6s %8s %10s %10s %s%n",
                    "#", "开始", "结束", "时段", "分钟", "单价", "原价", "应收", "标记");
            for (int i = 0; i < result.getDurationSegments().size(); i++) {
                DurationSegment d = result.getDurationSegments().get(i);
                String mark = d.freePromotionId() != null ? " [免费:" + d.freePromotionId() + "]" : "";
                System.out.printf("  %-3d %-16s %-16s %-10s %6d %8s %10s %10s %s%n",
                        i + 1,
                        d.beginTime() == null ? "-" : d.beginTime().format(FMT),
                        d.endTime() == null ? "-" : d.endTime().format(FMT),
                        d.periodLabel(),
                        d.chargedMinutes(),
                        d.unitPrice(),
                        d.originalAmount(),
                        d.chargedAmount(),
                        mark);
            }
        }

        // 优惠使用情况
        if (result.getPromotionUsages() != null && !result.getPromotionUsages().isEmpty()) {
            System.out.println("\n【优惠使用情况】");
            System.out.printf("  %-20s %-20s %-8s %-8s %-16s %-16s %s%n",
                    "优惠ID", "类型", "授予", "使用", "使用起点", "使用终点", "等效金额");
            for (PromotionUsage u : result.getPromotionUsages()) {
                System.out.printf("  %-20s %-20s %-8d %-8d %-16s %-16s %s%n",
                        u.getPromotionId(),
                        u.getType(),
                        u.getGrantedMinutes(),
                        u.getUsedMinutes(),
                        u.getUsedFrom() != null ? u.getUsedFrom().format(FMT) : "-",
                        u.getUsedTo() != null ? u.getUsedTo().format(FMT) : "-",
                        u.getEquivalentAmount() != null ? u.getEquivalentAmount() : "-");
            }
        }

        // 完整结果 JSON 序列化（方便复制、对比、传给前端）
        System.out.println("\n【完整结果 JSON】");
        System.out.println(JacksonUtils.toJsonString(result));

        System.out.println("\n" + "─".repeat(72));
    }

    @SuppressWarnings("unchecked")
    static boolean isSimplified(BillingUnit unit) {
        if (unit == null || unit.getRuleData() == null) return false;
        if (!(unit.getRuleData() instanceof Map<?, ?> rawMap)) return false;
        return Boolean.TRUE.equals(((Map<String, Object>) rawMap).get("isSimplified"));
    }

    /**
     * 构建 SchemeChange（SchemeChange 的 setter 非链式，用辅助方法简化）
     */
    static SchemeChange buildSchemeChange(String lastSchemeId, String nextSchemeId, LocalDateTime changeTime) {
        SchemeChange sc = new SchemeChange();
        sc.setLastSchemeId(lastSchemeId);
        sc.setNextSchemeId(nextSchemeId);
        sc.setChangeTime(changeTime);
        return sc;
    }

    // ==================== 场景配置对象 ====================

    /**
     * 计费场景配置。用 {@link #builder()} 创建，设置后传入 {@link #run(Scenario)}。
     * <p>
     * 单方案场景：设置 {@code ruleConfig} + {@code schemeId}（可选，默认 "scheme-1"）。
     * 方案切换场景：设置 {@code schemeChanges} + {@code ruleConfigByScheme}（schemeId → 规则配置）。
     */
    public static class Scenario {
        public String name;
        public LocalDateTime beginTime;
        public LocalDateTime endTime;
        public BConstants.CalculationMode calculationMode;
        public RuleConfig ruleConfig;
        /**
         * 方案切换场景：schemeId → 规则配置（与 ruleConfig 二选一）
         */
        public Map<String, RuleConfig> ruleConfigByScheme;
        public String schemeId;
        public List<SchemeChange> schemeChanges;
        public List<PromotionGrant> externalPromotions;
        /**
         * 优惠规则配置（如 START_FREE）
         */
        public List<PromotionRuleConfig> promotionRuleConfigs;
        public TimeRoundingMode timeRoundingMode;
        public EquivalentAmountSpec equivalentAmountSpec;

        public static ScenarioBuilder builder() {
            return new ScenarioBuilder();
        }
    }

    /**
     * Scenario 构建器（手写，避免引入 Lombok @Builder 依赖歧义）
     */
    public static class ScenarioBuilder {
        private final Scenario s = new Scenario();

        public ScenarioBuilder name(String v) {
            s.name = v;
            return this;
        }

        public ScenarioBuilder beginTime(LocalDateTime v) {
            s.beginTime = v;
            return this;
        }

        public ScenarioBuilder endTime(LocalDateTime v) {
            s.endTime = v;
            return this;
        }

        public ScenarioBuilder calculationMode(BConstants.CalculationMode v) {
            s.calculationMode = v;
            return this;
        }

        public ScenarioBuilder ruleConfig(RuleConfig v) {
            s.ruleConfig = v;
            return this;
        }

        public ScenarioBuilder schemeId(String v) {
            s.schemeId = v;
            return this;
        }

        public ScenarioBuilder schemeChanges(List<SchemeChange> v) {
            s.schemeChanges = v;
            return this;
        }

        public ScenarioBuilder externalPromotions(List<PromotionGrant> v) {
            s.externalPromotions = v;
            return this;
        }

        public ScenarioBuilder promotionRuleConfigs(List<PromotionRuleConfig> v) {
            s.promotionRuleConfigs = v;
            return this;
        }

        public ScenarioBuilder timeRoundingMode(TimeRoundingMode v) {
            s.timeRoundingMode = v;
            return this;
        }

        public ScenarioBuilder equivalentAmountSpec(EquivalentAmountSpec v) {
            s.equivalentAmountSpec = v;
            return this;
        }

        public Scenario build() {
            if (s.calculationMode == null) {
                s.calculationMode = BConstants.CalculationMode.CONTINUOUS;
            }
            if (s.name == null) s.name = "未命名场景";
            return s;
        }
    }
}
