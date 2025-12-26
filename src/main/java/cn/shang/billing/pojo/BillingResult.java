package cn.shang.billing.pojo;


import cn.shang.charge.pojo.ChargingResult;
import cn.shang.promotion.pojo.PromotionUsage;
import cn.shang.settlement.pojo.SettlementAdjustment;
import cn.shang.settlement.pojo.SettlementResult;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class BillingResult {
    private final List<BillingUnit> units; // 计费细节
    private final List<PromotionUsage> promotionUsages; // 优惠使用情况
    private final List<SettlementAdjustment> settlementAdjustments; // 结算情况
    private final BigDecimal finalAmount;
    // 价格的有效时间
    private final LocalDateTime effectiveFrom;
    private final LocalDateTime effectiveTo;


    public static BillingResult of(ChargingResult chargingResult, SettlementResult settlementResult) {
        // TODO
        return null;
    }
}
