package cn.shang.settlement;

import cn.shang.billing.pojo.BillingRequest;
import cn.shang.billing.pojo.BillingResult;
import cn.shang.billing.pojo.BillingSegmentResult;

import java.util.List;

public class ResultAssembler {

    /**
     * 汇总结果
     */
    public BillingResult assemble(BillingRequest request,
                                  List<BillingSegmentResult> segmentResultList) {
        return new BillingResult();
    }
}
