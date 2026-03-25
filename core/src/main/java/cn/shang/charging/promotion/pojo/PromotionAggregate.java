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
 * 优惠计算
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

    // ==================== 优惠类型判断方法 ====================

    /**
     * 是否为空（无优惠）
     */
    public boolean isEmpty() {
        return (freeTimeRanges == null || freeTimeRanges.isEmpty()) && freeMinutes <= 0;
    }

    /**
     * 是否有多种优惠类型
     */
    public boolean hasMultiplePromotionTypes() {
        if (freeTimeRanges == null || freeTimeRanges.isEmpty()) {
            return freeMinutes > 0;
        }
        Set<BConstants.PromotionType> types = new HashSet<>();
        for (FreeTimeRange range : freeTimeRanges) {
            if (range.getPromotionType() != null) {
                types.add(range.getPromotionType());
            }
        }
        // 如果有免费分钟数，也算一种类型
        if (freeMinutes > 0) {
            types.add(BConstants.PromotionType.FREE_MINUTES);
        }
        return types.size() > 1;
    }

    /**
     * 是否为单一优惠类型
     */
    public boolean hasSinglePromotionType() {
        if ((freeTimeRanges == null || freeTimeRanges.isEmpty()) && freeMinutes <= 0) {
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
        return types.size() == 1;
    }
}
