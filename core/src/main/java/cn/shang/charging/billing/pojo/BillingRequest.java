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

    /**
     * 精确查询时用于禁用 simplification
     */
    private Boolean disableSimplification;

    /**
     * 等效金额计算规格（TODO-20260706-003）。
     * <p>
     * {@code null}（默认）= 不计算等效金额，{@code promotionUsages.equivalentAmount}
     * 保持策略侧"原价之和"近似值，{@code totalEquivalentAmount} 为 {@code null}。
     * 非 {@code null} 时按规格过滤计算，回填到 usage 与 {@code totalEquivalentAmount}。
     */
    private EquivalentAmountSpec equivalentAmountSpec;

}
