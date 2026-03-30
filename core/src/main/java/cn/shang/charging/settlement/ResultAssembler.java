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

        // 汇总金额（本次计算的费用）
        BigDecimal totalAmount = segmentResultList.stream()
                .map(BillingSegmentResult::getChargedAmount)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b != null ? b : BigDecimal.ZERO));

        // 计算累计金额（从最后一个单元获取）
        BigDecimal accumulatedAmount = extractAccumulatedAmountFromUnits(allUnits);

        // 汇总费用稳定时间窗口
        LocalDateTime effectiveFrom = calculateEffectiveFrom(segmentResultList);
        LocalDateTime effectiveTo = calculateEffectiveTo(segmentResultList);

        // 汇总 calculationEndTime
        LocalDateTime calculationEndTime = calculateCalculationEndTime(segmentResultList);

        // 构建 carryOver（用于支持后续继续计算）
        BillingCarryOver carryOver = buildBillingCarryOver(segmentResultList, calculationEndTime);

        // 检测第一个单元是否是合并单元
        Boolean firstUnitMerged = detectFirstUnitMerged(segmentResultList);

        BillingResult result = BillingResult.builder()
                .units(allUnits)
                .promotionUsages(allUsages)
                .finalAmount(accumulatedAmount != null ? accumulatedAmount : totalAmount)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .calculationEndTime(calculationEndTime)
                .carryOver(carryOver)
                .firstUnitMerged(firstUnitMerged)
                .build();

        return result;
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

        // 提取最后一个截断单元的开始时间
        LocalDateTime lastTruncatedUnitStartTime = extractLastTruncatedUnitStartTime(segmentResultList);

        // 提取截断单元已收取的金额
        BigDecimal truncatedUnitChargedAmount = extractTruncatedUnitChargedAmount(segmentResultList);

        // 提取累计金额（从最后一个单元获取）
        BigDecimal accumulatedAmount = extractAccumulatedAmount(segmentResultList);

        return BillingCarryOver.builder()
                .calculatedUpTo(calculationEndTime)
                .segments(segments)
                .lastTruncatedUnitStartTime(lastTruncatedUnitStartTime)
                .truncatedUnitChargedAmount(truncatedUnitChargedAmount)
                .accumulatedAmount(accumulatedAmount)
                .build();
    }

    /**
     * 提取累计金额
     * 从最后一个单元获取 accumulatedAmount
     */
    private BigDecimal extractAccumulatedAmount(List<BillingSegmentResult> segmentResultList) {
        if (segmentResultList == null || segmentResultList.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 从最后一个分段开始向前查找
        for (int i = segmentResultList.size() - 1; i >= 0; i--) {
            BillingSegmentResult segment = segmentResultList.get(i);
            List<BillingUnit> units = segment.getBillingUnits();
            if (units == null || units.isEmpty()) {
                continue;
            }

            // 返回最后一个单元的累计金额
            BillingUnit lastUnit = units.get(units.size() - 1);
            if (lastUnit.getAccumulatedAmount() != null) {
                return lastUnit.getAccumulatedAmount();
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * 检测第一个单元是否是合并单元
     * @deprecated 已废弃，改用 accumulatedAmount 字段
     */
    @Deprecated
    private Boolean detectFirstUnitMerged(List<BillingSegmentResult> segmentResultList) {
        // 不再需要合并检测，返回 false
        return false;
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

    /**
     * 提取最后一个截断单元的开始时间
     * 从所有分段的最后一个单元中查找
     * 返回最后一个标记为 isTruncated=true 的单元的开始时间
     */
    private LocalDateTime extractLastTruncatedUnitStartTime(List<BillingSegmentResult> segmentResultList) {
        if (segmentResultList == null || segmentResultList.isEmpty()) {
            return null;
        }

        // 从最后一个分段开始向前查找
        for (int i = segmentResultList.size() - 1; i >= 0; i--) {
            BillingSegmentResult segment = segmentResultList.get(i);
            List<BillingUnit> units = segment.getBillingUnits();
            if (units == null || units.isEmpty()) {
                continue;
            }

            // 从该分段的最后一个单元开始向前查找截断单元
            for (int j = units.size() - 1; j >= 0; j--) {
                BillingUnit unit = units.get(j);
                if (unit.getIsTruncated() != null && unit.getIsTruncated()) {
                    return unit.getBeginTime();
                }
            }
        }

        return null;
    }

    /**
     * 提取截断单元已收取的金额
     * 返回最后一个截断单元的 chargedAmount
     */
    private BigDecimal extractTruncatedUnitChargedAmount(List<BillingSegmentResult> segmentResultList) {
        if (segmentResultList == null || segmentResultList.isEmpty()) {
            return null;
        }

        // 从最后一个分段开始向前查找
        for (int i = segmentResultList.size() - 1; i >= 0; i--) {
            BillingSegmentResult segment = segmentResultList.get(i);
            List<BillingUnit> units = segment.getBillingUnits();
            if (units == null || units.isEmpty()) {
                continue;
            }

            // 从该分段的最后一个单元开始向前查找截断单元
            for (int j = units.size() - 1; j >= 0; j--) {
                BillingUnit unit = units.get(j);
                if (unit.getIsTruncated() != null && unit.getIsTruncated()) {
                    return unit.getChargedAmount();
                }
            }
        }

        return null;
    }

    /**
     * 从计费单元列表中提取累计金额
     * 返回最后一个单元的 accumulatedAmount
     */
    private BigDecimal extractAccumulatedAmountFromUnits(List<BillingUnit> allUnits) {
        if (allUnits == null || allUnits.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BillingUnit lastUnit = allUnits.get(allUnits.size() - 1);
        return lastUnit.getAccumulatedAmount();
    }
}
