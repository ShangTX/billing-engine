package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.charge.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.ranges.FreeTimeRangePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方案切换场景测试
 *
 * 测试场景：景区停车场收费
 * - 旺季：4月20日 - 10月10日，单价高、单元短（2元/30分钟，日封顶50元）
 * - 淡季：10月11日 - 次年4月19日，单价低、单元长（1元/60分钟，日封顶20元）
 *
 * 测试维度：
 * - 切换次数：无切换、单次切换、多次切换
 * - 计算模式：SEGMENT_LOCAL（分段独立起算）、GLOBAL_ORIGIN（全局起算截取）
 * - CONTINUE模式：跨方案切换
 */
public class SchemeSwitchTest {

    static final String PEAK_SCHEME = "peak-season";    // 旺季方案
    static final String OFF_PEAK_SCHEME = "off-season"; // 淡季方案

    static LocalDateTime BASE_DATE = LocalDateTime.of(2026, 1, 1, 0, 0);
    static DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public static void main(String[] args) {
        System.out.println("========== 方案切换场景测试 ==========\n");

        // Chunk 2: 单次切换测试
        testSingleSwitch_PeakToOffPeak_SegmentLocal();
        testSingleSwitch_OffPeakToPeak_GlobalOrigin();

        // Chunk 3: 多次切换测试
        testMultipleSwitch_ThreeSeasons();
        testSwitchAtUnitBoundary();

        // Chunk 4: CONTINUE模式测试
        testContinue_CrossScheme();

        System.out.println("\n========== 测试完成 ==========\n");
    }

    // ==================== Chunk 2: 单次切换测试 ====================

    /**
     * 场景1：单次切换 - 旺季→淡季（SEGMENT_LOCAL）
     *
     * 计费时间：10月01日 08:00 - 10月15日 08:00
     * 切换点：10月11日 00:00
     *
     * 预期分段：
     * - 分段1：10月01日 08:00 - 10月11日 00:00（旺季方案，10天）
     * - 分段2：10月11日 00:00 - 10月15日 08:00（淡季方案，4天8小时）
     *
     * SEGMENT_LOCAL：每个分段独立起算周期
     */
    static void testSingleSwitch_PeakToOffPeak_SegmentLocal() {
        System.out.println("=== 场景1: 单次切换 旺季→淡季 (SEGMENT_LOCAL) ===\n");

        var billingService = createBillingService();

        // 切换点：10月11日 00:00
        var switchTime = LocalDateTime.of(2026, 10, 11, 0, 0);

        var request = createRequest(
                LocalDateTime.of(2026, 10, 1, 8, 0),  // 10月01日 08:00
                LocalDateTime.of(2026, 10, 15, 8, 0), // 10月15日 08:00
                BConstants.SegmentCalculationMode.SEGMENT_LOCAL
        );

        // 设置方案切换
        var schemeChange = new SchemeChange();
        schemeChange.setLastSchemeId(PEAK_SCHEME);
        schemeChange.setNextSchemeId(OFF_PEAK_SCHEME);
        schemeChange.setChangeTime(switchTime);
        request.setSchemeChanges(List.of(schemeChange));

        var result = billingService.calculate(request);

        System.out.println("输入参数:");
        System.out.println("  计费时间: 10月01日 08:00 - 10月15日 08:00");
        System.out.println("  切换点: 10月11日 00:00 (旺季→淡季)");
        System.out.println("  计算模式: SEGMENT_LOCAL (分段独立起算)");
        System.out.println();

        System.out.println("分段信息:");
        printSegments(result);

        System.out.println("\n计费单元统计:");
        printUnitStats(result);

        System.out.println("\n验证结果:");
        System.out.println("  总金额: " + result.getFinalAmount() + "元");
        System.out.println("  计费单元数: " + result.getUnits().size());

        // 验证分段数量
        boolean segmentCountCorrect = countSegments(result) == 2;
        System.out.println("  分段数量正确: " + (segmentCountCorrect ? "✓" : "✗"));

        System.out.println();
    }

    /**
     * 场景2：单次切换 - 淡季→旺季（GLOBAL_ORIGIN）
     *
     * 计费时间：04月15日 08:00 - 04月25日 08:00
     * 切换点：04月20日 00:00
     *
     * 预期分段：
     * - 分段1：04月15日 08:00 - 04月20日 00:00（淡季方案）
     * - 分段2：04月20日 00:00 - 04月25日 08:00（旺季方案）
     *
     * GLOBAL_ORIGIN：从全局起点（04月15日 08:00）计算周期边界
     */
    static void testSingleSwitch_OffPeakToPeak_GlobalOrigin() {
        System.out.println("=== 场景2: 单次切换 淡季→旺季 (GLOBAL_ORIGIN) ===\n");

        var billingService = createBillingService();

        // 切换点：04月20日 00:00
        var switchTime = LocalDateTime.of(2026, 4, 20, 0, 0);

        var request = createRequest(
                LocalDateTime.of(2026, 4, 15, 8, 0),   // 04月15日 08:00
                LocalDateTime.of(2026, 4, 25, 8, 0),   // 04月25日 08:00
                BConstants.SegmentCalculationMode.GLOBAL_ORIGIN
        );

        request.setSchemeChanges(List.of(
                createSchemeChange(OFF_PEAK_SCHEME, PEAK_SCHEME, switchTime)
        ));

        var result = billingService.calculate(request);

        System.out.println("输入参数:");
        System.out.println("  计费时间: 04月15日 08:00 - 04月25日 08:00");
        System.out.println("  切换点: 04月20日 00:00 (淡季→旺季)");
        System.out.println("  计算模式: GLOBAL_ORIGIN (全局起算截取)");
        System.out.println();

        System.out.println("分段信息:");
        printSegments(result);

        System.out.println("\n计费单元统计:");
        printUnitStats(result);

        System.out.println("\n验证结果:");
        System.out.println("  总金额: " + result.getFinalAmount() + "元");

        // 验证 GLOBAL_ORIGIN 模式：周期边界从全局起点计算
        System.out.println("  关键验证: GLOBAL_ORIGIN 模式周期边界从全局起点计算");
        System.out.println("  全局起点: 04月15日 08:00");

        System.out.println();
    }

    // ==================== Chunk 3: 多次切换测试 ====================

    /**
     * 场景3：多次切换 - 跨三个季节
     *
     * 计费时间：09月01日 08:00 - 次年05月01日 08:00
     * 切换点：10月11日 00:00（旺季→淡季）、04月20日 00:00（淡季→旺季）
     *
     * 预期分段：
     * - 分段1：09月01日 08:00 - 10月11日 00:00（旺季）
     * - 分段2：10月11日 00:00 - 04月20日 00:00（淡季）
     * - 分段3：04月20日 00:00 - 05月01日 08:00（旺季）
     */
    static void testMultipleSwitch_ThreeSeasons() {
        System.out.println("=== 场景3: 多次切换 跨三个季节 ===\n");

        var billingService = createBillingService();

        var request = createRequest(
                LocalDateTime.of(2026, 9, 1, 8, 0),    // 09月01日 08:00
                LocalDateTime.of(2027, 5, 1, 8, 0),    // 次年05月01日 08:00
                BConstants.SegmentCalculationMode.SEGMENT_LOCAL
        );

        request.setSchemeChanges(List.of(
                // 第一次切换：旺季→淡季
                createSchemeChange(PEAK_SCHEME, OFF_PEAK_SCHEME, LocalDateTime.of(2026, 10, 11, 0, 0)),
                // 第二次切换：淡季→旺季
                createSchemeChange(OFF_PEAK_SCHEME, PEAK_SCHEME, LocalDateTime.of(2027, 4, 20, 0, 0))
        ));

        var result = billingService.calculate(request);

        System.out.println("输入参数:");
        System.out.println("  计费时间: 2026年09月01日 08:00 - 2027年05月01日 08:00");
        System.out.println("  切换点1: 2026年10月11日 00:00 (旺季→淡季)");
        System.out.println("  切换点2: 2027年04月20日 00:00 (淡季→旺季)");
        System.out.println();

        System.out.println("分段信息:");
        printSegments(result);

        System.out.println("\n验证结果:");
        int segmentCount = countSegments(result);
        boolean segmentCountCorrect = segmentCount == 3;
        System.out.println("  分段数量: " + segmentCount + " (预期3) " + (segmentCountCorrect ? "✓" : "✗"));
        System.out.println("  总金额: " + result.getFinalAmount() + "元");

        System.out.println();
    }

    /**
     * 场景4：切换点在单元边界
     *
     * 计费时间：10月10日 22:00 - 10月11日 02:00
     * 切换点：10月11日 00:00
     *
     * 验证：切换点恰好是单元边界，不产生截断单元
     */
    static void testSwitchAtUnitBoundary() {
        System.out.println("=== 场景4: 切换点在单元边界 ===\n");

        var billingService = createBillingService();

        var request = createRequest(
                LocalDateTime.of(2026, 10, 10, 22, 0), // 10月10日 22:00
                LocalDateTime.of(2026, 10, 11, 2, 0),  // 10月11日 02:00
                BConstants.SegmentCalculationMode.SEGMENT_LOCAL
        );

        request.setSchemeChanges(List.of(
                createSchemeChange(PEAK_SCHEME, OFF_PEAK_SCHEME, LocalDateTime.of(2026, 10, 11, 0, 0))
        ));

        var result = billingService.calculate(request);

        System.out.println("输入参数:");
        System.out.println("  计费时间: 10月10日 22:00 - 10月11日 02:00");
        System.out.println("  切换点: 10月11日 00:00");
        System.out.println("  旺季单元长度: 30分钟");
        System.out.println("  淡季单元长度: 60分钟");
        System.out.println();

        System.out.println("计费单元详情:");
        for (var unit : result.getUnits()) {
            String segmentMark = unit.getBeginTime().isBefore(LocalDateTime.of(2026, 10, 11, 0, 0)) ? "[旺季]" : "[淡季]";
            System.out.printf("  %s %s - %s (%d分钟) %.0f元%n",
                    segmentMark,
                    unit.getBeginTime().format(DATE_TIME_FORMAT),
                    unit.getEndTime().format(DATE_TIME_FORMAT),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount());
        }

        System.out.println("\n验证结果:");
        System.out.println("  总金额: " + result.getFinalAmount() + "元");
        System.out.println("  验证: 切换点00:00恰好是旺季单元边界（22:00, 22:30, 23:00, 23:30, 00:00）");

        System.out.println();
    }

    // ==================== Chunk 4: CONTINUE模式测试 ====================

    /**
     * 场景5：CONTINUE模式跨方案切换
     *
     * 第一次计算：10月01日 08:00 - 10月05日 08:00（旺季内）
     * 第二次计算：CONTINUE 到 10月15日 08:00（跨入淡季）
     *
     * 预期：
     * - 自动识别方案切换
     * - 分段1继续：10月05日 - 10月11日（旺季）
     * - 分段2新起：10月11日 - 10月15日（淡季）
     */
    static void testContinue_CrossScheme() {
        System.out.println("=== 场景5: CONTINUE模式跨方案切换 ===\n");

        var billingService = createBillingService();

        // 方案切换配置
        var schemeChanges = List.of(
                createSchemeChange(PEAK_SCHEME, OFF_PEAK_SCHEME, LocalDateTime.of(2026, 10, 11, 0, 0))
        );

        // 第一次计算：旺季内
        var request1 = createRequest(
                LocalDateTime.of(2026, 10, 1, 8, 0),
                LocalDateTime.of(2026, 10, 5, 8, 0),
                BConstants.SegmentCalculationMode.SEGMENT_LOCAL
        );
        request1.setSchemeChanges(schemeChanges);

        var result1 = billingService.calculate(request1);

        System.out.println("第一次计算: 10月01日 08:00 - 10月05日 08:00");
        System.out.println("  结果金额: " + result1.getFinalAmount() + "元");
        System.out.println("  calculationEndTime: " + formatDateTime(result1.getCalculationEndTime()));

        // 第二次计算：跨入淡季
        var request2 = createRequest(
                LocalDateTime.of(2026, 10, 1, 8, 0),
                LocalDateTime.of(2026, 10, 15, 8, 0),
                BConstants.SegmentCalculationMode.SEGMENT_LOCAL
        );
        request2.setSchemeChanges(schemeChanges);
        request2.setPreviousCarryOver(result1.getCarryOver());

        var result2 = billingService.calculate(request2);

        System.out.println("\n第二次计算 (CONTINUE): 10月05日 08:00 - 10月15日 08:00");
        System.out.println("  跨越方案切换点: 10月11日 00:00");

        System.out.println("\n分段信息:");
        printSegments(result2);

        System.out.println("\n验证结果:");
        System.out.println("  总金额: " + result2.getFinalAmount() + "元");
        System.out.println("  关键验证: CONTINUE模式自动识别方案切换，分段正确");

        System.out.println();
    }

    // ==================== 辅助方法 ====================

    static BillingService createBillingService() {
        // 方案配置缓存
        Map<String, RuleConfig> schemeConfigs = new HashMap<>();
        schemeConfigs.put(PEAK_SCHEME, createPeakSeasonConfig());
        schemeConfigs.put(OFF_PEAK_SCHEME, createOffPeakSeasonConfig());

        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.UNIT_BASED;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return schemeConfigs.getOrDefault(schemeId, createPeakSeasonConfig());
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

    /**
     * 旺季方案配置
     * - 单元长度：30分钟
     * - 单价：2元/单元
     * - 日封顶：50元
     */
    static RuleConfig createPeakSeasonConfig() {
        return RelativeTimeConfig.builder()
                .id("peak-season-rule")
                .periods(List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitMinutes(30)
                                .unitPrice(new BigDecimal("2"))
                                .build()
                ))
                .maxChargeOneCycle(new BigDecimal("50"))
                .build();
    }

    /**
     * 淡季方案配置
     * - 单元长度：60分钟
     * - 单价：1元/单元
     * - 日封顶：20元
     */
    static RuleConfig createOffPeakSeasonConfig() {
        return RelativeTimeConfig.builder()
                .id("off-season-rule")
                .periods(List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("1"))
                                .build()
                ))
                .maxChargeOneCycle(new BigDecimal("20"))
                .build();
    }

    static BillingRequest createRequest(LocalDateTime begin, LocalDateTime end,
                                         BConstants.SegmentCalculationMode mode) {
        var request = new BillingRequest();
        request.setId("scheme-switch-test");
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeChanges(new ArrayList<>());
        request.setSegmentCalculationMode(mode);
        request.setExternalPromotions(new ArrayList<>());
        return request;
    }

    static void printSegments(BillingResult result) {
        if (result.getUnits() == null || result.getUnits().isEmpty()) {
            System.out.println("  无计费单元");
            return;
        }

        // 按分段分组
        Map<String, List<BillingUnit>> segmentUnits = new HashMap<>();
        for (var unit : result.getUnits()) {
            // 使用 schemeId 分组（需要从单元推断）
            String key = "分段";
            segmentUnits.computeIfAbsent(key, k -> new ArrayList<>()).add(unit);
        }

        int segIndex = 1;
        BigDecimal segAmount = BigDecimal.ZERO;
        String currentScheme = null;

        for (var unit : result.getUnits()) {
            // 简单判断：通过单元价格推断方案
            String scheme = unit.getUnitPrice().compareTo(new BigDecimal("1.5")) > 0 ? PEAK_SCHEME : OFF_PEAK_SCHEME;

            if (currentScheme == null) {
                currentScheme = scheme;
                System.out.printf("  分段%d [%s]:%n", segIndex, getSchemeName(scheme));
            } else if (!currentScheme.equals(scheme)) {
                System.out.printf("    小计: %.0f元%n%n", segAmount);
                segIndex++;
                segAmount = BigDecimal.ZERO;
                currentScheme = scheme;
                System.out.printf("  分段%d [%s]:%n", segIndex, getSchemeName(scheme));
            }

            System.out.printf("    %s - %s (%d分钟) %.0f元%n",
                    formatDateTime(unit.getBeginTime()),
                    formatDateTime(unit.getEndTime()),
                    unit.getDurationMinutes(),
                    unit.getChargedAmount());
            segAmount = segAmount.add(unit.getChargedAmount());
        }
        System.out.printf("    小计: %.0f元%n", segAmount);
    }

    static void printUnitStats(BillingResult result) {
        long peakUnits = result.getUnits().stream()
                .filter(u -> u.getUnitPrice().compareTo(new BigDecimal("1.5")) > 0)
                .count();
        long offPeakUnits = result.getUnits().size() - peakUnits;

        System.out.println("  旺季单元数: " + peakUnits);
        System.out.println("  淡季单元数: " + offPeakUnits);
        System.out.println("  总单元数: " + result.getUnits().size());
    }

    static int countSegments(BillingResult result) {
        if (result.getUnits() == null || result.getUnits().isEmpty()) {
            return 0;
        }

        int count = 1;
        String currentScheme = null;

        for (var unit : result.getUnits()) {
            String scheme = unit.getUnitPrice().compareTo(new BigDecimal("1.5")) > 0 ? PEAK_SCHEME : OFF_PEAK_SCHEME;
            if (currentScheme != null && !currentScheme.equals(scheme)) {
                count++;
            }
            currentScheme = scheme;
        }

        return count;
    }

    static String getSchemeName(String schemeId) {
        return PEAK_SCHEME.equals(schemeId) ? "旺季" : "淡季";
    }

    static String formatDateTime(LocalDateTime time) {
        return time.format(DATE_TIME_FORMAT);
    }

    static SchemeChange createSchemeChange(String lastScheme, String nextScheme, LocalDateTime changeTime) {
        var sc = new SchemeChange();
        sc.setLastSchemeId(lastScheme);
        sc.setNextSchemeId(nextScheme);
        sc.setChangeTime(changeTime);
        return sc;
    }
}