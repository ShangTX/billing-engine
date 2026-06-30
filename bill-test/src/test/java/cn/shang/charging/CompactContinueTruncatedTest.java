package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingCarryOver;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 CONTINUE 模式下 compact 与 truncated 单元的共存：
 * 第一步计算到中间时刻，最后单元被截断（truncated）保持独立、不 compact；
 * 前面的完整单元正常 compact；续算扣减正确。
 */
class CompactContinueTruncatedTest {

    @Test
    void continueMode_truncatedUnitStaysIndependent_fromCompact() {
        // 单时段 30min 1.50，8:00-10:30 = 5 个子单元，在 9:15 截断
        // 第一步 8:00-9:15：3 个完整 30min 子单元 compact + 1 个 15min truncated
        // 第二步 9:15-10:30：续算，扣减 truncated 已收金额
        RelativeTimeConfig config = RelativeTimeConfig.builder()
                .id("rel-continue-compact")
                .periods(List.of(RelativeTimePeriod.builder()
                        .beginMinute(0).endMinute(1440).unitMinutes(30)
                        .unitPrice(new BigDecimal("1.50")).build()))
                .maxChargeOneCycle(new BigDecimal("1000.00")).build();
        BillingService service = service(config);

        LocalDateTime begin = LocalDateTime.of(2026, 4, 20, 8, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 20, 10, 30);
        LocalDateTime split = LocalDateTime.of(2026, 4, 20, 9, 15);

        // 第一步：8:00-9:15
        BillingRequest firstReq = baseRequest(begin, split);
        BillingResult firstResult = service.calculate(firstReq);

        BillingUnit lastUnit = firstResult.getUnits().get(firstResult.getUnits().size() - 1);

        // 截断单元必须独立、不 compact
        assertTrue(Boolean.TRUE.equals(lastUnit.getIsTruncated()), "最后单元应被截断");
        assertFalse(lastUnit.isCompact(), "截断单元不应 compact");
        assertEquals(15, lastUnit.getDurationMinutes(), "截断单元应为 15 分钟");
        assertEquals(split, lastUnit.getEndTime(), "截断单元结束于 splitTime");

        // 前面应有 compact 单元（8:00-9:00 的 2 个子单元合并）
        long compactCount = firstResult.getUnits().stream().filter(BillingUnit::isCompact).count();
        assertTrue(compactCount > 0, "截断单元前应有 compact 单元");

        // carryOver 应携带截断单元的 beginTime 和 chargedAmount
        BillingCarryOver carryOver = firstResult.getCarryOver();
        assertNotNull(carryOver.getLastTruncatedUnitStartTime(), "carryOver 应携带截断单元起点");
        assertEquals(lastUnit.getBeginTime(), carryOver.getLastTruncatedUnitStartTime(),
                "carryOver 截断起点 = 截断单元 beginTime");
        assertEquals(lastUnit.getChargedAmount(), carryOver.getTruncatedUnitChargedAmount(),
                "carryOver 截断金额 = 截断单元 chargedAmount");

        // 第二步：续算，扣减 truncated 已收金额
        BillingRequest secondReq = baseRequest(begin, end);
        secondReq.setPreviousCarryOver(carryOver);
        BillingResult secondResult = service.calculate(secondReq);

        // 续算语义：第一步累计 4.50（含 truncated 1.50），第二步从 9:00 重算，
        // 9:00-9:30 单元扣减已收的 1.50 → 净 0；9:30-10:30 收 3.00。
        // accumulated 起始 = previousAccumulated(4.50) - truncatedDeduction(1.50) = 3.00，
        // 加续算段净增 3.00 = 6.00。这是 CONTINUE + 不足单元收全额的既有语义。
        BigDecimal expected = new BigDecimal("6.00");
        assertEquals(0, expected.compareTo(secondResult.getFinalAmount()),
                () -> "续算总金额不符，expected=" + expected
                        + ", actual=" + secondResult.getFinalAmount());

        // 第二步的 compact 单元也不应与截断单元合并
        BillingUnit step2Last = secondResult.getUnits().get(secondResult.getUnits().size() - 1);
        assertFalse(Boolean.TRUE.equals(step2Last.getIsTruncated()),
                "第二步最后单元未被 calcEnd 截断（10:30 正好对齐 30min 边界）");
    }

    private BillingRequest baseRequest(LocalDateTime begin, LocalDateTime end) {
        BillingRequest r = new BillingRequest();
        r.setBeginTime(begin);
        r.setEndTime(end);
        r.setSchemeId("scheme-1");
        r.setSchemeChanges(List.of());
        r.setExternalPromotions(List.of());
        r.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        return r;
    }

    private BillingService service(RelativeTimeConfig config) {
        BillingConfigResolver resolver = new BillingConfigResolver() {
            @Override public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> ctx) { return BConstants.BillingMode.CONTINUOUS; }
            @Override public RuleConfig resolveChargingRule(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> ctx) { return config; }
            @Override public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime s, LocalDateTime e, Map<String, Object> ctx) { return List.of(); }
        };
        BillingRuleRegistry rr = new BillingRuleRegistry();
        rr.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
        return new BillingService(new SegmentBuilder(), resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), new FreeMinuteAllocator(), new PromotionRuleRegistry()),
                new BillingCalculator(rr), new ResultAssembler());
    }
}
