package cn.shang.charging.billing.pojo;


import cn.shang.charging.charge.pojo.ChargingResult;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import cn.shang.charging.settlement.pojo.SettlementAdjustment;
import cn.shang.charging.settlement.pojo.SettlementResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain=true)
@Builder(toBuilder = true)
public class BillingResult {
    private List<BillingUnit> units; // 计费细节
    private List<PromotionUsage> promotionUsages; // 优惠使用情况
    private List<SettlementAdjustment> settlementAdjustments; // 结算情况
    private BigDecimal finalAmount;
    // 价格的有效时间
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;

    /**
     * 实际计算到的时间点（延伸后，用于缓存有效性判断和 CONTINUE 起点）
     * 最后一个计费单元延伸后的结束时间
     */
    private LocalDateTime calculationEndTime;

    /**
     * 本次计算后的结转状态（供下次 CONTINUE 使用）
     */
    private BillingCarryOver carryOver;


    public static BillingResult of(ChargingResult chargingResult, SettlementResult settlementResult) {
        // TODO
        return null;
    }
}
