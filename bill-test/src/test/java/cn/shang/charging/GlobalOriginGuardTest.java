package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.billing.pojo.SchemeChange;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GLOBAL_ORIGIN 半成品守卫测试（TODO-20260702-001）。
 * <p>
 * GLOBAL_ORIGIN 窗口截取减法未实现，多分段下双重计费；当前止血：
 * <ul>
 *   <li>GLOBAL_ORIGIN + 多分段 → 抛异常</li>
 *   <li>GLOBAL_ORIGIN + 单分段 → 正常（等价 SEGMENT_LOCAL）</li>
 *   <li>UNIT_BASED + GLOBAL_ORIGIN → 抛异常（结构性不兼容）</li>
 * </ul>
 */
class GlobalOriginGuardTest {

    @Test
    void globalOriginMultipleSegmentsThrows() {
        BillingService service = createService(BConstants.BillingMode.CONTINUOUS);
        BillingRequest request = createRequest(
                LocalDateTime.of(2026, 4, 15, 8, 0),
                LocalDateTime.of(2026, 4, 25, 8, 0),
                BConstants.SegmentCalculationMode.GLOBAL_ORIGIN);
        request.setSchemeChanges(List.of(
                createSchemeChange("scheme-a", "scheme-b", LocalDateTime.of(2026, 4, 20, 0, 0))
        ));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.calculate(request));
        assertTrue(ex.getMessage().contains("GLOBAL_ORIGIN"),
                "异常应说明 GLOBAL_ORIGIN：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("多分段"),
                "异常应说明多分段：" + ex.getMessage());
    }

    @Test
    void globalOriginSingleSegmentOk() {
        BillingService service = createService(BConstants.BillingMode.CONTINUOUS);
        BillingRequest request = createRequest(
                LocalDateTime.of(2026, 4, 15, 8, 0),
                LocalDateTime.of(2026, 4, 16, 8, 0),
                BConstants.SegmentCalculationMode.GLOBAL_ORIGIN);
        request.setSchemeChanges(List.of());

        // 单分段 GLOBAL_ORIGIN 等价 SEGMENT_LOCAL，守卫放行
        assertDoesNotThrow(() -> service.calculate(request));
    }

    @Test
    void unitBasedWithGlobalOriginThrows() {
        BillingService service = createService(BConstants.BillingMode.UNIT_BASED);
        BillingRequest request = createRequest(
                LocalDateTime.of(2026, 4, 15, 8, 0),
                LocalDateTime.of(2026, 4, 16, 8, 0),
                BConstants.SegmentCalculationMode.GLOBAL_ORIGIN);
        request.setSchemeChanges(List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.calculate(request));
        assertTrue(ex.getMessage().contains("UNIT_BASED"),
                "异常应说明 UNIT_BASED 不兼容：" + ex.getMessage());
    }

    private static SchemeChange createSchemeChange(String lastSchemeId, String nextSchemeId, LocalDateTime changeTime) {
        SchemeChange change = new SchemeChange();
        change.setLastSchemeId(lastSchemeId);
        change.setNextSchemeId(nextSchemeId);
        change.setChangeTime(changeTime);
        return change;
    }

    private static BillingRequest createRequest(LocalDateTime begin, LocalDateTime end,
                                                BConstants.SegmentCalculationMode mode) {
        BillingRequest request = new BillingRequest();
        request.setId("global-origin-guard");
        request.setBeginTime(begin);
        request.setEndTime(end);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(mode);
        request.setExternalPromotions(List.of());
        request.setSchemeId("scheme-a");
        return request;
    }

    private static BillingService createService(BConstants.BillingMode billingMode) {
        BillingConfigResolver resolver = createResolver(billingMode);
        PromotionRuleRegistry promotionRegistry = new PromotionRuleRegistry();
        PromotionEngine promotionEngine = new PromotionEngine(
                resolver,
                new FreeTimeRangeMerger(),
                promotionRegistry
        );
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());
        return new BillingService(
                new SegmentBuilder(),
                resolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    private static BillingConfigResolver createResolver(BConstants.BillingMode billingMode) {
        return new BillingConfigResolver() {
            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
                return billingMode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(480)
                        .setDayEndMinute(1200)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("50"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of();
            }
        };
    }
}
