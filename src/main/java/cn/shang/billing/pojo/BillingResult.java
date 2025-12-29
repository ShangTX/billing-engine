package cn.shang.billing.pojo;


import cn.shang.charge.pojo.ChargingResult;
import cn.shang.promotion.pojo.PromotionUsage;
import cn.shang.settlement.pojo.SettlementAdjustment;
import cn.shang.settlement.pojo.SettlementResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BillingResult {
    private List<BillingUnit> units; // 计费细节
    private List<PromotionUsage> promotionUsages; // 优惠使用情况
    private List<SettlementAdjustment> settlementAdjustments; // 结算情况
    private BigDecimal finalAmount;
    // 价格的有效时间
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;


    public static BillingResult of(ChargingResult chargingResult, SettlementResult settlementResult) {
        // TODO
        return null;
    }
}
