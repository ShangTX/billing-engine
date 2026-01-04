package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.settlement.ResultAssembler;

import java.util.ArrayList;
import java.util.List;

public class BillingService {

    private final SegmentBuilder segmentBuilder;
    private final RuleResolver ruleResolver;
    private final PromotionEngine promotionEngine;
    private final BillingCalculator billingCalculator;
    private final ResultAssembler resultAssembler;

    public BillingService(
            SegmentBuilder segmentBuilder,
            RuleResolver ruleResolver,
            PromotionEngine promotionEngine,
            BillingCalculator billingCalculator,
            ResultAssembler resultAssembler) {
        this.segmentBuilder = segmentBuilder;
        this.ruleResolver = ruleResolver;
        this.promotionEngine = promotionEngine;
        this.billingCalculator = billingCalculator;
        this.resultAssembler = resultAssembler;
    }

    /**
     * 计费计算
     *
     * @param request 计费参数
     */
    public BillingResult calculate(BillingRequest request) {

        // 1. 构建方案分段（只负责方案切换）
        List<BillingSegment> segments = segmentBuilder.buildSegments(request);

        // 各分段计费结果
        List<BillingSegmentResult> segmentResults = new ArrayList<>();

        // 2.逐段计算

        // 2. 逐段计算
        for (BillingSegment segment : segments) {

            // 2.1 构建计算窗口（支持两种分段模式）
            CalculationWindow window = CalculationWindowFactory.create(
                            request.getBeginTime(),
                            segment,
                            request.getSegmentCalculationMode()
                    );

            // 2.2 解析规则快照（方案已确定）
            RuleSnapshot chargingRule = ruleResolver.resolveChargingRule(
                            segment.getSchemeId(),
                            window.getCalculationBegin(),
                            window.getCalculationEnd());
            // 解析优惠规则
            List<PromotionRuleSnapshot> promotionRules =
                    ruleResolver.resolvePromotionRules(
                            segment.getSchemeId(),
                            window.getCalculationBegin(),
                            window.getCalculationEnd());

            // 2.3 构建 BillingContext（只读）
            BillingContext context = BillingContext.builder()
                    .id(request.getId())
                    .beginTime(request.getBeginTime())
                    .segment(segment)
                    .window(window)
                    .chargingRule(chargingRule)
                    .promotionRules(promotionRules)
                    .externalPromotions(request.getExternalPromotions())
                    .billingMode(request.getBillingMode())
                    .build();

            // 2.4 执行优惠聚合
            PromotionAggregate promotionAggregate = promotionEngine.evaluate(context);

            // 2.5 执行计费
            BillingSegmentResult segmentResult = billingCalculator.calculate(context, promotionAggregate);

            segmentResults.add(segmentResult);
        }
        // 3. 汇总结果（金额、满减、封顶等）
        return resultAssembler.assemble(
                request,
                segmentResults
        );
    }

}
