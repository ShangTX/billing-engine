package cn.shang.charging.settlement;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ResultAssembler {

    /**
     * 汇总结果
     */
    public BillingResult assemble(BillingRequest request,
                                  List<BillingSegmentResult> segmentResultList) {
        // 汇总总金额
        BigDecimal finalAmount = segmentResultList.stream()
                .map(BillingSegmentResult::getChargedAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 汇总所有计费单元
        List<BillingUnit> units = segmentResultList.stream()
                .map(BillingSegmentResult::getBillingUnits)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        // 汇总优惠使用情况
        List<PromotionUsage> promotionUsages = segmentResultList.stream()
                .map(BillingSegmentResult::getPromotionUsages)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        return BillingResult.builder()
                .finalAmount(finalAmount)
                .units(units)
                .promotionUsages(promotionUsages)
                .effectiveFrom(request.getBeginTime())
                .effectiveTo(request.getEndTime())
                .build();
    }
}
