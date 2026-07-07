package cn.shang.charging.settlement;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.DurationSegment;
import cn.shang.charging.charge.rules.CompactMerger;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 计费结果汇总器。
 * <p>
 * 将多个 {@link BillingSegmentResult} 合并为最终的 {@link BillingResult}：
 * <ul>
 *   <li>跨分段连续相同单元做 compact 合并（{@link CompactMerger}）</li>
 *   <li>汇总时长计费段（DURATION_PERIOD/DURATION_GLOBAL 模式）</li>
 *   <li>汇总优惠使用情况（PromotionUsage）</li>
 *   <li>计算最终金额 = 各分段 chargedAmount 之和</li>
 *   <li>取最后分段的计算结束时间作为 calculationEndTime</li>
 * </ul>
 */
public class ResultAssembler {

    /**
     * 汇总结果
     */
    public BillingResult assemble(BillingRequest request,
                                  List<BillingSegmentResult> segmentResultList) {

        // 汇总所有计费单元，并对跨分段的连续相同单元做 compact 合并
        List<BillingUnit> allUnits = CompactMerger.merge(
                segmentResultList.stream()
                        .map(BillingSegmentResult::getBillingUnits)
                        .flatMap(Collection::stream)
                        .toList()
        );

        // 汇总时长计费段（时长计费模式）
        List<DurationSegment> allDurationSegments = segmentResultList.stream()
                .map(BillingSegmentResult::getDurationSegments)
                .filter(segments -> segments != null && !segments.isEmpty())
                .flatMap(Collection::stream)
                .toList();

        // 汇总优惠使用
        List<PromotionUsage> allUsages = segmentResultList.stream()
                .map(BillingSegmentResult::getPromotionUsages)
                .flatMap(usages -> usages != null ? usages.stream() : Stream.empty())
                .toList();

        // finalAmount = 各分段 chargedAmount 之和（单元模式与时长模式统一）
        BigDecimal finalAmount = segmentResultList.stream()
                .map(BillingSegmentResult::getChargedAmount)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b != null ? b : BigDecimal.ZERO));


        // 汇总 calculationEndTime
        LocalDateTime calculationEndTime = calculateCalculationEndTime(segmentResultList);

        return BillingResult.builder()
                .units(allUnits)
                .durationSegments(allDurationSegments.isEmpty() ? null : allDurationSegments)
                .promotionUsages(allUsages)
                .finalAmount(finalAmount)
                .calculationEndTime(calculationEndTime)
                .build();
    }

    /**
     * 汇总 calculationEndTime
     * 取最后一个分段的 calculationEndTime
     */
    private LocalDateTime calculateCalculationEndTime(List<BillingSegmentResult> segmentResultList) {
        if (segmentResultList == null || segmentResultList.isEmpty()) {
            return null;
        }
        for (int i = segmentResultList.size() - 1; i >= 0; i--) {
            LocalDateTime time = segmentResultList.get(i).getCalculationEndTime();
            if (time != null) {
                return time;
            }
        }
        return null;
    }
}
