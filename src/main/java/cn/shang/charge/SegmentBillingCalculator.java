package cn.shang.charge;

import org.springframework.stereotype.Service;

/**
 * 规则驱动
 */
@Service
public class SegmentBillingCalculator {

    public SegmentResult calculate(ChargeSegment segment,
                                   BillingContext context) {

        RuleSnapshot rules =
                context.ruleSnapshotFor(segment.schemeId());

        UnitTimeGenerator generator =
                UnitTimeGeneratorFactory.from(rules);

        List<UnitTime> units =
                generator.generate(segment);

        List<BillingUnitDetail> details =
                applyPricing(units, rules);

        applyCaps(details, rules);

        return new SegmentResult(details, List.of());
    }

}
