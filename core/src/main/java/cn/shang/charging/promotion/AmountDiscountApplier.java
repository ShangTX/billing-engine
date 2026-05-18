package cn.shang.charging.promotion;

import cn.shang.charging.billing.pojo.BillingSegmentResult;
import cn.shang.charging.promotion.pojo.PromotionAggregate;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额折扣优惠应用器
 * <p>
 * 实现优惠叠加逻辑：
 * 1. 先折扣后减免
 * 2. 多个 AMOUNT 总和扣除
 * 3. 多个 DISCOUNT 取最优折扣（min discountRate）
 * <p>
 * 最终金额下限为 0。
 */
public final class AmountDiscountApplier {

    /**
     * 应用于 BillingSegmentResult
     * <p>
     * 将 promotionAggregate 中的 AMOUNT/DISCOUNT 优惠应用到计费结果。
     *
     * @param result 计费结果（chargedAmount 为规则计算结果）
     * @return 应用优惠后的结果（originalAmount, discountSavedAmount, amountDiscount, finalAmount）
     */
    public static BillingSegmentResult apply(BillingSegmentResult result) {
        if (result == null || result.getChargedAmount() == null) {
            return result;
        }

        PromotionAggregate promotionAggregate = result.getPromotionAggregate();
        if (promotionAggregate == null || !promotionAggregate.hasAmountDiscount() && !promotionAggregate.hasRateDiscount()) {
            // 无金额折扣优惠，直接设置 finalAmount = chargedAmount
            result.setOriginalAmount(result.getChargedAmount());
            result.setFinalAmount(result.getChargedAmount());
            return result;
        }

        BigDecimal originalAmount = result.getChargedAmount();
        BigDecimal afterDiscount = originalAmount;
        BigDecimal discountSavedAmount = BigDecimal.ZERO;

        // 1. 先应用折扣
        if (promotionAggregate.hasRateDiscount()) {
            BigDecimal bestDiscountRate = promotionAggregate.getBestDiscountRate();
            afterDiscount = originalAmount.multiply(bestDiscountRate).setScale(2, RoundingMode.HALF_UP);
            discountSavedAmount = originalAmount.subtract(afterDiscount);
        }

        // 2. 后应用金额减免
        BigDecimal amountDiscount = BigDecimal.ZERO;
        if (promotionAggregate.hasAmountDiscount()) {
            amountDiscount = promotionAggregate.getTotalAmountDiscount();
            // 减免金额不能超过折扣后金额
            if (amountDiscount.compareTo(afterDiscount) > 0) {
                amountDiscount = afterDiscount;
            }
        }

        // 3. 计算最终金额
        BigDecimal finalAmount = afterDiscount.subtract(amountDiscount).setScale(2, RoundingMode.HALF_UP);
        // 最终金额不能小于 0
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            finalAmount = BigDecimal.ZERO;
        }

        result.setOriginalAmount(originalAmount);
        result.setDiscountSavedAmount(discountSavedAmount);
        result.setAmountDiscount(amountDiscount);
        result.setFinalAmount(finalAmount);

        return result;
    }

    /**
     * 计算应用优惠后的最终金额
     *
     * @param chargedAmount      规则计算的应收金额
     * @param promotionAggregate 优惠聚合结果
     * @return 最终金额
     */
    public static BigDecimal calculateFinalAmount(BigDecimal chargedAmount, PromotionAggregate promotionAggregate) {
        if (chargedAmount == null) {
            return BigDecimal.ZERO;
        }

        if (promotionAggregate == null || !promotionAggregate.hasAmountDiscount() && !promotionAggregate.hasRateDiscount()) {
            return chargedAmount;
        }

        BigDecimal afterDiscount = chargedAmount;

        // 先折扣
        if (promotionAggregate.hasRateDiscount()) {
            afterDiscount = chargedAmount.multiply(promotionAggregate.getBestDiscountRate())
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // 后减免
        if (promotionAggregate.hasAmountDiscount()) {
            BigDecimal amountDiscount = promotionAggregate.getTotalAmountDiscount();
            if (amountDiscount.compareTo(afterDiscount) > 0) {
                amountDiscount = afterDiscount;
            }
            afterDiscount = afterDiscount.subtract(amountDiscount);
        }

        // 下限为 0
        if (afterDiscount.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }

        return afterDiscount.setScale(2, RoundingMode.HALF_UP);
    }
}