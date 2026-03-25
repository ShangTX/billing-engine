package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.PromotionGrant;
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

    /**
     * 查询时间点（可选）
     * 用于返回该时刻的费用状态
     * 不提供时，默认使用 calcEndTime
     */
    private LocalDateTime queryTime;

    /**
     * 计算结束时间（可选）
     * 用于控制计算进度
     * 不提供时，使用 endTime
     */
    private LocalDateTime calcEndTime;

    // 外部优惠
    private List<PromotionGrant> externalPromotions;

    // 分段计算方式
    private BConstants.SegmentCalculationMode segmentCalculationMode;

    // 单个方案id
    private String schemeId;

    /**
     * 方案变更时间轴（只在方案切换时产生）
     */
    private List<SchemeChange> schemeChanges;

    /**
     * 上一次计费的结转状态（用于 CONTINUE 模式）
     * 如果不为 null，则从 previousCarryOver.calculatedUpTo 继续计算
     */
    private BillingCarryOver previousCarryOver;

}
