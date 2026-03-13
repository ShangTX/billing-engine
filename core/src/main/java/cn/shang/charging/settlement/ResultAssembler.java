package cn.shang.charging.settlement;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class ResultAssembler {

    /**
     * 汇总结果
     */
    public BillingResult assemble(BillingRequest request,
                                  List<BillingSegmentResult> segmentResultList) {

        // 汇总所有计费单元
        List<BillingUnit> allUnits = segmentResultList.stream()
                .map(BillingSegmentResult::getBillingUnits)
                .flatMap(Collection::stream)
                .toList();

        // 汇总优惠使用
        List<PromotionUsage> allUsages = segmentResultList.stream()
                .map(BillingSegmentResult::getPromotionUsages)
                .flatMap(usages -> usages != null ? usages.stream() : Stream.empty())
                .toList();

        // 汇总金额
        BigDecimal totalAmount = segmentResultList.stream()
                .map(BillingSegmentResult::getChargedAmount)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b != null ? b : BigDecimal.ZERO));

        // 汇总费用稳定时间窗口
        LocalDateTime effectiveFrom = calculateEffectiveFrom(segmentResultList);
        LocalDateTime effectiveTo = calculateEffectiveTo(segmentResultList);

        return BillingResult.builder()
                .units(allUnits)
                .promotionUsages(allUsages)
                .finalAmount(totalAmount)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .calculationEndTime(calculateCalculationEndTime(segmentResultList))
                .build();
    }

    /**
     * 汇总 effectiveFrom
     * 取最后一个分段的 feeEffectiveStart
     */
    private LocalDateTime calculateEffectiveFrom(List<BillingSegmentResult> segmentResultList) {
        if (segmentResultList == null || segmentResultList.isEmpty()) {
            return null;
        }
        return segmentResultList.get(segmentResultList.size() - 1).getFeeEffectiveStart();
    }

    /**
     * 汇总 effectiveTo
     * 取所有分段中最早的 feeEffectiveEnd（保守策略）
     */
    private LocalDateTime calculateEffectiveTo(List<BillingSegmentResult> segmentResultList) {
        if (segmentResultList == null || segmentResultList.isEmpty()) {
            return null;
        }
        return segmentResultList.stream()
                .map(BillingSegmentResult::getFeeEffectiveEnd)
                .filter(t -> t != null)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * 汇总 calculationEndTime
     * 取最后一个分段的 calculationEndTime
     */
    private LocalDateTime calculateCalculationEndTime(List<BillingSegmentResult> segmentResultList) {
        if (segmentResultList == null || segmentResultList.isEmpty()) {
            return null;
        }
        // 取最后一个分段的 calculationEndTime
        for (int i = segmentResultList.size() - 1; i >= 0; i--) {
            LocalDateTime time = segmentResultList.get(i).getCalculationEndTime();
            if (time != null) {
                return time;
            }
        }
        return null;
    }
}
