package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.FreeTimeRange;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 优惠结转状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionCarryOver {

    /**
     * 剩余免费分钟数
     * key: promotionId
     * value: 剩余分钟数
     */
    private Map<String, Integer> remainingMinutes;

    /**
     * 已使用的免费时段
     * 用于追踪免费时段的部分使用情况
     */
    private List<FreeTimeRange> usedFreeRanges;

}