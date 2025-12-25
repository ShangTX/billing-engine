package cn.shang.charge;

import cn.shang.charge.pojo.BillingContext;
import cn.shang.charge.pojo.BillingResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class BillingCalculator {

    private final SegmentBillingCalculator segmentCalculator;

    public BillingCalculator(SegmentBillingCalculator segmentCalculator) {
        this.segmentCalculator = segmentCalculator;
    }

    public BillingResult calculate(BillingContext context) {

        List<ChargeSegment> segments =
                BillingSegmentBuilder.build(context);

        List<BillingUnitDetail> allUnits = new ArrayList<>();
        List<PromotionUsage> promotions = new ArrayList<>();

        for (ChargeSegment segment : segments) {
            SegmentResult segmentResult =
                    segmentCalculator.calculate(segment, context);

            allUnits.addAll(segmentResult.units());
            promotions.addAll(segmentResult.promotions());
        }

        return BillingResult.of(allUnits, promotions);
    }

}
