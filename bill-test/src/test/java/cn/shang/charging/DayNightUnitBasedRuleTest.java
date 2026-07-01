package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightUnitBasedRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DayNightUnitBasedRule 冒烟测试：验证 UNIT_BASED 语义（固定边界 + 完全覆盖才免费）。
 */
class DayNightUnitBasedRuleTest {

    @Test
    void fixedAlignment_basicCalculation() {
        // 8:00-10:00，60min 单元，日间 2 元，应收 2 单元 × 2 = 4 元
        // 两个相同单元会被 ResultAssembler 的 CompactMerger 合并为 1 个 compact 单元（count=2）
        BillingResult result = calculate(null, LocalDateTime.of(2026, 4, 20, 8, 0), LocalDateTime.of(2026, 4, 20, 10, 0));
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));
        assertEquals(1, result.getUnits().size());
        BillingUnit unit = result.getUnits().get(0);
        assertTrue(unit.isCompact());
        assertEquals(2, unit.getCount());
        assertEquals(120, unit.getDurationMinutes());
    }

    @Test
    void freeRangeMustFullyCoverUnit_partialCoverNotFree() {
        // 免费段 08:30-09:30 覆盖了 08:00-09:00 单元的后半和 09:00-10:00 单元的前半，
        // 但都不完整覆盖。UNIT_BASED 语义下两个单元都不免费。
        PromotionGrant free = PromotionGrant.builder()
                .id("partial-free")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 4, 20, 8, 30))
                .endTime(LocalDateTime.of(2026, 4, 20, 9, 30))
                .build();
        BillingResult result = calculate(List.of(free), LocalDateTime.of(2026, 4, 20, 8, 0), LocalDateTime.of(2026, 4, 20, 10, 0));
        // 两单元都不免费，应收 4 元
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getFinalAmount()));
        result.getUnits().forEach(u -> assertFalse(u.isFree(), "部分覆盖单元不应免费"));
    }

    @Test
    void freeRangeFullyCoversUnit_makesUnitFree() {
        // 免费段 08:00-10:00 完整覆盖两个单元，都免费
        PromotionGrant free = PromotionGrant.builder()
                .id("full-free")
                .type(BConstants.PromotionType.FREE_RANGE)
                .priority(1)
                .beginTime(LocalDateTime.of(2026, 4, 20, 8, 0))
                .endTime(LocalDateTime.of(2026, 4, 20, 10, 0))
                .build();
        BillingResult result = calculate(List.of(free), LocalDateTime.of(2026, 4, 20, 8, 0), LocalDateTime.of(2026, 4, 20, 10, 0));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getFinalAmount()));
        result.getUnits().forEach(u -> assertTrue(u.isFree(), "完整覆盖单元应免费"));
    }

    @Test
    void dailyCap_applies() {
        // 单元 1 元，封顶 1.50，8:00-10:00 两单元应收 1 + 0.5 = 1.50
        BillingResult result = calculateWithCap(new BigDecimal("1.50"),
                LocalDateTime.of(2026, 4, 20, 8, 0), LocalDateTime.of(2026, 4, 20, 10, 0));
        assertEquals(0, new BigDecimal("1.50").compareTo(result.getFinalAmount()));
    }

    private BillingResult calculate(List<PromotionGrant> promotions, LocalDateTime begin, LocalDateTime end) {
        return calculateWithCap(promotions, new BigDecimal("1000.00"), begin, end);
    }

    private BillingResult calculateWithCap(BigDecimal maxCharge, LocalDateTime begin, LocalDateTime end) {
        return calculateWithCap(null, maxCharge, begin, end);
    }

    private BillingResult calculateWithCap(List<PromotionGrant> promotions, BigDecimal maxCharge,
                                            LocalDateTime begin, LocalDateTime end) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> ctx) {
                return BConstants.BillingMode.UNIT_BASED;
            }
            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> ctx) {
                return DayNightConfig.builder()
                        .id("daynight-unitbased")
                        .dayBeginMinute(8 * 60)
                        .dayEndMinute(19 * 60)
                        .unitMinutes(60)
                        .blockWeight(new BigDecimal("0.5"))
                        .dayUnitPrice(new BigDecimal("2.00"))
                        .nightUnitPrice(new BigDecimal("1.00"))
                        .maxChargeOneDay(maxCharge)
                        .build();
            }
            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> ctx) {
                return List.of();
            }
        };
        BillingRuleRegistry rr = new BillingRuleRegistry();
        rr.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightUnitBasedRule());
        PromotionEngine pe = new PromotionEngine(resolver, new FreeTimeRangeMerger(), new FreeMinuteAllocator(), new PromotionRuleRegistry());
        BillingService svc = new BillingService(new SegmentBuilder(), resolver, pe, new BillingCalculator(rr), new ResultAssembler());

        BillingRequest req = new BillingRequest();
        req.setBeginTime(begin);
        req.setEndTime(end);
        req.setSchemeId("scheme-1");
        req.setSchemeChanges(List.of());
        req.setExternalPromotions(promotions != null ? promotions : List.of());
        req.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        return svc.calculate(req);
    }
}
