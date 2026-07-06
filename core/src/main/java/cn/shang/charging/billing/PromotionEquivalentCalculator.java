package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.EquivalentAmountSpec;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.SegmentContext;
import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 优惠等效金额计算器（TODO-20260706-003 从 billing-api 迁入 core）。
 * <p>
 * 使用消去法精确计算每个优惠的等效金额：依次排除某个优惠后重算，差额即为该优惠的等效金额。
 * <p>
 * 多段 + 外部优惠修复：{@link #cloneAndExclude} 在源层（externalPromotions / promotionRules 按 id）
 * 排除，配合 {@link BillingService#calculateWithContexts} 重放 {@code PromotionEngine.evaluate}
 * （externalPool reset + 每段 evaluate + writeBack 推进），跨段去重在每次消去迭代中重放。
 * <p>
 * 按需计算：{@link BillingRequest#getEquivalentAmountSpec()} 控制：
 * <ul>
 *   <li>{@code null} → 不计算，返回空 Map</li>
 *   <li>非 {@code null} → 按 promotionIds + types 过滤，仅对命中优惠做消去法</li>
 * </ul>
 */
public class PromotionEquivalentCalculator {

    private final BillingService billingService;

    public PromotionEquivalentCalculator(BillingService billingService) {
        this.billingService = billingService;
    }

    /**
     * 计算各优惠的等效金额。
     *
     * @param request 计费请求（读 equivalentAmountSpec 过滤）
     * @return 优惠ID → 等效金额（spec==null 时返回空 Map）
     */
    public Map<String, BigDecimal> calculate(BillingRequest request) {
        Map<String, BigDecimal> equivalents = new LinkedHashMap<>();

        EquivalentAmountSpec spec = request.getEquivalentAmountSpec();

        // 1. 准备分段上下文（只执行一次）
        List<SegmentContext> contexts = billingService.prepareContexts(request);

        // 2. 计算全优惠基准结果
        BillingResult baseline = billingService.calculateWithContexts(contexts, request);
        BigDecimal baselineAmount = baseline.getFinalAmount() != null
            ? baseline.getFinalAmount()
            : BigDecimal.ZERO;

        // 3. 提取所有优惠时间段，按 spec 过滤后按开始时间排序
        List<FreeTimeRange> sortedRanges = extractAndSortRanges(baseline, spec);

        // 如果没有优惠（或 spec 过滤后无命中），直接返回空 Map
        if (sortedRanges.isEmpty()) {
            return equivalents;
        }

        // 4. 依次消去优惠
        Set<String> excludedIds = new HashSet<>();
        BigDecimal previousAmount = baselineAmount;

        for (FreeTimeRange range : sortedRanges) {
            excludedIds.add(range.getId());

            // 源层排除：externalPromotions + promotionRules 按 id 过滤
            List<SegmentContext> modifiedContexts = cloneAndExclude(contexts, excludedIds);

            // 计算（calculateWithContexts 重放 evaluate + writeBack）
            BillingResult result = billingService.calculateWithContexts(modifiedContexts, request);
            BigDecimal currentAmount = result.getFinalAmount() != null
                ? result.getFinalAmount()
                : BigDecimal.ZERO;

            // 等效金额 = 新费用 - 旧费用（消去后费用上升 = 该优惠的等效金额）
            BigDecimal equivalent = currentAmount.subtract(previousAmount);
            if (equivalent.compareTo(BigDecimal.ZERO) < 0) {
                equivalent = BigDecimal.ZERO;
            }

            equivalents.put(range.getId(), equivalent);
            previousAmount = currentAmount;
        }

        return equivalents;
    }

    /**
     * 提取所有优惠时间段并按开始时间排序，按 spec 过滤（id + 类型）。
     * 包括 FREE_RANGE / FREE_MINUTES / SMART_FREE_MINUTES 转换后的时间段。
     */
    private List<FreeTimeRange> extractAndSortRanges(BillingResult result, EquivalentAmountSpec spec) {
        if (result.getPromotionUsages() == null) {
            return List.of();
        }

        return result.getPromotionUsages().stream()
            .filter(u -> u.getType() == BConstants.PromotionType.FREE_RANGE
                      || u.getType() == BConstants.PromotionType.FREE_MINUTES
                      || u.getType() == BConstants.PromotionType.SMART_FREE_MINUTES)
            .filter(u -> u.getUsedFrom() != null && u.getUsedTo() != null)
            .filter(u -> matchesSpec(u, spec))
            .map(u -> FreeTimeRange.builder()
                .id(u.getPromotionId())
                .beginTime(u.getUsedFrom())
                .endTime(u.getUsedTo())
                .promotionType(u.getType())
                .build())
            .sorted(Comparator.comparing(FreeTimeRange::getBeginTime))
            .toList();
    }

    /**
     * 判断 usage 是否命中 spec（promotionIds + types 交集）。spec==null 视为不限（全部命中）。
     */
    private boolean matchesSpec(PromotionUsage u, EquivalentAmountSpec spec) {
        if (spec == null) {
            return true;
        }
        if (spec.getPromotionIds() != null && !spec.getPromotionIds().isEmpty()
                && !spec.getPromotionIds().contains(u.getPromotionId())) {
            return false;
        }
        if (spec.getTypes() != null && !spec.getTypes().isEmpty()
                && !spec.getTypes().contains(u.getType())) {
            return false;
        }
        return true;
    }

    /**
     * 克隆分段上下文并在源层排除指定优惠（TODO-20260706-003）。
     * <p>
     * 不再在聚合后按 ID 过滤免费段（旧 {@code PromotionAggregateUtil.exclude} 路径），
     * 改为在源层排除：
     * <ul>
     *   <li>外部优惠：过滤 {@code request.externalPromotions}，存入每个 context 的
     *       {@code sourceExternalPromotions}；calculateWithContexts reset externalPool 时生效</li>
     *   <li>方案内规则：过滤 {@code billingContext.promotionRules}，重放 evaluate 时不进入聚合</li>
     * </ul>
     * externalPool 仍共享同一实例（每次迭代 reset 同一实例，用过滤后的源列表）。
     */
    private List<SegmentContext> cloneAndExclude(List<SegmentContext> contexts, Set<String> excludedIds) {
        return contexts.stream()
            .map(ctx -> {
                // billingContext 为 null 时无法 toBuilder（测试桩场景），原样透传
                if (ctx.getBillingContext() == null) {
                    return ctx;
                }
                List<PromotionRuleConfig> filteredRules = ctx.getBillingContext().getPromotionRules() == null
                    ? null
                    : ctx.getBillingContext().getPromotionRules().stream()
                        .filter(r -> r.getId() == null || !excludedIds.contains(r.getId()))
                        .collect(Collectors.toList());

                return SegmentContext.builder()
                    .segmentId(ctx.getSegmentId())
                    .billingContext(ctx.getBillingContext().toBuilder()
                        .promotionRules(filteredRules)
                        .build())
                    .promotionAggregate(ctx.getPromotionAggregate())
                    // 共享同一 externalPool 引用：calculateWithContexts 每次重算前 reset 同一实例
                    .externalPool(ctx.getExternalPool())
                    // 源层排除：过滤后的外部优惠列表，reset externalPool 时生效
                    .sourceExternalPromotions(filterSourceExternal(ctx.getSourceExternalPromotions(), excludedIds))
                    .build();
            })
            .toList();
    }

    /**
     * 过滤源外部优惠列表（按 id 排除）。null 输入返回 null。
     */
    private static List<PromotionGrant> filterSourceExternal(List<PromotionGrant> source, Set<String> excludedIds) {
        if (source == null) {
            return null;
        }
        return source.stream()
            .filter(g -> g.getId() == null || !excludedIds.contains(g.getId()))
            .collect(Collectors.toList());
    }

    /**
     * 收集 result 中命中 spec 的等效金额之和（用于回填 totalEquivalentAmount）。
     */
    public static BigDecimal sumEquivalents(BillingResult result, EquivalentAmountSpec spec) {
        if (result == null || result.getPromotionUsages() == null) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> values = new ArrayList<>();
        for (PromotionUsage u : result.getPromotionUsages()) {
            if (u.getEquivalentAmount() == null) {
                continue;
            }
            if (spec != null) {
                if (spec.getPromotionIds() != null && !spec.getPromotionIds().isEmpty()
                        && !spec.getPromotionIds().contains(u.getPromotionId())) {
                    continue;
                }
                if (spec.getTypes() != null && !spec.getTypes().isEmpty()
                        && !spec.getTypes().contains(u.getType())) {
                    continue;
                }
            }
            values.add(u.getEquivalentAmount());
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
