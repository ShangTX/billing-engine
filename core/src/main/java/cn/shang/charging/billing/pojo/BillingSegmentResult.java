package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.PromotionAggregate;
import cn.shang.charging.promotion.pojo.PromotionUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 分段计费结果
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain=true)
@Builder
public class BillingSegmentResult {

    /* ========== 一、分段与时间语义 ========== */

    /** 分段ID（与 BillingSegment 对应） */
    private String segmentId;

    /** 本分段逻辑起止时间（方案层） */
    private LocalDateTime segmentStartTime;
    private LocalDateTime segmentEndTime;


    /** 本次实际参与计算的时间范围（考虑截取） */
    private LocalDateTime calculationStartTime;
    private LocalDateTime calculationEndTime;


    /* ========== 二、计费结果（核心数值） ========== */

    /** 本分段最终应收金额 */
    private BigDecimal chargedAmount;

    /** 本分段实际计费时长 分钟数（不含免费） */
    private Integer chargedDuration;

    /* ========== 三、费用稳定性 / 有效期（用于缓存判断） ========== */

    /**
     * 从什么时候开始，当前费用是确定的
     * 通常是最后一个已完成计费单元的结束时间
     */
    private LocalDateTime feeEffectiveStart;

    /**
     * 到什么时候为止，当前费用不会变化
     * ⚠️ 可能为 null（无法预测）
     */
    private LocalDateTime feeEffectiveEnd;


    /* ========== 四、优惠结果（强需求） ========== */

    /** 本分段内的优惠聚合结果 */
    private PromotionAggregate promotionAggregate;


    /* ========== 五、计费过程明细（可追溯） ========== */

    /**
     * 按最小计费单元拆分的明细
     * 是未来对账 / 仲裁 / 调试的唯一证据
     */
    private List<BillingUnit> billingUnits;


    /* ========== 六、继续计算支持（状态输出） ========== */

    /**
     * 本分段结束后的结转状态
     * 用于下一个分段 or 下次继续计算
     */
    private BillingCarryOver carryOverAfter;

    /**
     * 规则计算过程中的输出状态（供 buildCarryOverState 提取）
     */
    private Map<String, Object> ruleOutputState;

    private List<PromotionUsage> promotionUsages;

}
