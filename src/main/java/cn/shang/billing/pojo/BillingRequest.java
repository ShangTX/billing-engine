package cn.shang.billing.pojo;

import cn.shang.promotion.pojo.PromotionRule;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计费请求
 */
@Data
public class BillingRequest {

    private String id;

    // 开始结束时间
    private LocalDateTime entryTime;
    private LocalDateTime billingEndTime;

    /** 三种模式：STATELESS / CACHE / PERSIST */
    private BConstants.BillingMode mode;

    // 外部优惠
    private List<PromotionRule> promotionRules;

}
