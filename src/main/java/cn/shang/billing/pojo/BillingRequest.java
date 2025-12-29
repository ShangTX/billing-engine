package cn.shang.billing.pojo;

import cn.shang.promotion.pojo.ExternalPromotionInput;
import cn.shang.promotion.pojo.PromotionContribution;
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
    private LocalDateTime beginTime;
    private LocalDateTime endTime;

    /** 三种模式：STATELESS / CACHE / PERSIST */
    private BConstants.BillingMode billingMode;

    // 外部优惠
    private List<PromotionContribution> externalPromotions;

    // 分段计算方式
    private BConstants.SegmentCalculationMode segmentCalculationMode;

    // 单个方案id
    private String schemeId;

    /**
     * 方案变更时间轴（只在方案切换时产生）
     */
    private List<SchemeChange> schemeChanges;

}
