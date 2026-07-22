package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * DayNight CONTINUOUS 在 {@code splitDayNightBoundary=false} 下的跨日夜边界处理回归测试。
 * <p>
 * 背景：旧实现用 snap 把日夜边界吸附到单元边，当首个日夜边界的跨边界单元归属导致 snap 落点塌缩到
 * 计算起点时，fallback 会<b>原样返回下一个原始日夜边界</b>（未 snap），使原始 dayEnd（如 12:09）被当成
 * 硬边界切断，产生错误的截断单元。
 * <p>
 * 修复后：{@code splitDayNightBoundary=false} 不再 snap，而是把"跨越日夜边界的单元"孤立成独立 segment，
 * 由 {@code buildSegmentForDayNight → determineUnitPriceForContinuous} 按 crossPeriodMode（默认 BLOCK_WEIGHT）
 * 定价。本测试钉住：
 * <ul>
 *   <li>无任何单元边界落在原始 dayEnd（12:09）；</li>
 *   <li>覆盖 12:00 的跨日夜单元为 {@code [11:50, 12:50)}，按 blockWeight（白天 19/60 &lt; 0.5）归夜价；</li>
 *   <li>覆盖 08:00 的跨日夜单元为 {@code [07:50, 08:50)}，按 blockWeight（白天 47/60 &ge; 0.5）归日价。</li>
 * </ul>
 * 场景为 {@code BillingPlaygroundTest.scenario_cust2} 的形态（两个日夜边界，首边界归属塌缩到起点）。
 */
class DayNightContinuousNoSplitBoundaryTest {

    /** dayBegin=08:03, dayEnd=12:09；计费 07:50-19:51。12:09 不得成为单元边界，跨边界单元按 blockWeight 归属。 */
    @Test
    void noSplit_doesNotCutAtRawDayEndBoundary() {
        BillingResult result = calculate();

        LocalDateTime rawDayEnd = LocalDateTime.of(2026, 1, 1, 12, 9);
        for (BillingUnit unit : result.getUnits()) {
            assertNotEquals(rawDayEnd, unit.getBeginTime(),
                    "splitDayNightBoundary=false 不应在原始 dayEnd 12:09 切断（单元起点）");
            assertNotEquals(rawDayEnd, unit.getEndTime(),
                    "splitDayNightBoundary=false 不应在原始 dayEnd 12:09 切断（单元终点）");
        }

        // 覆盖 12:00 的跨日夜单元 [11:50, 12:50)：白天 19/60 < 0.5 → 归夜价 7
        BillingUnit crossingDayEnd = unitCovering(result, LocalDateTime.of(2026, 1, 1, 12, 0));
        assertNotNull(crossingDayEnd, "应存在覆盖 12:00 的单元");
        assertEquals(LocalDateTime.of(2026, 1, 1, 11, 50), crossingDayEnd.getBeginTime());
        assertEquals(LocalDateTime.of(2026, 1, 1, 12, 50), crossingDayEnd.getEndTime());
        assertEquals(0, new BigDecimal("7").compareTo(crossingDayEnd.getUnitPrice()),
                "跨 dayEnd 单元应按 blockWeight 归夜价");

        // 覆盖 08:00 的跨日夜单元 [07:50, 08:50)：白天 47/60 >= 0.5 → 归日价 6.9
        BillingUnit crossingDayBegin = unitCovering(result, LocalDateTime.of(2026, 1, 1, 8, 0));
        assertNotNull(crossingDayBegin, "应存在覆盖 08:00 的单元");
        assertEquals(LocalDateTime.of(2026, 1, 1, 7, 50), crossingDayBegin.getBeginTime());
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 50), crossingDayBegin.getEndTime());
        assertEquals(0, new BigDecimal("6.9").compareTo(crossingDayBegin.getUnitPrice()),
                "跨 dayBegin 单元应按 blockWeight 归日价");
    }

    /** 与 scenario_cust2 相同配置下最终金额仍撞每日封顶 50。 */
    @Test
    void noSplit_cust2Shape_finalAmountHitsDailyCap() {
        BillingResult result = calculate();
        assertEquals(0, new BigDecimal("50.00").compareTo(result.getFinalAmount()));
    }

    // ==================== 辅助方法 ====================

    private static BillingUnit unitCovering(BillingResult result, LocalDateTime t) {
        for (BillingUnit unit : result.getUnits()) {
            if (!unit.getBeginTime().isAfter(t) && unit.getEndTime().isAfter(t)) {
                return unit;
            }
        }
        return null;
    }

    private BillingResult calculate() {
        DayNightConfig config = DayNightConfig.builder()
                .id("dn-nosplit-cust2")
                .dayBeginMinute(8 * 60 + 3)        // 08:03
                .dayEndMinute(12 * 60 + 9)         // 12:09
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .dayUnitPrice(new BigDecimal("6.9"))
                .nightUnitPrice(new BigDecimal("7"))
                .maxChargeOneDay(new BigDecimal("50"))
                .splitDayNightBoundary(false)
                .build();

        BillingRequest request = new BillingRequest();
        request.setBeginTime(LocalDateTime.of(2026, 1, 1, 7, 50));
        request.setEndTime(LocalDateTime.of(2026, 1, 1, 19, 51));
        request.setSchemeId("scheme-1");
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        request.setExternalPromotions(List.of());

        return createService(config).calculate(request);
    }

    private BillingService createService(DayNightConfig config) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return config;
            }

            @Override
            public List<cn.shang.charging.billing.pojo.PromotionRuleConfig> resolvePromotionRules(
                    String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return List.of();
            }
        };
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
        PromotionEngine promotionEngine = new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry());
        return new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());
    }
}
