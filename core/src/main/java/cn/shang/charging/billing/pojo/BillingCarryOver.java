package cn.shang.charging.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 顶层结转对象
 * 用于 CONTINUE 模式，支持从上次计算结果继续计算
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingCarryOver {

    /**
     * 已计算到的时间点（延伸后的 calculationEndTime）
     */
    private LocalDateTime calculatedUpTo;

    /**
     * 按分段ID存储的结转状态
     * key: segmentId
     * value: SegmentCarryOver
     */
    private Map<String, SegmentCarryOver> segments;

    /**
     * 最后一个截断单元的开始时间
     * 用于 CONTINUE 模式调整计算起点
     * 如果上次计算的最后单元是完整的（isTruncated=false 或不存在），此值为 null
     */
    private LocalDateTime lastTruncatedUnitStartTime;

    /**
     * 上次计算的累计总金额
     * CONTINUE 模式下用于计算各单元的累计金额
     */
    private BigDecimal accumulatedAmount;

    /**
     * 截断单元已收取的金额
     * 当 lastTruncatedUnitStartTime 不为 null 时，此字段存储该截断单元已收取的费用
     * CONTINUE 模式下重新计算截断单元时，需要扣减此金额以避免重复收费
     */
    private BigDecimal truncatedUnitChargedAmount;

}