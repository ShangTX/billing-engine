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
     * 应用时间取整（所有时间对齐到分钟）。
     * <p>
     * 三步：
     * <ol>
     *   <li>计费时间（beginTime/endTime/calcEndTime）按 {@code mode} 取整（现有逻辑，默认
     *       CEIL_BEGIN_TRUNCATE_END：计费尽量短）</li>
     *   <li>-1 分钟守卫：取整后 {@code beginTime >= endTime} 时调到一致（计费 0）。场景：
     *       ceil(begin) 超过 truncate(end)，如 begin=10:31:30→10:32, end=10:31:50→10:31</li>
     *   <li>优惠时间（externalPromotions 的 FREE_RANGE）按「优惠尽量长」取整：begin 向下
     *       （truncate）、end 向上（ceil）。独立于 {@code mode}，始终对齐到分钟，避免与计费
     *       时间不对齐产生 0 分钟段</li>
     * </ol>
     */
    private void applyTimeRounding(BillingRequest request, TimeRoundingMode mode) {
        // 1. 计费时间取整（按 mode）
        if (mode != null && request.getBeginTime() != null && request.getEndTime() != null) {
            switch (mode) {
                case KEEP_SECONDS:
                    break;
                case TRUNCATE_BOTH:
                    request.setBeginTime(truncateSeconds(request.getBeginTime()));
                    request.setEndTime(truncateSeconds(request.getEndTime()));
                    break;
                case CEIL_BEGIN_TRUNCATE_END:
                    request.setBeginTime(ceilSeconds(request.getBeginTime()));
                    request.setEndTime(truncateSeconds(request.getEndTime()));
                    break;
                case TRUNCATE_BEGIN_CEIL_END:
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

        // 2. -1 分钟守卫：取整后 beginTime >= endTime 时调到一致（计费 0）
        if (request.getBeginTime() != null && request.getEndTime() != null
                && !request.getBeginTime().isBefore(request.getEndTime())) {
            request.setBeginTime(request.getEndTime());
        }

        // 3. 优惠时间取整：优惠尽量长（begin 向下 truncate, end 向上 ceil）
        roundExternalPromotions(request);
    }

    /**
     * 对外部优惠（FREE_RANGE）的时间按「优惠尽量长」取整：begin 向下、end 向上。
     * 非 FREE_RANGE 类型（FREE_MINUTES/SMART_FREE_MINUTES/AMOUNT/DISCOUNT）无时间段，不处理。
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
                grant.setBeginTime(truncateSeconds(grant.getBeginTime()));
            }
            if (grant.getEndTime() != null) {
                grant.setEndTime(ceilSeconds(grant.getEndTime()));
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
