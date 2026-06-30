package cn.shang.charging;

import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingContext;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.CalculationWindow;
import cn.shang.charging.charge.rules.CompactMerger;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeConfig;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeRule;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边界驱动 + compact 输出的最小验证。
 * 验证：连续 N 个相同子单元经 CompactMerger 合并为 compact 单元，
 * 合并后 amount 与逐单元累加一致。
 */
class CompactMergerTest {

    @Test
    void shouldMergeConsecutiveIdenticalUnits() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<BillingUnit> units = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BillingUnit u = BillingUnit.builder()
                    .beginTime(begin.plusMinutes(i * 60L))
                    .endTime(begin.plusMinutes((i + 1) * 60L))
                    .durationMinutes(60)
                    .unitPrice(BigDecimal.valueOf(2))
                    .originalAmount(BigDecimal.valueOf(2))
                    .chargedAmount(BigDecimal.valueOf(2))
                    .accumulatedAmount(BigDecimal.valueOf((i + 1) * 2))
                    .free(false)
                    .compact(false)
                    .count(1)
                    .build();
            units.add(u);
        }
        List<BillingUnit> merged = CompactMerger.merge(units);
        // debugging
        for (int i = 0; i < merged.size(); i++) {
            BillingUnit m = merged.get(i);
            System.out.println("merged[" + i + "] = " + m.getBeginTime() + " to " + m.getEndTime() + " dur=" + m.getDurationMinutes() + " count=" + m.getCount() + " compact=" + m.isCompact());
        }
        assertEquals(1, merged.size());
        BillingUnit u = merged.get(0);
        assertTrue(u.isCompact());
        assertEquals(5, u.getCount());
        assertEquals(300, u.getDurationMinutes());
        assertEquals(BigDecimal.valueOf(10), u.getChargedAmount());
        assertEquals(BigDecimal.valueOf(10), u.getAccumulatedAmount());
    }

    @Test
    void shouldNotMergeUnitsWithDifferentPrice() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<BillingUnit> units = List.of(
                makeUnit(begin, 0, 60, "2"),
                makeUnit(begin, 60, 120, "3")
        );
        List<BillingUnit> merged = CompactMerger.merge(units);
        assertEquals(2, merged.size());
        assertFalse(merged.get(0).isCompact());
    }

    @Test
    void shouldNotMergeTruncatedUnits() {
        LocalDateTime begin = LocalDateTime.of(2026, 1, 1, 0, 0);
        BillingUnit a = BillingUnit.builder()
                .beginTime(begin).endTime(begin.plusMinutes(60))
                .durationMinutes(60).unitPrice(BigDecimal.ONE)
                .chargedAmount(BigDecimal.ONE).build();
        BillingUnit b = BillingUnit.builder()
                .beginTime(begin.plusMinutes(60)).endTime(begin.plusMinutes(80))
                .durationMinutes(20).unitPrice(BigDecimal.ONE)
                .chargedAmount(BigDecimal.ONE).isTruncated(true).build();
        List<BillingUnit> merged = CompactMerger.merge(List.of(a, b));
        assertEquals(2, merged.size());
    }

    private BillingUnit makeUnit(LocalDateTime origin, int fromMin, int toMin, String price) {
        return BillingUnit.builder()
                .beginTime(origin.plusMinutes(fromMin))
                .endTime(origin.plusMinutes(toMin))
                .durationMinutes(toMin - fromMin)
                .unitPrice(new BigDecimal(price))
                .originalAmount(new BigDecimal(price))
                .chargedAmount(new BigDecimal(price))
                .accumulatedAmount(new BigDecimal(price))
                .free(false)
                .build();
    }
}
