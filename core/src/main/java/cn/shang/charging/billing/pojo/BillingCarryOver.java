package cn.shang.charging.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

}