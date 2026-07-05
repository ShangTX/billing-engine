package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BillingUnit;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 通用 compact 合并器：把连续相同的 BillingUnit 合并为单个 compact 单元。
 * <p>
 * 合并条件（除时间窗连续外，其他字段完全相同）：
 * <ul>
 *   <li>unitPrice、originalAmount、free、freePromotionId、chargedAmount、ruleData 一致</li>
 *   <li>非 compact、非 truncated、非简化单元</li>
 *   <li>前后单元时间连续（prev.endTime == next.beginTime）</li>
 *   <li>单子单元时长一致（即 prev.durationMinutes == next.durationMinutes）</li>
 * </ul>
 * 合并后：compact=true, count = 子单元数, durationMinutes = count * subDuration,
 * accumulatedAmount 取合并段最后一个子单元的累计值。
 */
public final class CompactMerger {

    private CompactMerger() {
    }

    /**
     * 把列表中连续相同的单元合并为 compact 单元。
     * 不修改输入列表，返回新列表。
     */
    public static List<BillingUnit> merge(List<BillingUnit> units) {
        if (units == null || units.isEmpty()) {
            return units;
        }
        List<BillingUnit> result = new ArrayList<>();
        BillingUnit runStart = null;
        int runCount = 0;
        BigDecimal runAccumulated = null;
        LocalDateTime runCurrentEnd = null;

        for (BillingUnit u : units) {
            if (u == null) continue;
            if (runStart == null) {
                runStart = u;
                runCount = 1;
                runAccumulated = u.getAccumulatedAmount();
                runCurrentEnd = u.getEndTime();
                continue;
            }
            if (canMerge(runStart, u, runCurrentEnd)) {
                runCount++;
                runAccumulated = u.getAccumulatedAmount();
                runCurrentEnd = u.getEndTime();
            } else {
                result.add(toRunUnit(runStart, runCount, runAccumulated));
                runStart = u;
                runCount = 1;
                runAccumulated = u.getAccumulatedAmount();
                runCurrentEnd = u.getEndTime();
            }
        }
        if (runStart != null) {
            result.add(toRunUnit(runStart, runCount, runAccumulated));
        }
        return result;
    }

    private static boolean canMerge(BillingUnit a, BillingUnit b, LocalDateTime runCurrentEnd) {
        if (a == null || b == null) return false;
        if (a.isCompact() || b.isCompact()) return false;
        if (Boolean.TRUE.equals(a.getIsTruncated()) || Boolean.TRUE.equals(b.getIsTruncated())) {
            return false;
        }
        if (!Objects.equals(a.getUnitPrice(), b.getUnitPrice())) return false;
        if (!Objects.equals(a.getOriginalAmount(), b.getOriginalAmount())) return false;
        if (a.isFree() != b.isFree()) return false;
        if (!Objects.equals(a.getFreePromotionId(), b.getFreePromotionId())) return false;
        if (!Objects.equals(a.getChargedAmount(), b.getChargedAmount())) return false;
        if (!Objects.equals(a.getRuleData(), b.getRuleData())) return false;
        if (a.getDurationMinutes() != b.getDurationMinutes()) return false;
        if (runCurrentEnd == null || !runCurrentEnd.equals(b.getBeginTime())) return false;
        return true;
    }

    private static BillingUnit toRunUnit(BillingUnit first, int runCount, BigDecimal accumulatedAtEnd) {
        if (runCount <= 1) {
            return first;
        }
        int subDuration = first.getDurationMinutes();
        return BillingUnit.builder()
                .beginTime(first.getBeginTime())
                .endTime(first.getEndTime().plusMinutes((long) subDuration * (runCount - 1)))
                .durationMinutes(subDuration * runCount)
                .unitPrice(first.getUnitPrice())
                .originalAmount(first.getOriginalAmount() != null
                        ? first.getOriginalAmount().multiply(BigDecimal.valueOf(runCount))
                        : null)
                .free(first.isFree())
                .freePromotionId(first.getFreePromotionId())
                .chargedAmount(first.getChargedAmount() != null
                        ? first.getChargedAmount().multiply(BigDecimal.valueOf(runCount))
                        : null)
                .accumulatedAmount(accumulatedAtEnd)
                .ruleData(first.getRuleData())
                .isTruncated(first.getIsTruncated())
                .compact(true)
                .count(runCount)
                .build();
    }
}
