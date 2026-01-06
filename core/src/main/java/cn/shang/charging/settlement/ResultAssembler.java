package cn.shang.charging.settlement;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

public class ResultAssembler {

    /**
     * 汇总结果
     */
    public BillingResult assemble(BillingRequest request,
                                  List<BillingSegmentResult> segmentResultList) {
        return BillingResult.builder()
                .promotionUsages(segmentResultList.stream().map(BillingSegmentResult::getPromotionUsages)
                        .flatMap(Collection::stream).toList())
                .build();
    }
}
