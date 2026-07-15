package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.PromotionEquivalentCalculator;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.EquivalentAmountSpec;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.billing.pojo.SchemeChange;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TODO-20260706-003 输出优化 + 等效金额增强测试。
 * 覆盖：PromotionUsage.source 透传、EquivalentAmountSpec 按需/细致指定、
 * totalEquivalentAmount、等效金额多段+外部优惠。
 */
class OutputOptimizationAndEquivalentAmountTest {

    // ==================== PromotionUsage.source 透传 ====================

    @Test
    void promotionUsage_source_externalCouponPropagated() {
        // 外部 FREE_RANGE 优惠（COUPON）→ usage.source == COUPON
        DayNightConfig config = dayNightConfig("src-coupon", new BigDecimal("1000"));
        BillingService service = createService(config, BConstants.CalculationMode.CONTINUOUS);
        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("coupon-fr")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 7, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        PromotionUsage usage = findUsage(result, "coupon-fr");
        assertNotNull(usage, "应有 coupon-fr usage");
        assertEquals(BConstants.PromotionSource.COUPON, usage.getSource(),
                "外部 FREE_RANGE usage.source 应为 COUPON");
    }

    @Test
    void promotionUsage_source_freeMinutesExternalPropagated() {
        // 外部 FREE_MINUTES → usage.source 透传
        DayNightConfig config = dayNightConfig("src-fm", new BigDecimal("1000"));
        BillingService service = createService(config, BConstants.CalculationMode.CONTINUOUS);
        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("coupon-fm")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(60)
                        .priority(1)
                        .build()
        ));

        BillingResult result = service.calculate(req);

        PromotionUsage usage = findUsage(result, "coupon-fm");
        assertNotNull(usage);
        assertEquals(BConstants.PromotionSource.COUPON, usage.getSource(),
                "外部 FREE_MINUTES usage.source 应为 COUPON");
    }

    // ==================== EquivalentAmountSpec 按需/细致指定 ====================

    @Test
    void equivalentAmountSpec_null_notCalculated() {
        // spec == null → 不计算，totalEquivalentAmount == null
        DayNightConfig config = dayNightConfig("spec-null", new BigDecimal("1000"));
        BillingService service = createService(config, BConstants.CalculationMode.CONTINUOUS);
        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("fr-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 7, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                        .priority(1)
                        .build()
        ));
        // spec 默认 null

        BillingResult result = service.calculate(req);

        assertNull(result.getTotalEquivalentAmount(),
                "spec==null 时 totalEquivalentAmount 应为 null");
    }

    @Test
    void equivalentAmountSpec_allPromotions_calculatedAndBackfilled() {
        // spec 非空（不限 id/类型）→ 计算所有，回填 + total
        DayNightConfig config = dayNightConfig("spec-all", new BigDecimal("1000"));
        BillingService service = createService(config, BConstants.CalculationMode.CONTINUOUS);
        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("fr-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 7, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                        .priority(1)
                        .build()
        ));
        req.setEquivalentAmountSpec(EquivalentAmountSpec.builder().build());

        BillingResult result = service.calculate(req);

        assertNotNull(result.getTotalEquivalentAmount());
        PromotionUsage usage = findUsage(result, "fr-1");
        assertNotNull(usage);
        assertNotNull(usage.getEquivalentAmount(), "spec 命中后 usage.equivalentAmount 应被回填");
        // 07:00-08:00 是夜段（1元/h）→ 等效 1 元
        assertEquals(0, new BigDecimal("1.00").compareTo(usage.getEquivalentAmount()));
        assertEquals(0, new BigDecimal("1.00").compareTo(result.getTotalEquivalentAmount()));
    }

    @Test
    void equivalentAmountSpec_filterByPromotionId() {
        // spec 仅指定某个 id → 只计算该 id
        DayNightConfig config = dayNightConfig("spec-id", new BigDecimal("1000"));
        BillingService service = createService(config, BConstants.CalculationMode.CONTINUOUS);
        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("fr-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 7, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                        .priority(1)
                        .build(),
                PromotionGrant.builder()
                        .id("fr-2")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 9, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 10, 0))
                        .priority(1)
                        .build()
        ));
        req.setEquivalentAmountSpec(EquivalentAmountSpec.builder()
                .promotionIds(Set.of("fr-1"))
                .build());

        BillingResult result = service.calculate(req);

        PromotionUsage fr1 = findUsage(result, "fr-1");
        PromotionUsage fr2 = findUsage(result, "fr-2");
        assertNotNull(fr1.getEquivalentAmount());
        // fr-2 未命中 spec → 不被回填（保持策略侧原价之和近似值，非 null）
        // totalEquivalentAmount 只含 fr-1（07:00-08:00 夜段 1 元）
        assertEquals(0, new BigDecimal("1.00").compareTo(fr1.getEquivalentAmount()));
        assertEquals(0, new BigDecimal("1.00").compareTo(result.getTotalEquivalentAmount()),
                "totalEquivalentAmount 应只含 spec 命中的 fr-1");
    }

    @Test
    void equivalentAmountSpec_filterByType() {
        // spec 仅指定 FREE_MINUTES 类型 → 只计算 FREE_MINUTES
        DayNightConfig config = dayNightConfig("spec-type", new BigDecimal("1000"));
        BillingService service = createService(config, BConstants.CalculationMode.CONTINUOUS);
        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("fr-1")
                        .type(BConstants.PromotionType.FREE_RANGE)
                        .source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, 1, 1, 7, 0))
                        .endTime(LocalDateTime.of(2026, 1, 1, 8, 0))
                        .priority(1)
                        .build(),
                PromotionGrant.builder()
                        .id("fm-1")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(60)
                        .priority(2)
                        .build()
        ));
        req.setEquivalentAmountSpec(EquivalentAmountSpec.builder()
                .types(Set.of(BConstants.PromotionType.FREE_MINUTES))
                .build());

        BillingResult result = service.calculate(req);

        PromotionUsage fm1 = findUsage(result, "fm-1");
        // total 应只含 fm-1 的等效金额（FREE_MINUTES 落在 06:00-07:00 夜段 1 元/h → 1 元）
        assertNotNull(fm1.getEquivalentAmount());
        assertEquals(0, new BigDecimal("1.00").compareTo(result.getTotalEquivalentAmount()),
                "totalEquivalentAmount 应只含 FREE_MINUTES 的等效金额");
    }

    // ==================== 等效金额多段 + 外部优惠 ====================

    @Test
    void equivalentAmount_multiSegmentExternalPromotion_correct() {
        // 多段 + 外部 FREE_MINUTES 跨段共享：等效金额应正确（消去法每次迭代重放 evaluate + writeBack）
        // 段1：06:00-12:00 方案A，段2：12:00-18:00 方案B（schemeChanges 切换）
        // 外部 FREE_MINUTES=120min，priority=1 → 从段1起点顺序分配，跨段共享
        DayNightConfig config = dayNightConfig("multi-seg", new BigDecimal("1000"));
        BillingService service = createService(config, BConstants.CalculationMode.CONTINUOUS);
        BillingRequest req = request(LocalDateTime.of(2026, 1, 1, 6, 0),
                LocalDateTime.of(2026, 1, 1, 18, 0));
        req.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        req.setSchemeChanges(List.of(schemeChange("scheme-1", "scheme-2",
                LocalDateTime.of(2026, 1, 1, 12, 0))));
        req.setExternalPromotions(List.of(
                PromotionGrant.builder()
                        .id("fm-120")
                        .type(BConstants.PromotionType.FREE_MINUTES)
                        .source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(120)
                        .priority(1)
                        .build()
        ));
        req.setEquivalentAmountSpec(EquivalentAmountSpec.builder().build());

        BillingResult result = service.calculate(req);

        PromotionUsage fm = findUsage(result, "fm-120");
        assertNotNull(fm);
        // usedMinutes 应跨段累计 120（整笔共享，不重复）
        assertEquals(120, fm.getUsedMinutes(), "外部 FREE_MINUTES 跨段共享应累计 120min 不重复");
        assertNotNull(result.getTotalEquivalentAmount());
        // 等效金额 > 0（120min 免费占用 06:00-08:00 夜段 1元/h = 2 元）
        assertTrue(result.getTotalEquivalentAmount().compareTo(BigDecimal.ZERO) > 0,
                "等效金额应 > 0");

        // 验证消去法正确性：直接用 calculator 计算应与回填一致
        PromotionEquivalentCalculator calc = new PromotionEquivalentCalculator(service);
        Map<String, BigDecimal> direct = calc.calculate(req);
        assertEquals(direct.get("fm-120"), fm.getEquivalentAmount(),
                "直接 calculator 计算应与 BillingService 回填一致");
    }

    // ==================== GLOBAL_ORIGIN 废弃 ====================

    @Test
    void globalOriginEnum_removed() {
        // GLOBAL_ORIGIN 枚举值已删除，SEGMENT_LOCAL 保留
        assertNotNull(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        boolean hasGlobalOrigin = false;
        for (BConstants.SegmentCalculationMode m : BConstants.SegmentCalculationMode.values()) {
            if (m.name().equals("GLOBAL_ORIGIN")) {
                hasGlobalOrigin = true;
            }
        }
        assertFalse(hasGlobalOrigin, "GLOBAL_ORIGIN 枚举值应已删除");
    }

    // ==================== 辅助方法 ====================

    private PromotionUsage findUsage(BillingResult result, String id) {
        if (result.getPromotionUsages() == null) return null;
        return result.getPromotionUsages().stream()
                .filter(u -> id.equals(u.getPromotionId()))
                .findFirst().orElse(null);
    }

    private static SchemeChange schemeChange(String last, String next, LocalDateTime time) {
        SchemeChange c = new SchemeChange();
        c.setLastSchemeId(last);
        c.setNextSchemeId(next);
        c.setChangeTime(time);
        return c;
    }

    private BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        r.setSchemeChanges(List.of());
        r.setExternalPromotions(List.of());
        return r;
    }

    private DayNightConfig dayNightConfig(String id, BigDecimal maxCharge) {
        return new DayNightConfig()
                .setId(id)
                .setBlockWeight(new BigDecimal("0.5"))
                .setDayBeginMinute(480)   // 08:00
                .setDayEndMinute(1200)    // 20:00
                .setDayUnitPrice(new BigDecimal("2"))
                .setNightUnitPrice(new BigDecimal("1"))
                .setMaxChargeOneDay(maxCharge)
                .setUnitMinutes(60);
    }

    private BillingService createService(DayNightConfig config, BConstants.CalculationMode mode) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return config;
            }
            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return mode;
            }
        };
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
        return new BillingService(
                new SegmentBuilder(),
                resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry()),
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
