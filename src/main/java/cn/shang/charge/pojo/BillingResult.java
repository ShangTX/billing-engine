package cn.shang.charge.pojo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class BillingResult {

    // 停车费
    BigDecimal fee;
    // 生效时间
    LocalDateTime effectiveTime;
    // 过期时间 即到这个时间以前，价格都会是同样的，比如15:01分到16:01分这1小时内价格都是一样的
    LocalDateTime expireTime;
    // 停车分钟数
    Integer duration;

    List<Object> details; // 计费细节

    List<Object> promotionUsages; // 已使用的优惠

    List<Object> unusedPromotions; // 未使用的优惠



    public static BillingResult createZeroMinutesResult() {
        var chargeResult = new BillingResult();
        chargeResult.setDuration(0);
        chargeResult.setFee(BigDecimal.ZERO);
        return chargeResult;
    }

}
