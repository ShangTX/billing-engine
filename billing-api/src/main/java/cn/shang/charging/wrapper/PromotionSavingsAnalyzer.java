package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.BillingUnit;
import cn.shang.charging.promotion.pojo.PromotionUsage;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 优惠等效金额分析器
 * 分析计费结果中各优惠的节省金额
 */
public class PromotionSavingsAnalyzer {

    /**
     * 分析优惠节省金额
     *
     * @param result 计费结果
     * @return promotionId → 节省金额
     */
    public Map<String, BigDecimal> analyze(BillingResult result) {
        Map<String, BigDecimal> savings = new HashMap<>();

        if (result == null || result.getUnits() == null) {
            return savings;
        }

        for (BillingUnit unit : result.getUnits()) {
            if (unit.isFree() && unit.getFreePromotionId() != null) {
                // 完全免费的单元
                savings.merge(unit.getFreePromotionId(),
                        unit.getOriginalAmount(), BigDecimal::add);
            } else if (unit.getOriginalAmount() != null
                    && unit.getChargedAmount() != null
                    && unit.getOriginalAmount().compareTo(unit.getChargedAmount()) > 0) {
                // 部分优惠（差额即节省金额）
                BigDecimal saved = unit.getOriginalAmount().subtract(unit.getChargedAmount());
                // 从 promotionUsages 查找使用的优惠
                if (result.getPromotionUsages() != null) {
                    for (PromotionUsage usage : result.getPromotionUsages()) {
                        if (usage.getEquivalentAmount() != null
                                && usage.getEquivalentAmount().compareTo(BigDecimal.ZERO) > 0) {
                            savings.merge(usage.getPromotionId(),
                                    usage.getEquivalentAmount(), BigDecimal::add);
                        }
                    }
                }
            }
        }

        // 如果 promotionUsages 中已有 equivalentAmount，直接使用
        if (result.getPromotionUsages() != null) {
            for (PromotionUsage usage : result.getPromotionUsages()) {
                if (usage.getEquivalentAmount() != null
                        && usage.getEquivalentAmount().compareTo(BigDecimal.ZERO) > 0) {
                    savings.put(usage.getPromotionId(), usage.getEquivalentAmount());
                }
            }
        }

        return savings;
    }
}