package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.ExternalPromotionPool;
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

        LocalDateTime actualBeginTime = request.getBeginTime();

        // 边界检查：如果 actualBeginTime >= endTime，直接返回空结果
        if (actualBeginTime.isAfter(request.getEndTime()) || actualBeginTime.equals(request.getEndTime())) {
            return BillingResult.builder()
                    .units(List.of())
                    .promotionUsages(List.of())
                    .finalAmount(java.math.BigDecimal.ZERO)
                    .calculationEndTime(request.getBeginTime())
                    .build();
        }

        // 1. 构建方案分段（只负责方案切换）
        List<BillingSegment> segments = segmentBuilder.buildSegments(request);

        // GLOBAL_ORIGIN 半成品守卫（TODO-20260702-001）
        validateGlobalOrigin(request, segments);

        // 外部优惠跨段共享可用量池（TODO-20260702-003）：整笔停车享一次，多分段不重复
        ExternalPromotionPool externalPool = new ExternalPromotionPool();
        externalPool.init(request.getExternalPromotions());

        // 各分段计费结果
        List<BillingSegmentResult> segmentResults = new ArrayList<>();

        // 2. 逐段计算
        for (BillingSegment segment : segments) {

            // 2.1 构建计算窗口
            CalculationWindow window = CalculationWindowFactory.create(
                    actualBeginTime,
                    segment,
                    request.getSegmentCalculationMode()
            );

            // 2.2 解析规则快照（方案已确定）
            Map<String, Object> contextParam = request.getContext();
            RuleConfig chargingRule = billingConfigResolver.resolveChargingRule(
                    segment.getSchemeId(),
                    window.getCalculationBegin(),
                    window.getCalculationEnd(),
                    contextParam);

            // 解析优惠规则
            List<PromotionRuleConfig> promotionRules =
                    billingConfigResolver.resolvePromotionRules(
                            segment.getSchemeId(),
                            window.getCalculationBegin(),
                            window.getCalculationEnd(),
                            contextParam);

            // 解析计算模式
            BConstants.CalculationMode calculationMode = billingConfigResolver.resolveCalculationMode(
                    segment.getSchemeId(), contextParam);

            // 2.3 构建 BillingContext（只读）
            BillingContext context = BillingContext.builder()
                    .id(request.getId())
                    .beginTime(request.getBeginTime())
                    .endTime(request.getEndTime())
                    .segment(segment)
                    .window(window)
                    .chargingRule(chargingRule)
                    .promotionRules(promotionRules)
                    .externalPromotions(externalPool.remaining())
                    .calculationMode(calculationMode)
                    .disableSimplification(request.getDisableSimplification())
                    .billingConfigResolver(billingConfigResolver)
                    .build();

            // 2.4 执行优惠聚合
            PromotionAggregate promotionAggregate = promotionEngine.evaluate(context);

            // 2.5 执行计费
            BillingSegmentResult segmentResult = billingCalculator.calculate(context, promotionAggregate);

            segmentResults.add(segmentResult);

            // 回写扣减外部优惠剩余量（FREE_MINUTES/FREE_RANGE 跨段共享，TODO-20260702-003）
            externalPool.writeBack(segmentResult.getPromotionUsages());
        }
        // 3. 汇总结果（金额、满减、封顶等）
        return resultAssembler.assemble(
                request,
                segmentResults
        );
    }

    /**
     * GLOBAL_ORIGIN 半成品守卫（TODO-20260702-001）。
     * <p>
     * GLOBAL_ORIGIN 窗口截取的减法语义未实现（clipBegin/clipEnd 从未被读取），
     * 多分段下会双重计费。当前仅支持单分段（等价 SEGMENT_LOCAL）；
     * UNIT_BASED 与 GLOBAL_ORIGIN 结构性不兼容（单元对齐 vs 全局起点截取）。
     */
    private void validateGlobalOrigin(BillingRequest request, List<BillingSegment> segments) {
        if (request.getSegmentCalculationMode() != BConstants.SegmentCalculationMode.GLOBAL_ORIGIN) {
            return;
        }
        if (segments.size() > 1) {
            throw new IllegalStateException(
                    "GLOBAL_ORIGIN 窗口截取模式当前为半成品（减法未实现），多分段（当前 "
                            + segments.size() + " 段）下会双重计费；仅支持单分段（等价 SEGMENT_LOCAL）。"
                            + "详见 TODO-20260702-001。");
        }
        Map<String, Object> contextParam = request.getContext();
        for (BillingSegment segment : segments) {
            BConstants.CalculationMode calculationMode = billingConfigResolver.resolveCalculationMode(segment.getSchemeId(), contextParam);
            if (calculationMode == BConstants.CalculationMode.UNIT_BASED) {
                throw new IllegalStateException(
                        "UNIT_BASED 与 GLOBAL_ORIGIN 结构性不兼容：单元对齐语义与全局起点截取冲突；"
                                + "UNIT_BASED 仅支持 SEGMENT_LOCAL。详见 TODO-20260702-001。");
            }
        }
    }

    /**
     * 准备分段上下文
     * 执行分段构建、规则解析、优惠聚合
     *
     * @param request 计费请求
     * @return 分段上下文列表
     */
    public List<SegmentContext> prepareContexts(BillingRequest request) {
        List<SegmentContext> contexts = new ArrayList<>();

        List<BillingSegment> segments = segmentBuilder.buildSegments(request);

        // GLOBAL_ORIGIN 半成品守卫（TODO-20260702-001）
        validateGlobalOrigin(request, segments);

        Map<String, Object> contextParam = request.getContext();

        for (BillingSegment segment : segments) {
            CalculationWindow window = CalculationWindowFactory.create(
                request.getBeginTime(),
                segment,
                request.getSegmentCalculationMode()
            );

            RuleConfig chargingRule = billingConfigResolver.resolveChargingRule(
                segment.getSchemeId(),
                window.getCalculationBegin(),
                window.getCalculationEnd(),
                contextParam);

            List<PromotionRuleConfig> promotionRules = billingConfigResolver.resolvePromotionRules(
                segment.getSchemeId(),
                window.getCalculationBegin(),
                window.getCalculationEnd(),
                contextParam);

            BConstants.CalculationMode calculationMode = billingConfigResolver.resolveCalculationMode(
                segment.getSchemeId(), contextParam);

            BillingContext billingContext = BillingContext.builder()
                .id(request.getId())
                .beginTime(request.getBeginTime())
                .endTime(request.getEndTime())
                .segment(segment)
                .window(window)
                .chargingRule(chargingRule)
                .promotionRules(promotionRules)
                .externalPromotions(request.getExternalPromotions())
                .calculationMode(calculationMode)
                .disableSimplification(request.getDisableSimplification())
                .billingConfigResolver(billingConfigResolver)
                .build();

            PromotionAggregate promotionAggregate = promotionEngine.evaluate(billingContext);

            contexts.add(SegmentContext.builder()
                .segmentId(segment.getId())
                .billingContext(billingContext)
                .promotionAggregate(promotionAggregate)
                .build());
        }

        return contexts;
    }

    /**
     * 用分段上下文计算
     * 只执行计费计算和结果汇总
     *
     * @param contexts 分段上下文列表
     * @param request  原始请求（用于 assemble）
     * @return 计费结果
     */
    public BillingResult calculateWithContexts(List<SegmentContext> contexts, BillingRequest request) {
        List<BillingSegmentResult> segmentResults = new ArrayList<>();

        for (SegmentContext ctx : contexts) {
            BillingSegmentResult segmentResult = billingCalculator.calculate(
                ctx.getBillingContext(),
                ctx.getPromotionAggregate()
            );
            segmentResults.add(segmentResult);
        }

        return resultAssembler.assemble(request, segmentResults);
    }

}
