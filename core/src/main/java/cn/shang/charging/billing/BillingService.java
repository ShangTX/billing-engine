package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.settlement.ResultAssembler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BillingService {

    private final SegmentBuilder segmentBuilder;
    private final BillingConfigResolver billingConfigResolver;
    private final PromotionEngine promotionEngine;
    private final BillingCalculator billingCalculator;
    private final ResultAssembler resultAssembler;

    public BillingService(
            SegmentBuilder segmentBuilder,
            BillingConfigResolver billingConfigResolver,
            PromotionEngine promotionEngine,
            BillingCalculator billingCalculator,
            ResultAssembler resultAssembler) {
        this.segmentBuilder = segmentBuilder;
        this.billingConfigResolver = billingConfigResolver;
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

        // 确定 ContinueMode
        boolean isContinueMode = request.getPreviousCarryOver() != null;
        BillingCarryOver carryOver = request.getPreviousCarryOver();

        // CONTINUE 模式：计算实际起点
        LocalDateTime actualBeginTime = isContinueMode && carryOver.getCalculatedUpTo() != null
                ? carryOver.getCalculatedUpTo()
                : request.getBeginTime();

        // 边界检查：如果 actualBeginTime >= endTime，直接返回空结果
        if (actualBeginTime.isAfter(request.getEndTime()) || actualBeginTime.equals(request.getEndTime())) {
            return BillingResult.builder()
                    .units(List.of())
                    .promotionUsages(List.of())
                    .finalAmount(java.math.BigDecimal.ZERO)
                    .calculationEndTime(carryOver != null ? carryOver.getCalculatedUpTo() : request.getBeginTime())
                    .carryOver(carryOver)
                    .build();
        }

        // 1. 构建方案分段（只负责方案切换）
        List<BillingSegment> segments = segmentBuilder.buildSegments(request);

        // 各分段计费结果
        List<BillingSegmentResult> segmentResults = new ArrayList<>();

        // 2. 逐段计算
        for (BillingSegment segment : segments) {

            // 2.1 构建计算窗口（支持两种分段模式）
            CalculationWindow window = CalculationWindowFactory.create(
                    actualBeginTime,  // 使用调整后的起点
                    segment,
                    request.getSegmentCalculationMode()
            );

            // CONTINUE 模式：调整计算窗口起点
            if (isContinueMode) {
                // 确保计算窗口的起点不早于 actualBeginTime
                if (window.getCalculationBegin().isBefore(actualBeginTime)) {
                    window.setCalculationBegin(actualBeginTime);
                }
            }

            // 2.2 解析规则快照（方案已确定）
            RuleConfig chargingRule = billingConfigResolver.resolveChargingRule(
                    segment.getSchemeId(),
                    window.getCalculationBegin(),
                    window.getCalculationEnd());

            // 解析优惠规则
            List<PromotionRuleConfig> promotionRules =
                    billingConfigResolver.resolvePromotionRules(
                            segment.getSchemeId(),
                            window.getCalculationBegin(),
                            window.getCalculationEnd());

            // 解析计费模式
            BConstants.BillingMode billingMode = billingConfigResolver.resolveBillingMode(segment.getSchemeId());

            // 2.3 恢复规则状态（CONTINUE 模式）
            Map<String, Object> ruleState = null;
            PromotionCarryOver promotionCarryOver = null;
            if (isContinueMode && carryOver.getSegments() != null) {
                SegmentCarryOver segmentCarryOver = carryOver.getSegments().get(segment.getId());
                if (segmentCarryOver != null) {
                    ruleState = segmentCarryOver.getRuleState();
                    promotionCarryOver = segmentCarryOver.getPromotionState();
                }
            }

            // 2.4 构建 BillingContext（只读）
            BillingContext context = BillingContext.builder()
                    .id(request.getId())
                    .beginTime(request.getBeginTime())
                    .endTime(request.getEndTime())
                    .segment(segment)
                    .window(window)
                    .chargingRule(chargingRule)
                    .promotionRules(promotionRules)
                    .externalPromotions(request.getExternalPromotions())
                    .billingMode(billingMode)
                    .continueMode(isContinueMode ? BConstants.ContinueMode.CONTINUE : BConstants.ContinueMode.FROM_SCRATCH)
                    .ruleState(ruleState)
                    .promotionCarryOver(promotionCarryOver)
                    .build();

            // 2.5 执行优惠聚合
            PromotionAggregate promotionAggregate = promotionEngine.evaluate(context);

            // 2.6 执行计费
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