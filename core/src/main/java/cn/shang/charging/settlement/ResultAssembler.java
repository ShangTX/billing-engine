package cn.shang.charging.settlement;

import cn.shang.charging.billing.pojo.BillingCarryOver;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.SegmentCarryOver;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // 汇总 calculationEndTime
        LocalDateTime calculationEndTime = calculateCalculationEndTime(segmentResultList);

        // 构建 carryOver（用于支持后续继续计算）
        BillingCarryOver carryOver = buildBillingCarryOver(segmentResultList, calculationEndTime);

        BillingResult result = BillingResult.builder()
                .units(allUnits)
                .promotionUsages(allUsages)
                .finalAmount(totalAmount)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .calculationEndTime(calculationEndTime)
                .carryOver(carryOver)
                .build();

        // 根据 queryTime 过滤（如果指定了 queryTime）
        return filterByQueryTime(result, request.getQueryTime());
    }

    /**
     * 根据 queryTime 过滤计费单元并重新计算金额
     * <p>
     * 用于返回指定时间点的费用状态，而非最终计算结果
     *
     * @param result    原始计费结果
     * @param queryTime 查询时间点
     * @return 过滤后的计费结果
     */
    private BillingResult filterByQueryTime(BillingResult result, LocalDateTime queryTime) {
        if (queryTime == null) {
            return result;
        }

        // 过滤单元：只保留 queryTime 之前完成的单元
        List<BillingUnit> filteredUnits = result.getUnits().stream()
                .filter(unit -> !unit.getEndTime().isAfter(queryTime))
                .toList();

        // 重新计算金额
        BigDecimal filteredAmount = filteredUnits.stream()
                .map(unit -> unit.getChargedAmount() != null ? unit.getChargedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 重新计算 effectiveFrom/effectiveTo
        LocalDateTime effectiveFrom = filteredUnits.isEmpty() ? null : filteredUnits.get(0).getBeginTime();
        LocalDateTime effectiveTo = filteredUnits.isEmpty() ? null : filteredUnits.get(filteredUnits.size() - 1).getEndTime();

        return result.toBuilder()
                .units(filteredUnits)
                .finalAmount(filteredAmount)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
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

    /**
     * 构建 BillingCarryOver
     */
    private BillingCarryOver buildBillingCarryOver(List<BillingSegmentResult> segmentResultList, LocalDateTime calculationEndTime) {
        if (segmentResultList == null || segmentResultList.isEmpty()) {
            return null;
        }

        Map<String, SegmentCarryOver> segments = new HashMap<>();

        for (BillingSegmentResult result : segmentResultList) {
            if (result.getSegmentId() == null) {
                continue;
            }

            // 从 PromotionAggregate 中提取优惠结转状态
            var promotionCarryOver = extractPromotionCarryOver(result.getPromotionAggregate());

            SegmentCarryOver segmentCarryOver = SegmentCarryOver.builder()
                    .ruleState(result.getRuleOutputState())
                    .promotionState(promotionCarryOver)
                    .build();

            segments.put(result.getSegmentId(), segmentCarryOver);
        }

        return BillingCarryOver.builder()
                .calculatedUpTo(calculationEndTime)
                .segments(segments)
                .build();
    }

    /**
     * 从 PromotionAggregate 中提取优惠结转状态
     */
    private cn.shang.charging.billing.pojo.PromotionCarryOver extractPromotionCarryOver(PromotionAggregate aggregate) {
        if (aggregate == null || aggregate.getPromotionCarryOver() == null) {
            return null;
        }
        return aggregate.getPromotionCarryOver();
    }
}
