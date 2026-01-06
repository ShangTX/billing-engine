package cn.shang.charging.promotion.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 优惠计算
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class PromotionAggregate {

    // 最终唯一生效的优惠表达
    List<FreeTimeRange> freeTimeRanges;

    // 使用统计（来自规则 & 外部）
    List<PromotionUsage> usages;


}
