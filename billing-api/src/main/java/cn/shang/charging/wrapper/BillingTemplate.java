package cn.shang.charging.wrapper;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.PromotionEquivalentCalculator;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.TimeRoundingMode;
import cn.shang.charging.promotion.pojo.PromotionGrant;

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
    private final PromotionEquivalentCalculator promotionEquivalentCalculator;

    public BillingTemplate(BillingService billingService,
                           BillingConfigResolver configResolver) {
        this.billingService = billingService;
        this.configResolver = configResolver;
        this.savingsAnalyzer = new PromotionSavingsAnalyzer();
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
        TimeRoundingMode mode = request.getTimeRoundingMode() != null
                ? request.getTimeRoundingMode()
                : roundingMode;

        applyTimeRounding(request, mode);

        return billingService.calculate(request);
    }

    /**
     * 应用时间取整：统一向下取整（秒数置0）。
     * <p>
     * 引擎按分钟精度计算，所有时间（计费时间 + 外部优惠时间）在入口统一向下取整。
     * 忽略 {@code mode}（保留参数向后兼容）；外部业务策略（如 beginTime 向上取整）请通过
     * {@link TimeRounding} 自行预处理后再传入。
     * <p>
     * 向下取整不会产生 {@code beginTime > endTime} 的倒置（最多相等 → 计费 0），无需守卫。
     */
    private void applyTimeRounding(BillingRequest request, TimeRoundingMode mode) {
        if (request.getBeginTime() != null) {
            request.setBeginTime(TimeRounding.truncate(request.getBeginTime()));
        }
        if (request.getEndTime() != null) {
            request.setEndTime(TimeRounding.truncate(request.getEndTime()));
        }
        if (request.getCalcEndTime() != null) {
            request.setCalcEndTime(TimeRounding.truncate(request.getCalcEndTime()));
        }
        roundExternalPromotions(request);
    }

    /**
     * 对外部优惠（FREE_RANGE）的时间统一向下取整。
     * 非 FREE_RANGE 类型（FREE_MINUTES/SMART_FREE_MINUTES）无时间段，不处理。
     */
    private void roundExternalPromotions(BillingRequest request) {
        if (request.getExternalPromotions() == null) {
            return;
        }
        for (PromotionGrant grant : request.getExternalPromotions()) {
            if (grant.getType() != BConstants.PromotionType.FREE_RANGE) {
                continue;
            }
            if (grant.getBeginTime() != null) {
                grant.setBeginTime(TimeRounding.truncate(grant.getBeginTime()));
            }
            if (grant.getEndTime() != null) {
                grant.setEndTime(TimeRounding.truncate(grant.getEndTime()));
            }
        }
    }

    /**
     * 计算优惠节省金额
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
     * 计算优惠等效金额
     *
     * @param request 计费请求
     * @return 优惠ID → 等效金额
     */
    public Map<String, BigDecimal> calculatePromotionEquivalents(BillingRequest request) {
        return promotionEquivalentCalculator.calculate(request);
    }
}
