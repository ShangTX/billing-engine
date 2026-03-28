package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.PromotionGrant;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /**
     * 时间取整模式（可选）
     * 用于处理开始/结束时间中的秒数
     * 不设置时，在 BillingTemplate.calculate 中默认使用 CEIL_BEGIN_TRUNCATE_END
     */
    private TimeRoundingMode timeRoundingMode;

    /**
     * 上下文参数（可选）
     * 用于传递自定义参数给 BillingConfigResolver 的 resolve 方法
     * 实现类可根据此参数灵活返回不同的配置
     */
    private Map<String, Object> context;

}
