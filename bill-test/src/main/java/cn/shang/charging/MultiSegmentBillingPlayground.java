package cn.shang.charging;

import cn.shang.charging.billing.*;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.util.JacksonUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 多分段计费测试工具 —— 方便配置和测试多分段计费场景。
 * <p>
 * 与 {@link BillingPlaygroundTest} 的区别：
 * <ul>
 *   <li>{@link BillingPlaygroundTest}：单分段场景，完整配置一个计费场景</li>
 *   <li>本类：多分段场景，支持灵活配置多个分段，每个分段可以有不同的规则和优惠</li>
 * </ul>
 * <p>
 * 用法：
 * <ol>
 *   <li>创建 {@link MultiSegmentScenario}，添加多个 {@link SegmentConfig}</li>
 *   <li>运行 {@link #run(MultiSegmentScenario)}，查看完整计费明细</li>
 * </ol>
 * <p>
 * 示例：
 * <pre>{@code
 * MultiSegmentScenario scenario = MultiSegmentScenario.builder()
 *     .name("多费率测试")
 *     .calculationMode(BConstants.CalculationMode.CONTINUOUS)
 *     .addSegment(SegmentConfig.builder()
 *         .schemeId("scheme-a")
 *         .beginTime(LocalDateTime.of(2026, 7, 7, 8, 0))
 *         .endTime(LocalDateTime.of(2026, 7, 7, 12, 0))
 *         .ruleConfig(new DayNightConfig()...)
 *         .externalPromotions(List.of(...))
 *         .build())
 *     .addSegment(SegmentConfig.builder()
 *         .schemeId("scheme-b")
 *         .beginTime(LocalDateTime.of(2026, 7, 7, 12, 0))
 *         .endTime(LocalDateTime.of(2026, 7, 7, 18, 0))
 *         .ruleConfig(new DayNightConfig()...)
 *         .build())
 *     .build();
 *
 * run(scenario);
 * }</pre>
 */
public class MultiSegmentBillingPlayground {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static void main(String[] args) {
        // Windows 命令行默认 GBK，强制 stdout 用 UTF-8
        try {
            System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException ignored) {
        }

        System.out.println("=".repeat(72));
        System.out.println("  多分段计费测试工具 — 灵活配置多个分段");
        System.out.println("=".repeat(72));

        // ▼▼▼ 在这里选择/修改要运行的场景 ▼▼▼
        run(scenario1_twoSegments());
//        run(scenario2_threeSegments());
//        run(scenario3_withPromotions());
        // ▲▲▲ 在这里选择/修改要运行的场景 ▲▲▲
    }

    // ==================== 预设场景 ====================

    /**
     * 场景1：两段计费 - 不同费率。
     * <p>
     * 上午 08:00-12:00 使用 scheme-a（2元/时），下午 12:00-18:00 使用 scheme-b（3元/时）。
     */
    static MultiSegmentScenario scenario1_twoSegments() {
        return MultiSegmentScenario.builder()
                .name("两段计费 - 不同费率")
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .addSegment(SegmentConfig.builder()
                        .schemeId("scheme-a")
                        .beginTime(LocalDateTime.of(2026, 7, 7, 8, 0))
                        .endTime(LocalDateTime.of(2026, 7, 7, 12, 0))
                        .ruleConfig(new cn.shang.charging.charge.rules.daynight.DayNightConfig()
                                .setId("dn-a")
                                .setDayBeginMinute(8 * 60)
                                .setDayEndMinute(20 * 60)
                                .setDayUnitPrice(new BigDecimal("2"))
                                .setNightUnitPrice(new BigDecimal("1"))
                                .setUnitMinutes(60)
                                .setMaxChargeOneDay(new BigDecimal("50"))
                                .setBlockWeight(new BigDecimal("0.5")))
                        .build())
                .addSegment(SegmentConfig.builder()
                        .schemeId("scheme-b")
                        .beginTime(LocalDateTime.of(2026, 7, 7, 12, 0))
                        .endTime(LocalDateTime.of(2026, 7, 7, 18, 0))
                        .ruleConfig(new cn.shang.charging.charge.rules.daynight.DayNightConfig()
                                .setId("dn-b")
                                .setDayBeginMinute(8 * 60)
                                .setDayEndMinute(20 * 60)
                                .setDayUnitPrice(new BigDecimal("3"))
                                .setNightUnitPrice(new BigDecimal("1.5"))
                                .setUnitMinutes(60)
                                .setMaxChargeOneDay(new BigDecimal("60"))
                                .setBlockWeight(new BigDecimal("0.5")))
                        .build())
                .build();
    }

    /**
     * 场景2：三段计费 - 旺季/淡季/旺季切换。
     */
    static MultiSegmentScenario scenario2_threeSegments() {
        return MultiSegmentScenario.builder()
                .name("三段计费 - 旺季/淡季/旺季")
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .addSegment(SegmentConfig.builder()
                        .schemeId("peak-1")
                        .beginTime(LocalDateTime.of(2026, 9, 1, 8, 0))
                        .endTime(LocalDateTime.of(2026, 10, 11, 0, 0))
                        .ruleConfig(cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig.builder()
                                .id("peak-rule")
                                .periods(List.of(
                                        cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod.builder()
                                                .beginMinute(0)
                                                .endMinute(1440)
                                                .unitMinutes(30)
                                                .unitPrice(new BigDecimal("2"))
                                                .build()))
                                .maxChargeOneCycle(new BigDecimal("50"))
                                .build())
                        .build())
                .addSegment(SegmentConfig.builder()
                        .schemeId("off-peak")
                        .beginTime(LocalDateTime.of(2026, 10, 11, 0, 0))
                        .endTime(LocalDateTime.of(2027, 4, 20, 0, 0))
                        .ruleConfig(cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig.builder()
                                .id("off-peak-rule")
                                .periods(List.of(
                                        cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod.builder()
                                                .beginMinute(0)
                                                .endMinute(1440)
                                                .unitMinutes(60)
                                                .unitPrice(new BigDecimal("1"))
                                                .build()))
                                .maxChargeOneCycle(new BigDecimal("20"))
                                .build())
                        .build())
                .addSegment(SegmentConfig.builder()
                        .schemeId("peak-2")
                        .beginTime(LocalDateTime.of(2027, 4, 20, 0, 0))
                        .endTime(LocalDateTime.of(2027, 5, 1, 8, 0))
                        .ruleConfig(cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig.builder()
                                .id("peak-rule")
                                .periods(List.of(
                                        cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod.builder()
                                                .beginMinute(0)
                                                .endMinute(1440)
                                                .unitMinutes(30)
                                                .unitPrice(new BigDecimal("2"))
                                                .build()))
                                .maxChargeOneCycle(new BigDecimal("50"))
                                .build())
                        .build())
                .build();
    }

    /**
     * 场景3：带优惠的多段计费。
     */
    static MultiSegmentScenario scenario3_withPromotions() {
        return MultiSegmentScenario.builder()
                .name("两段计费 + 优惠")
                .calculationMode(BConstants.CalculationMode.CONTINUOUS)
                .addSegment(SegmentConfig.builder()
                        .schemeId("segment-1")
                        .beginTime(LocalDateTime.of(2026, 7, 7, 8, 0))
                        .endTime(LocalDateTime.of(2026, 7, 7, 12, 0))
                        .ruleConfig(new cn.shang.charging.charge.rules.daynight.DayNightConfig()
                                .setId("dn-1")
                                .setDayBeginMinute(8 * 60)
                                .setDayEndMinute(20 * 60)
                                .setDayUnitPrice(new BigDecimal("2"))
                                .setNightUnitPrice(new BigDecimal("1"))
                                .setUnitMinutes(60)
                                .setMaxChargeOneDay(new BigDecimal("50"))
                                .setBlockWeight(new BigDecimal("0.5")))
                        .externalPromotions(List.of(
                                PromotionGrant.builder()
                                        .id("free-30min")
                                        .type(BConstants.PromotionType.FREE_MINUTES)
                                        .source(BConstants.PromotionSource.COUPON)
                                        .freeMinutes(30)
                                        .priority(1)
                                        .build()))
                        .build())
                .addSegment(SegmentConfig.builder()
                        .schemeId("segment-2")
                        .beginTime(LocalDateTime.of(2026, 7, 7, 12, 0))
                        .endTime(LocalDateTime.of(2026, 7, 7, 18, 0))
                        .ruleConfig(new cn.shang.charging.charge.rules.daynight.DayNightConfig()
                                .setId("dn-2")
                                .setDayBeginMinute(8 * 60)
                                .setDayEndMinute(20 * 60)
                                .setDayUnitPrice(new BigDecimal("3"))
                                .setNightUnitPrice(new BigDecimal("1.5"))
                                .setUnitMinutes(60)
                                .setMaxChargeOneDay(new BigDecimal("60"))
                                .setBlockWeight(new BigDecimal("0.5")))
                        .externalPromotions(List.of(
                                PromotionGrant.builder()
                                        .id("free-range-lunch")
                                        .type(BConstants.PromotionType.FREE_RANGE)
                                        .source(BConstants.PromotionSource.COUPON)
                                        .priority(1)
                                        .beginTime(LocalDateTime.of(2026, 7, 7, 12, 0))
                                        .endTime(LocalDateTime.of(2026, 7, 7, 13, 0))
                                        .build()))
                        .build())
                .build();
    }

    // ==================== 引擎执行与结果打印 ====================

    /**
     * 执行多分段计费场景并打印结果。
     */
    static void run(MultiSegmentScenario scenario) {
        System.out.println();
        System.out.println("┌" + "─".repeat(70) + "┐");
        System.out.printf("│ 场景: %-66s│%n", scenario.name);
        System.out.println("└" + "─".repeat(70) + "┘");

        // 1. 构建 BillingRequest（自动生成 schemeChanges）
        BillingRequest request = buildRequest(scenario);

        // 2. 构建配置解析器
        BillingConfigResolver resolver = buildResolver(scenario);

        // 3. 组装引擎
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
        promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        promotionRuleRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        PromotionEngine promotionEngine = new PromotionEngine(
                resolver, new FreeTimeRangeMerger(), promotionRuleRegistry);

        BillingService billingService = new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());

        // 4. 打印输入
        printInput(scenario, request);

        // 5. 执行计费
        BillingResult result = billingService.calculate(request);

        // 6. 打印结果
        printResult(scenario, request, result);
    }

    /**
     * 从多分段配置构建 BillingRequest。
     * 自动生成 schemeChanges（方案切换时间点）。
     */
    static BillingRequest buildRequest(MultiSegmentScenario scenario) {
        if (scenario.segments == null || scenario.segments.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个分段配置");
        }

        BillingRequest request = new BillingRequest();
        request.setId("multi-segment-" + System.currentTimeMillis());

        // 第一个分段的时间范围作为整个请求的时间范围
        request.setBeginTime(scenario.segments.get(0).beginTime);
        request.setEndTime(scenario.segments.get(scenario.segments.size() - 1).endTime);

        // 第一个方案的 schemeId
        request.setSchemeId(scenario.segments.get(0).schemeId);

        // 自动生成 schemeChanges
        List<SchemeChange> schemeChanges = new ArrayList<>();
        for (int i = 1; i < scenario.segments.size(); i++) {
            SegmentConfig prev = scenario.segments.get(i - 1);
            SegmentConfig curr = scenario.segments.get(i);

            SchemeChange change = new SchemeChange();
            change.setLastSchemeId(prev.schemeId);
            change.setNextSchemeId(curr.schemeId);
            change.setChangeTime(curr.beginTime);
            schemeChanges.add(change);
        }
        request.setSchemeChanges(schemeChanges);

        // 合并所有分段的优惠（去重）
        List<PromotionGrant> allPromotions = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();
        for (SegmentConfig seg : scenario.segments) {
            if (seg.externalPromotions != null) {
                for (PromotionGrant promo : seg.externalPromotions) {
                    if (!addedIds.contains(promo.getId())) {
                        allPromotions.add(promo);
                        addedIds.add(promo.getId());
                    }
                }
            }
        }
        request.setExternalPromotions(allPromotions.isEmpty() ? List.of() : allPromotions);

        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

        return request;
    }

    /**
     * 构建配置解析器，根据 schemeId 返回对应分段的规则和优惠。
     */
    static BillingConfigResolver buildResolver(MultiSegmentScenario scenario) {
        // 构建 schemeId → 规则配置 的映射
        Map<String, RuleConfig> ruleConfigMap = new HashMap<>();
        Map<String, List<PromotionRuleConfig>> promotionConfigMap = new HashMap<>();

        for (SegmentConfig seg : scenario.segments) {
            ruleConfigMap.put(seg.schemeId, seg.ruleConfig);
            if (seg.promotionRuleConfigs != null) {
                promotionConfigMap.put(seg.schemeId, seg.promotionRuleConfigs);
            }
        }

        return new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return scenario.calculationMode != null ? scenario.calculationMode : BConstants.CalculationMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return ruleConfigMap.getOrDefault(schemeId, ruleConfigMap.values().iterator().next());
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return promotionConfigMap.getOrDefault(schemeId, List.of());
            }
        };
    }

    static void printInput(MultiSegmentScenario scenario, BillingRequest request) {
        System.out.println("\n【输入参数】");
        System.out.println("  分段数量: " + scenario.segments.size());
        System.out.println("  计算模式: " + scenario.calculationMode);

        for (int i = 0; i < scenario.segments.size(); i++) {
            SegmentConfig seg = scenario.segments.get(i);
            System.out.printf("  分段%d [%s]: %s — %s (%s)%n",
                    i + 1,
                    seg.schemeId,
                    seg.beginTime.format(FMT),
                    seg.endTime.format(FMT),
                    seg.ruleConfig.getType());
            if (seg.externalPromotions != null && !seg.externalPromotions.isEmpty()) {
                System.out.println("    外部优惠: " + seg.externalPromotions.size() + " 个");
                for (PromotionGrant p : seg.externalPromotions) {
                    System.out.println("      - " + p.getId() + " [" + p.getType() + "]");
                }
            }
        }
    }

    static void printResult(MultiSegmentScenario scenario, BillingRequest request, BillingResult result) {
        System.out.println("\n【计费结果】");
        System.out.println("  ★ 最终应收金额: " + result.getFinalAmount() + " 元");

        // 计费单元明细
        if (result.getUnits() != null && !result.getUnits().isEmpty()) {
            System.out.println("\n【计费单元明细】共 " + result.getUnits().size() + " 个");

            // 按分段分组显示
            int segIndex = 0;
            String currentScheme = null;
            BigDecimal segAmount = BigDecimal.ZERO;

            System.out.printf("  %-3s %-16s %-16s %6s %8s %10s %10s %s%n",
                    "#", "开始", "结束", "分钟", "单价", "原始", "实收", "标记");

            for (int i = 0; i < result.getUnits().size(); i++) {
                BillingUnit u = result.getUnits().get(i);

                // 判断所属分段（简化逻辑）
                String scheme = findSchemeByTime(scenario, u.getBeginTime());
                if (currentScheme == null) {
                    currentScheme = scheme;
                    segIndex++;
                    System.out.printf("  [分段%d - %s]%n", segIndex, scheme);
                } else if (!currentScheme.equals(scheme)) {
                    System.out.printf("      分段小计: %.2f元%n%n", segAmount);
                    segIndex++;
                    segAmount = BigDecimal.ZERO;
                    currentScheme = scheme;
                    System.out.printf("  [分段%d - %s]%n", segIndex, scheme);
                }

                String mark = "";
                if (u.isFree()) mark += " [免费:" + u.getFreePromotionId() + "]";
                if (Boolean.TRUE.equals(u.getIsTruncated())) mark += " [截断]";
                if (u.isCompact()) mark += " [compact×" + u.getCount() + "]";

                System.out.printf("  %-3d %-16s %-16s %6d %8s %10s %10s %s%n",
                        i + 1,
                        u.getBeginTime().format(FMT),
                        u.getEndTime().format(FMT),
                        u.getDurationMinutes(),
                        u.getUnitPrice(),
                        u.getOriginalAmount(),
                        u.getChargedAmount(),
                        mark);

                segAmount = segAmount.add(u.getChargedAmount());
            }
            System.out.printf("      分段小计: %.2f元%n", segAmount);
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

        // 完整 JSON
        System.out.println("\n【完整结果 JSON】");
        System.out.println(JacksonUtils.toJsonString(result));

        System.out.println("\n" + "─".repeat(72));
    }

    /**
     * 根据时间找到对应的 schemeId。
     */
    static String findSchemeByTime(MultiSegmentScenario scenario, LocalDateTime time) {
        for (SegmentConfig seg : scenario.segments) {
            if (!time.isBefore(seg.beginTime) && time.isBefore(seg.endTime)) {
                return seg.schemeId;
            }
        }
        return scenario.segments.get(0).schemeId;
    }

    // ==================== 配置对象 ====================

    /**
     * 多分段计费场景配置。
     */
    public static class MultiSegmentScenario {
        public String name;
        public BConstants.CalculationMode calculationMode;
        public List<SegmentConfig> segments = new ArrayList<>();

        public static MultiSegmentScenarioBuilder builder() {
            return new MultiSegmentScenarioBuilder();
        }
    }

    /**
     * 单个分段配置。
     */
    public static class SegmentConfig {
        public String schemeId;
        public LocalDateTime beginTime;
        public LocalDateTime endTime;
        public RuleConfig ruleConfig;
        public List<PromotionGrant> externalPromotions = new ArrayList<>();
        public List<PromotionRuleConfig> promotionRuleConfigs = new ArrayList<>();

        public static SegmentConfigBuilder builder() {
            return new SegmentConfigBuilder();
        }
    }

    // ==================== 构建器 ====================

    public static class MultiSegmentScenarioBuilder {
        private final MultiSegmentScenario s = new MultiSegmentScenario();

        public MultiSegmentScenarioBuilder name(String v) { s.name = v; return this; }
        public MultiSegmentScenarioBuilder calculationMode(BConstants.CalculationMode v) { s.calculationMode = v; return this; }

        /**
         * 添加一个分段配置。
         */
        public MultiSegmentScenarioBuilder addSegment(SegmentConfig segment) {
            s.segments.add(segment);
            return this;
        }

        public MultiSegmentScenario build() {
            if (s.segments.isEmpty()) {
                throw new IllegalArgumentException("至少需要一个分段配置");
            }
            if (s.name == null) s.name = "未命名多分段场景";
            if (s.calculationMode == null) s.calculationMode = BConstants.CalculationMode.CONTINUOUS;
            return s;
        }
    }

    public static class SegmentConfigBuilder {
        private final SegmentConfig s = new SegmentConfig();

        public SegmentConfigBuilder schemeId(String v) { s.schemeId = v; return this; }
        public SegmentConfigBuilder beginTime(LocalDateTime v) { s.beginTime = v; return this; }
        public SegmentConfigBuilder endTime(LocalDateTime v) { s.endTime = v; return this; }
        public SegmentConfigBuilder ruleConfig(RuleConfig v) { s.ruleConfig = v; return this; }
        public SegmentConfigBuilder externalPromotions(List<PromotionGrant> v) {
            if (v != null) s.externalPromotions = v;
            return this;
        }
        public SegmentConfigBuilder promotionRuleConfigs(List<PromotionRuleConfig> v) {
            if (v != null) s.promotionRuleConfigs = v;
            return this;
        }

        public SegmentConfig build() {
            if (s.schemeId == null) throw new IllegalArgumentException("schemeId 不能为空");
            if (s.beginTime == null) throw new IllegalArgumentException("beginTime 不能为空");
            if (s.endTime == null) throw new IllegalArgumentException("endTime 不能为空");
            if (s.ruleConfig == null) throw new IllegalArgumentException("ruleConfig 不能为空");
            return s;
        }
    }
}