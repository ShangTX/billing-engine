package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.PromotionCarryOver;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 优惠计算结果
 * <p>
 * 包含免费时段、免费分钟数、金额减免和折扣优惠的组合结果。
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class PromotionAggregate {

    // 最终唯一生效的优惠表达
    List<FreeTimeRange> freeTimeRanges;
    long freeMinutes;                     // 同化后的总免费分钟

    // 使用统计（来自规则 & 外部）
    List<PromotionUsage> usages;

    // —— 可选：等效金额（仅统计，不参与计费） ——
    BigDecimal equivalentAmount;

    /**
     * 优惠结转输出状态
     * 用于 CONTINUE 模式的下次计算
     */
    PromotionCarryOver promotionCarryOver;

    // ==================== AMOUNT/DISCOUNT 优惠 ====================

    /**
     * 金额减免列表
     */
    List<AmountDiscount> amountDiscounts;

    /**
     * 总减免金额（所有 AMOUNT 优惠的总和）
     */
    BigDecimal totalAmountDiscount;

    /**
     * 最优折扣率（所有 DISCOUNT 优惠中折扣力度最大的）
     * 如有多个折扣，取 min(discountRate)
     */
    BigDecimal bestDiscountRate;

    // ==================== 优惠类型判断方法 ====================

    /**
     * 是否为空（无优惠）
     */
    public boolean isEmpty() {
        return (freeTimeRanges == null || freeTimeRanges.isEmpty()) && freeMinutes <= 0
                && (amountDiscounts == null || amountDiscounts.isEmpty());
    }

    /**
     * 是否有多种优惠类型
     */
    public boolean hasMultiplePromotionTypes() {
        Set<BConstants.PromotionType> types = new HashSet<>();
        if (freeTimeRanges != null && !freeTimeRanges.isEmpty()) {
            for (FreeTimeRange range : freeTimeRanges) {
                if (range.getPromotionType() != null) {
                    types.add(range.getPromotionType());
                }
            }
        }
        if (freeMinutes > 0) {
            types.add(BConstants.PromotionType.FREE_MINUTES);
        }
        if (amountDiscounts != null && !amountDiscounts.isEmpty()) {
            for (AmountDiscount ad : amountDiscounts) {
                types.add(ad.type);
            }
        }
        return types.size() > 1;
    }

    /**
     * 是否为单一优惠类型
     */
    public boolean hasSinglePromotionType() {
        if (isEmpty()) {
            return false;
        }
        Set<BConstants.PromotionType> types = new HashSet<>();
        if (freeTimeRanges != null) {
            for (FreeTimeRange range : freeTimeRanges) {
                if (range.getPromotionType() != null) {
                    types.add(range.getPromotionType());
                }
            }
        }
        if (freeMinutes > 0) {
            types.add(BConstants.PromotionType.FREE_MINUTES);
        }
        if (amountDiscounts != null && !amountDiscounts.isEmpty()) {
            for (AmountDiscount ad : amountDiscounts) {
                types.add(ad.type);
            }
        }
        return types.size() == 1;
    }

    /**
     * 是否有金额减免优惠
     */
    public boolean hasAmountDiscount() {
        return totalAmountDiscount != null && totalAmountDiscount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 是否有折扣优惠
     */
    public boolean hasRateDiscount() {
        return bestDiscountRate != null && bestDiscountRate.compareTo(BigDecimal.ONE) < 0;
    }

    /**
     * 金额减免或折扣优惠记录
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AmountDiscount {
        String id;
        BConstants.PromotionType type;
        BigDecimal amount;       // AMOUNT 类型的减免金额
        BigDecimal discountRate; // DISCOUNT 类型的折扣率
        Integer priority;
    }
}
