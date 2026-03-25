package cn.shang.charging.wrapper;

import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.SegmentContext;
import cn.shang.charging.promotion.PromotionAggregateUtil;
import cn.shang.charging.promotion.pojo.FreeTimeRange;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 优惠等效金额计算器
 * 使用消去法精确计算每个优惠的等效金额
 */
public class PromotionEquivalentCalculator {

    private final BillingService billingService;

    public PromotionEquivalentCalculator(BillingService billingService) {
        this.billingService = billingService;
    }

    /**
     * 计算各优惠的等效金额
     *
     * @param request 计费请求
     * @return 优惠ID → 等效金额
     */
    public Map<String, BigDecimal> calculate(BillingRequest request) {
        Map<String, BigDecimal> equivalents = new LinkedHashMap<>();

        // 1. 准备分段上下文（只执行一次）
        List<SegmentContext> contexts = billingService.prepareContexts(request);

        // 2. 计算全优惠基准结果
        BillingResult baseline = billingService.calculateWithContexts(contexts, request);
        BigDecimal baselineAmount = baseline.getFinalAmount() != null
            ? baseline.getFinalAmount()
            : BigDecimal.ZERO;

        // 3. 提取所有优惠时间段，按开始时间排序
        List<FreeTimeRange> sortedRanges = extractAndSortRanges(baseline);

        // 如果没有优惠，直接返回空 Map
        if (sortedRanges.isEmpty()) {
            return equivalents;
        }

        // 4. 依次消去优惠
        Set<String> excludedIds = new HashSet<>();
        BigDecimal previousAmount = baselineAmount;

        for (FreeTimeRange range : sortedRanges) {
            excludedIds.add(range.getId());

            // 克隆并排除优惠
            List<SegmentContext> modifiedContexts = cloneAndExclude(contexts, excludedIds);

            // 计算
            BillingResult result = billingService.calculateWithContexts(modifiedContexts, request);
            BigDecimal currentAmount = result.getFinalAmount() != null
                ? result.getFinalAmount()
                : BigDecimal.ZERO;

            // 等效金额 = 新费用 - 旧费用
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
     * 提取所有优惠时间段并按开始时间排序
     * 包括 FREE_RANGE 和 FREE_MINUTES 转换后的时间段
     */
    private List<FreeTimeRange> extractAndSortRanges(BillingResult result) {
        if (result.getPromotionUsages() == null) {
            return List.of();
        }

        return result.getPromotionUsages().stream()
            .filter(u -> u.getType() == BConstants.PromotionType.FREE_RANGE
                      || u.getType() == BConstants.PromotionType.FREE_MINUTES)
            .filter(u -> u.getUsedFrom() != null && u.getUsedTo() != null)
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
     * 克隆分段上下文并排除指定优惠
     */
    private List<SegmentContext> cloneAndExclude(List<SegmentContext> contexts, Set<String> excludedIds) {
        return contexts.stream()
            .map(ctx -> SegmentContext.builder()
                .segmentId(ctx.getSegmentId())
                .billingContext(ctx.getBillingContext())
                .promotionAggregate(PromotionAggregateUtil.exclude(ctx.getPromotionAggregate(), excludedIds))
                .build())
            .toList();
    }
}