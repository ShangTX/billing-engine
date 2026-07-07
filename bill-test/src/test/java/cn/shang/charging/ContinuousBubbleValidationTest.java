package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CONTINUOUS 模式 BUBBLE 免费时段校验测试。
 * <p>
 * CONTINUOUS 模式不支持 BUBBLE 免费时段（bubble 需 effective 周期，CONTINUOUS 未消费），
 * 遇 BUBBLE 段直接抛 {@link IllegalArgumentException}，引导改用 DURATION_PERIOD/DURATION_GLOBAL。
 */
class ContinuousBubbleValidationTest {

    /** CONTINUOUS + BUBBLE 免费段 → 抛 IllegalArgumentException。 */
    @Test
    void continuousMode_bubble_throws() {
        BillingService service = createService(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0));
        req.setExternalPromotions(List.of(freeRange("bubble",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 6, 0),
                FreeTimeRangeType.BUBBLE)));

        assertThrows(IllegalArgumentException.class, () -> service.calculate(req));
    }

    /** CONTINUOUS + NORMAL 免费段 → 正常计算（不抛异常）。 */
    @Test
    void continuousMode_normal_ok() {
        BillingService service = createService(BConstants.CalculationMode.CONTINUOUS);

        BillingRequest req = request(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 2, 0, 0));
        req.setExternalPromotions(List.of(freeRange("normal",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 6, 0),
                FreeTimeRangeType.NORMAL)));

        BillingResult result = service.calculate(req);

        // 6h 免费 + 18h 收费，18×3=54>50 封顶 50
        assertEquals(0, new BigDecimal("50").compareTo(result.getFinalAmount()));
    }

    // ==================== 辅助方法 ====================

    private DayNightConfig config() {
        return new DayNightConfig()
                .setId("dn-bubble-validation")
                .setDayBeginMinute(0)
                .setDayEndMinute(1440)
                .setDayUnitPrice(new BigDecimal("3"))
                .setNightUnitPrice(new BigDecimal("3"))
                .setUnitMinutes(60)
                .setMaxChargeOneDay(new BigDecimal("50"))
                .setBlockWeight(new BigDecimal("0.5"));
    }

    private PromotionGrant freeRange(String id, LocalDateTime begin, LocalDateTime end, FreeTimeRangeType rangeType) {
        return PromotionGrant.builder()
                .id(id)
                .type(BConstants.PromotionType.FREE_RANGE)
                .source(BConstants.PromotionSource.COUPON)
                .priority(1)
                .rangeType(rangeType)
                .beginTime(begin)
                .endTime(end)
                .build();
    }

    private BillingRequest request(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSchemeChanges(List.of());
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SINGLE);
        r.setExternalPromotions(List.of());
        return r;
    }

    private BillingService createService(BConstants.CalculationMode mode) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return mode;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return config();
            }

            @Override
            public List<cn.shang.charging.billing.pojo.PromotionRuleConfig> resolvePromotionRules(
                    String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> context) {
                return List.of();
            }
        };
        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        PromotionEngine promotionEngine = new PromotionEngine(resolver, new FreeTimeRangeMerger(), new PromotionRuleRegistry());
        return new BillingService(
                new SegmentBuilder(), resolver, promotionEngine,
                new BillingCalculator(ruleRegistry), new ResultAssembler());
    }
}
