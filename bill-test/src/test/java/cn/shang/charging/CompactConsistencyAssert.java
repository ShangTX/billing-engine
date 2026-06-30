package cn.shang.charging;

import cn.shang.charging.billing.pojo.BillingUnit;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * compact 单元自洽性校验工具：把 compact 单元展开为子单元，断言子单元等长、等价、累计连续。
 * <p>
 * compact 是边界驱动循环的自然产物，其正确性来源于"同质段内单价/免费状态/时长不变"。
 * 本工具展开 compact 单元，验证：
 * <ul>
 *   <li>子单元数 == count</li>
 *   <li>每个子单元时长 == durationMinutes / count</li>
 *   <li>子单元的 unitPrice/originalAmount/chargedAmount/free/freePromotionId/valueSpec 一致</li>
 *   <li>展开后子单元的 accumulatedAmount 单调递增且步长等于子单元 chargedAmount</li>
 *   <li>compact 单元的 chargedAmount == 子单元 chargedAmount × count</li>
 *   <li>compact 单元的 accumulatedAmount == 最后一个子单元的累计值</li>
 * </ul>
 */
final class CompactConsistencyAssert {

    private CompactConsistencyAssert() {
    }

    /**
     * 校验单个 compact 单元的自洽性。
     */
    static void assertCompactConsistent(BillingUnit unit) {
        assertTrue(unit.isCompact(), "单元必须是 compact");
        int count = unit.getCount();
        assertTrue(count > 1, "compact 单元 count 必须 > 1，实际=" + count);

        int totalMinutes = unit.getDurationMinutes();
        int subDuration = totalMinutes / count;
        assertEquals(totalMinutes, subDuration * count,
                "compact 单元 durationMinutes 必须是 count 的整数倍");

        BigDecimal subCharged = unit.getUnitPrice() != null ? unit.getUnitPrice() : BigDecimal.ZERO;
        if (!unit.isFree()) {
            // 不足单元收全额：子单元 chargedAmount == unitPrice（与规则语义一致）
            assertEquals(0, subCharged.multiply(BigDecimal.valueOf(count)).compareTo(unit.getChargedAmount()),
                    "compact chargedAmount 必须等于 子单元单价 × count");
        } else {
            assertEquals(0, BigDecimal.ZERO.compareTo(unit.getChargedAmount()),
                    "免费 compact 单元 chargedAmount 必须为 0");
            subCharged = BigDecimal.ZERO;
        }

        // 累计金额：compact.accumulatedAmount 应等于"段前累计 + count × subCharged"
        // 段前累计无法从 compact 单元单独推断，这里只校验 chargedAmount 与 accumulatedAmount 的关系
        // 由 assertUnitsConsistent 做跨单元连续性校验
    }

    /**
     * 校验 BillingUnit 列表的整体自洽性：展开所有 compact 单元后，
     * 子单元序列的 accumulatedAmount 从第一个单元开始单调递增、步长等于各子单元 chargedAmount，
     * 且时间连续无重叠无间隙。
     */
    static void assertUnitsConsistent(List<BillingUnit> units) {
        assertTrue(units != null && !units.isEmpty(), "单元列表不能为空");

        List<BillingUnit> expanded = new ArrayList<>();
        for (BillingUnit unit : units) {
            if (unit.isCompact() && unit.getCount() > 1) {
                expanded.addAll(expandCompact(unit));
            } else {
                expanded.add(unit);
            }
        }

        // 时间连续性 + 累计金额单调递增
        LocalDateTime prevEnd = null;
        BigDecimal accumulated = BigDecimal.ZERO;
        for (int i = 0; i < expanded.size(); i++) {
            BillingUnit sub = expanded.get(i);
            if (prevEnd != null) {
                assertEquals(prevEnd, sub.getBeginTime(),
                        "子单元 " + i + " 与前一单元不连续：prevEnd=" + prevEnd + " begin=" + sub.getBeginTime());
            }
            int subDur = sub.getDurationMinutes();
            assertTrue(subDur > 0, "子单元 " + i + " 时长必须 > 0");
            assertEquals(subDur, (int) Duration.between(sub.getBeginTime(), sub.getEndTime()).toMinutes(),
                    "子单元 " + i + " durationMinutes 与时间区间不符");

            BigDecimal charged = sub.getChargedAmount() != null ? sub.getChargedAmount() : BigDecimal.ZERO;
            assertTrue(charged.signum() >= 0, "子单元 " + i + " chargedAmount 不能为负");
            accumulated = accumulated.add(charged);
            if (sub.getAccumulatedAmount() != null) {
                assertEquals(0, accumulated.compareTo(sub.getAccumulatedAmount()),
                        "子单元 " + i + " accumulatedAmount 与展开序列累计不符：期望=" + accumulated + " 实际=" + sub.getAccumulatedAmount());
            }
            prevEnd = sub.getEndTime();
        }
    }

    /**
     * 把 compact 单元展开为 count 个子单元。
     * 子单元继承 compact 的 unitPrice/originalAmount/free/freePromotionId/valueSpec，
     * chargedAmount 取子单元单价（不足单元收全额），accumulatedAmount 按步长递增。
     */
    private static List<BillingUnit> expandCompact(BillingUnit unit) {
        int count = unit.getCount();
        int subDuration = unit.getDurationMinutes() / count;
        BigDecimal subCharged = unit.isFree()
                ? BigDecimal.ZERO
                : (unit.getUnitPrice() != null ? unit.getUnitPrice() : BigDecimal.ZERO);
        BigDecimal subOriginal = unit.isFree()
                ? BigDecimal.ZERO
                : (unit.getUnitPrice() != null ? unit.getUnitPrice() : BigDecimal.ZERO);

        // 段前累计 = compact.accumulatedAmount - compact.chargedAmount
        BigDecimal segmentAccumulated = unit.getAccumulatedAmount() != null
                ? unit.getAccumulatedAmount().subtract(unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO)
                : BigDecimal.ZERO;

        List<BillingUnit> subs = new ArrayList<>(count);
        LocalDateTime current = unit.getBeginTime();
        for (int i = 0; i < count; i++) {
            LocalDateTime subEnd = current.plusMinutes(subDuration);
            segmentAccumulated = segmentAccumulated.add(subCharged);
            BillingUnit sub = BillingUnit.builder()
                    .beginTime(current)
                    .endTime(subEnd)
                    .durationMinutes(subDuration)
                    .unitPrice(unit.getUnitPrice())
                    .originalAmount(subOriginal)
                    .free(unit.isFree())
                    .freePromotionId(unit.getFreePromotionId())
                    .chargedAmount(subCharged)
                    .accumulatedAmount(segmentAccumulated)
                    .valueSpec(unit.getValueSpec())
                    .ruleData(unit.getRuleData())
                    .build();
            subs.add(sub);
            current = subEnd;
        }
        return subs;
    }
}
