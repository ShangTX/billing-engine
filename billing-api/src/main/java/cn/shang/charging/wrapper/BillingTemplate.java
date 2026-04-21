package cn.shang.charging.wrapper;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.billing.pojo.TimeRoundingMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 计费模板 - 便捷 API 封装
 */
public class BillingTemplate {

    private final BillingService billingService;
    private final BillingConfigResolver configResolver;
    private final PromotionSavingsAnalyzer savingsAnalyzer;
    private final BillingResultViewer resultViewer;
    private final PromotionEquivalentCalculator promotionEquivalentCalculator;

    public BillingTemplate(BillingService billingService,
                           BillingConfigResolver configResolver) {
        this.billingService = billingService;
        this.configResolver = configResolver;
        this.savingsAnalyzer = new PromotionSavingsAnalyzer();
        this.resultViewer = new BillingResultViewer();
        this.promotionEquivalentCalculator = new PromotionEquivalentCalculator(billingService);
    }

    /**
     * 执行计费计算
     * <p>
     * 默认使用 CEIL_BEGIN_TRUNCATE_END 模式处理秒数：
     * 开始时间向上取整（增加一分钟，秒数置0），结束时间去掉秒数
     *
     * @param request 计费请求
     * @return 计费结果
     */
    public BillingResult calculate(BillingRequest request) {
        return calculate(request, TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);
    }

    /**
     * 执行计费计算，指定时间取整模式
     *
     * @param request      计费请求
     * @param roundingMode 时间取整模式
     * @return 计费结果
     */
    public BillingResult calculate(BillingRequest request, TimeRoundingMode roundingMode) {
        // 如果请求中已设置取整模式，优先使用请求中的
        TimeRoundingMode mode = request.getTimeRoundingMode() != null
                ? request.getTimeRoundingMode()
                : roundingMode;

        // 应用时间取整
        applyTimeRounding(request, mode);

        return billingService.calculate(request);
    }

    /**
     * 应用时间取整模式
     */
    private void applyTimeRounding(BillingRequest request, TimeRoundingMode mode) {
        if (mode == null || request.getBeginTime() == null || request.getEndTime() == null) {
            return;
        }

        switch (mode) {
            case KEEP_SECONDS:
                // 保留秒数，不做处理
                break;
            case TRUNCATE_BOTH:
                // 开始和结束时间都直接去掉秒数
                request.setBeginTime(truncateSeconds(request.getBeginTime()));
                request.setEndTime(truncateSeconds(request.getEndTime()));
                break;
            case CEIL_BEGIN_TRUNCATE_END:
                // 开始时间向上取整，结束时间去掉秒数
                request.setBeginTime(ceilSeconds(request.getBeginTime()));
                request.setEndTime(truncateSeconds(request.getEndTime()));
                break;
            case TRUNCATE_BEGIN_CEIL_END:
                // 开始时间去掉秒数，结束时间向上取整
                request.setBeginTime(truncateSeconds(request.getBeginTime()));
                request.setEndTime(ceilSeconds(request.getEndTime()));
                break;
        }

        // 同步处理 calcEndTime
        if (request.getCalcEndTime() != null) {
            switch (mode) {
                case KEEP_SECONDS:
                    break;
                case TRUNCATE_BOTH:
                case CEIL_BEGIN_TRUNCATE_END:
                    request.setCalcEndTime(truncateSeconds(request.getCalcEndTime()));
                    break;
                case TRUNCATE_BEGIN_CEIL_END:
                    request.setCalcEndTime(ceilSeconds(request.getCalcEndTime()));
                    break;
            }
        }
    }

    /**
     * 去掉秒数（秒数置0）
     */
    private LocalDateTime truncateSeconds(LocalDateTime time) {
        if (time.getSecond() == 0 && time.getNano() == 0) {
            return time;
        }
        return time.withSecond(0).withNano(0);
    }

    /**
     * 向上取整（秒数大于0时，增加一分钟，秒数置0）
     */
    private LocalDateTime ceilSeconds(LocalDateTime time) {
        if (time.getSecond() == 0 && time.getNano() == 0) {
            return time;
        }
        return time.plusMinutes(1).withSecond(0).withNano(0);
    }

    /**
     * 计算优惠等效金额
     *
     * @param result 计费结果
     * @return promotionId → 节省金额
     */
    public Map<String, BigDecimal> calculatePromotionSavings(BillingResult result) {
        return savingsAnalyzer.analyze(result);
    }

    /**
     * 获取配置解析器
     */
    public BillingConfigResolver getConfigResolver() {
        return configResolver;
    }

    /**
     * 计算并返回两种结果
     *
     * @param request   计费请求
     * @param queryTime 查询时间点
     * @return 计算结果和查询摘要
     * @throws IllegalArgumentException 当 queryTime <= units[0].beginTime
     */
    public CalculationWithQueryResult calculateWithQuery(BillingRequest request, LocalDateTime queryTime) {
        BillingResult calculationResult = billingService.calculate(request);
        QuerySummary queryResult = resultViewer.createQuerySummary(calculationResult, queryTime);
        BillingUnit hitUnit = extractHitUnit(calculationResult, queryResult);
        if (isSimplifiedUnit(hitUnit)) {
            // Simplified units intentionally drop intra-unit detail.
            // Re-run once with simplification disabled before answering an exact query.
            BillingRequest detailedRequest = copyRequest(request);
            detailedRequest.setDisableSimplification(true);
            calculationResult = billingService.calculate(detailedRequest);
            queryResult = resultViewer.createQuerySummary(calculationResult, queryTime);
        }
        return new CalculationWithQueryResult(calculationResult, queryResult);
    }

    /**
     * 计算优惠等效金额
     *
     * @param request 计费请求
     * @return 优惠ID → 等效金额
     */
    public Map<String, BigDecimal> calculatePromotionEquivalents(BillingRequest request) {
        return promotionEquivalentCalculator.calculate(request);
    }

    private BillingUnit extractHitUnit(BillingResult calculationResult, QuerySummary queryResult) {
        if (calculationResult == null || calculationResult.getUnits() == null || queryResult == null) {
            return null;
        }
        int unitIndex = queryResult.getUnitIndex();
        if (unitIndex < 0 || unitIndex >= calculationResult.getUnits().size()) {
            return null;
        }
        return calculationResult.getUnits().get(unitIndex);
    }

    private boolean isSimplifiedUnit(BillingUnit unit) {
        if (unit == null || !(unit.getRuleData() instanceof Map<?, ?> ruleData)) {
            return false;
        }
        return Boolean.TRUE.equals(ruleData.get("isSimplified"));
    }

    private BillingRequest copyRequest(BillingRequest request) {
        // `BillingRequest` is still a mutable POJO, so exact-query fallback needs an explicit field copy.
        BillingRequest copied = new BillingRequest();
        copied.setId(request.getId());
        copied.setBeginTime(request.getBeginTime());
        copied.setEndTime(request.getEndTime());
        copied.setCalcEndTime(request.getCalcEndTime());
        copied.setExternalPromotions(request.getExternalPromotions());
        copied.setSegmentCalculationMode(request.getSegmentCalculationMode());
        copied.setSchemeId(request.getSchemeId());
        copied.setSchemeChanges(request.getSchemeChanges());
        copied.setPreviousCarryOver(request.getPreviousCarryOver());
        copied.setTimeRoundingMode(request.getTimeRoundingMode());
        copied.setContext(request.getContext());
        copied.setDisableSimplification(request.getDisableSimplification());
        return copied;
    }
}
