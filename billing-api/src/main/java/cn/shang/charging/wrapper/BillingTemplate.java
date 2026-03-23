package cn.shang.charging.wrapper;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;

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
     *
     * @param request 计费请求
     * @return 计费结果
     */
    public BillingResult calculate(BillingRequest request) {
        return billingService.calculate(request);
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
     * @return 计算结果和查询结果
     */
    public CalculationWithQueryResult calculateWithQuery(BillingRequest request, LocalDateTime queryTime) {
        BillingResult calculationResult = billingService.calculate(request);
        BillingResult queryResult = resultViewer.viewAtTime(calculationResult, queryTime);
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
}