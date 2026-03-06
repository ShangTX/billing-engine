package cn.shang.charging.settlement;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.util.Collection;
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

        return BillingResult.builder()
                .units(allUnits)
                .promotionUsages(allUsages)
                .finalAmount(totalAmount)
                .build();
    }
}
