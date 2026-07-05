package cn.shang.charging.charge.rules;

import cn.shang.charging.billing.pojo.BillingUnit;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 公共的同质段 → 计费单元（compact 合并）计算器。
 * <p>
 * 给定一组连续的同质计费段（由边界驱动循环产出），
 * 把可合并的相邻段合并为一个 compact 单元（count &gt; 1），
 * 不能合并的段作为单子单元（count == 1）。
 */
public final class HomogeneousSegmentCalculator {

    private HomogeneousSegmentCalculator() {
    }

    /**
     * 将同质段列表合并为 BillingUnit 列表（compact 已应用）。
     * <p>
     * 合并规则：相邻段 canMergeWith 为 true 时合并为 compact 单元，count 累加；
     * 否则保留为单子单元（compact=false, count=1）。
     */
    public static List<BillingUnit> toCompactUnits(List<HomogeneousSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return new ArrayList<>();
        }

        List<BillingUnit> units = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;

        int i = 0;
        while (i < segments.size()) {
            HomogeneousSegment current = segments.get(i);
            int runLength = 1;
            int totalMinutes = current.durationMinutes();
            int j = i + 1;
            // 贪心合并：只有当下一个段与当前 run 末段同质时才继续
            while (j < segments.size() && current.canMergeWith(segments.get(j))) {
                totalMinutes += segments.get(j).durationMinutes();
                runLength++;
                j++;
            }

            // 生成一个 BillingUnit（compact 或非 compact）
            BigDecimal segmentCharged = computeChargedAmount(current, runLength, totalMinutes);

            accumulated = accumulated.add(segmentCharged);
            boolean isCompact = runLength > 1;

            BillingUnit unit = BillingUnit.builder()
                    .beginTime(current.getBeginTime())
                    .endTime(current.getEndTime())
                    .durationMinutes(totalMinutes)
                    .unitPrice(current.getUnitPrice())
                    .originalAmount(current.getOriginalAmount() != null
                            ? current.getOriginalAmount().multiply(BigDecimal.valueOf(runLength))
                            : null)
                    .free(current.isFree())
                    .freePromotionId(current.getFreePromotionId())
                    .chargedAmount(segmentCharged)
                    .accumulatedAmount(accumulated)
                    .ruleData(current.getRuleData())
                    .compact(isCompact)
                    .count(isCompact ? runLength : 1)
                    .build();
            units.add(unit);
            i = j;
        }
        return units;
    }

    private static BigDecimal computeChargedAmount(HomogeneousSegment segment, int runLength, int totalMinutes) {
        BigDecimal original = segment.getOriginalAmount();
        if (original == null) {
            return BigDecimal.ZERO;
        }
        // 按子单元计费：合并 N 段，每段 originalAmount 一致，chargedAmount = N * originalAmount
        if (segment.isFree()) {
            return BigDecimal.ZERO;
        }
        return original.multiply(BigDecimal.valueOf(runLength));
    }
}
