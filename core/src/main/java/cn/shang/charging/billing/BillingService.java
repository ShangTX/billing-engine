package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.promotion.ExternalPromotionPool;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计费核心调度服务。
 * <p>
 * 计费管道入口，编排完整计费流程：
 * <pre>
 * 分段构建 → 配置解析 → 优惠聚合 → 规则计算 → 结果汇总 → 等效金额（按需）
 * </pre>
 * 提供三种调用方式：
 * <ul>
 *   <li>{@link #calculate} - 一步完成计费（内部自动构建分段上下文）</li>
 *   <li>{@link #prepareContexts} + {@link #calculateWithContexts} - 两步调用，
 *       支持在上下文不变的情况下重算（用于等效金额消去法等场景）</li>
 * </ul>
 */
public class BillingService {

    /** 方案分段构建器：按方案变更时间切割分段 */
    private final SegmentBuilder segmentBuilder;
    /** 计费配置解析器：调用方实现，按方案ID解析规则配置 */
    private final BillingConfigResolver billingConfigResolver;
    /** 优惠计算引擎：聚合免费时段、免费分钟、金额减免、折扣 */
    private final PromotionEngine promotionEngine;
    /** 规则计费计算器：根据规则类型分派到对应 BillingRule 执行 */
    private final BillingCalculator billingCalculator;
    /** 结果汇总器：合并分段结果、跨段 compact 合并、计算最终金额 */
    private final ResultAssembler resultAssembler;

    // 等效金额计算器（懒加载，避免构造函数循环依赖；TODO-20260706-003）
    private PromotionEquivalentCalculator equivalentCalculator;

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
     * 获取等效金额计算器（懒加载，TODO-20260706-003）。
     * <p>
     * {@link PromotionEquivalentCalculator} 构造需要 {@link BillingService}（用于 prepareContexts /
     * calculateWithContexts），故懒加载避免构造函数循环依赖。
     */
    private PromotionEquivalentCalculator getEquivalentCalculator() {
        if (equivalentCalculator == null) {
            equivalentCalculator = new PromotionEquivalentCalculator(this);
        }
        return equivalentCalculator;
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

            // 2.2 解析规则快照 + 聚合（方案已确定），与 prepareContexts 共用 resolveSegmentContext
            SegmentContext segmentContext = resolveSegmentContext(request, segment, window, externalPool);

            // 2.3 执行计费
            BillingSegmentResult segmentResult = billingCalculator.calculate(
                    segmentContext.getBillingContext(),
                    segmentContext.getPromotionAggregate());

            segmentResults.add(segmentResult);

            // 回写扣减外部优惠剩余量（FREE_MINUTES/FREE_RANGE 跨段共享，TODO-20260702-003）
            externalPool.writeBack(segmentResult.getPromotionUsages());
        }
        // 3. 汇总结果（金额、满减、封顶等）
        BillingResult result = resultAssembler.assemble(request, segmentResults);

        // 4. 等效金额按需计算（TODO-20260706-003）：spec != null 时调用消去法，回填 usage + total
        if (request.getEquivalentAmountSpec() != null) {
            backfillEquivalentAmounts(result, request);
        }

        return result;
    }

    /**
     * 用消去法计算等效金额并回填到 result（TODO-20260706-003）。
     * <p>
     * 覆盖策略侧"原价之和"近似值；totalEquivalentAmount = 命中等效金额之和。
     * spec 过滤后未命中的 usage.equivalentAmount 保持策略侧值（不被覆盖）。
     */
    private void backfillEquivalentAmounts(BillingResult result, BillingRequest request) {
        Map<String, BigDecimal> equivalents = getEquivalentCalculator().calculate(request);
        if (result.getPromotionUsages() != null) {
            for (PromotionUsage usage : result.getPromotionUsages()) {
                BigDecimal eq = equivalents.get(usage.getPromotionId());
                if (eq != null) {
                    usage.setEquivalentAmount(eq);
                }
            }
        }
        result.setTotalEquivalentAmount(
                PromotionEquivalentCalculator.sumEquivalents(result, request.getEquivalentAmountSpec()));
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

        // 外部优惠跨段共享池（TODO-20260706-002 阶段6）：与 calculate 共用 resolveSegmentContext，
        // 消除 calculationMode / externalPool 解析不同步。
        // 注：prepareContexts 不执行计费，无法 writeBack 推进跨段剩余量；多段跨段去重由
        // calculateWithContexts 重算时承担（单段场景已与 calculate 一致）。
        ExternalPromotionPool externalPool = new ExternalPromotionPool();
        externalPool.init(request.getExternalPromotions());

        for (BillingSegment segment : segments) {
            CalculationWindow window = CalculationWindowFactory.create(
                request.getBeginTime(),
                segment,
                request.getSegmentCalculationMode()
            );

            SegmentContext segmentContext = resolveSegmentContext(request, segment, window, externalPool);
            // 共享池引用挂到 context 上，供 calculateWithContexts 每次重算前 reset
            segmentContext.setExternalPool(externalPool);
            // 源外部优惠列表（供 calculateWithContexts reset 用；TODO-20260706-003）
            segmentContext.setSourceExternalPromotions(request.getExternalPromotions());
            contexts.add(segmentContext);
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
        // externalPool 重置点（TODO-20260706-002 阶段6 + TODO-20260706-003 源层排除）：
        // 用 context 的 sourceExternalPromotions（cloneAndExclude 过滤后的源列表）reset，
        // 使源层排除的外部优惠在重放 evaluate 时不进入池。回退到 request.getExternalPromotions()
        // 兼容未设 sourceExternalPromotions 的旧路径。
        if (contexts != null && !contexts.isEmpty()) {
            ExternalPromotionPool pool = contexts.get(0).getExternalPool();
            if (pool != null) {
                List<PromotionGrant> sourceExternal = contexts.get(0).getSourceExternalPromotions();
                if (sourceExternal == null) {
                    sourceExternal = request.getExternalPromotions();
                }
                pool.reset(sourceExternal);
            }
        }

        List<BillingSegmentResult> segmentResults = new ArrayList<>();

        for (SegmentContext ctx : contexts) {
            // 重放 evaluate：每段从 externalPool 取 remaining()，evaluate 后 writeBack 推进跨段剩余量
            ExternalPromotionPool pool = ctx.getExternalPool();
            List<PromotionGrant> segmentExternal = pool != null
                    ? pool.remaining()
                    : (ctx.getBillingContext().getExternalPromotions() != null
                        ? ctx.getBillingContext().getExternalPromotions() : List.of());

            BillingContext refreshedContext = ctx.getBillingContext().toBuilder()
                    .externalPromotions(segmentExternal)
                    .build();

            PromotionAggregate promotionAggregate = promotionEngine.evaluate(refreshedContext);

            BillingSegmentResult segmentResult = billingCalculator.calculate(
                refreshedContext,
                promotionAggregate
            );
            segmentResults.add(segmentResult);

            if (pool != null) {
                pool.writeBack(segmentResult.getPromotionUsages());
            }
        }

        return resultAssembler.assemble(request, segmentResults);
    }

    /**
     * 解析分段上下文（TODO-20260706-002 阶段6，决策 E）。
     * <p>
     * calculate 与 prepareContexts 共用本方法，统一解析：
     * <ul>
     *   <li>calculationMode（resolveCalculationMode）</li>
     *   <li>chargingRule（resolveChargingRule）</li>
     *   <li>promotionRules（resolvePromotionRules）</li>
     *   <li>externalPool（跨段共享优惠池，取 remaining() 注入 BillingContext）</li>
     * </ul>
     * 消除两路径解析不同步，保证 PromotionEquivalentCalculator 消去法基于与 calculate
     * 完全一致的解析，等效金额准确。
     *
     * @param request      计费请求
     * @param segment      分段
     * @param window       计算窗口
     * @param externalPool 外部优惠跨段共享池（取 remaining() 注入本段）
     * @return 分段上下文（含 BillingContext 与优惠聚合）
     */
    private SegmentContext resolveSegmentContext(BillingRequest request,
                                                 BillingSegment segment,
                                                 CalculationWindow window,
                                                 ExternalPromotionPool externalPool) {
        Map<String, Object> contextParam = request.getContext();

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
                .externalPromotions(externalPool.remaining())
                .calculationMode(calculationMode)
                .disableSimplification(request.getDisableSimplification())
                .billingConfigResolver(billingConfigResolver)
                .build();

        PromotionAggregate promotionAggregate = promotionEngine.evaluate(billingContext);

        return SegmentContext.builder()
                .segmentId(segment.getId())
                .billingContext(billingContext)
                .promotionAggregate(promotionAggregate)
                .externalPool(externalPool)
                .build();
    }

}
