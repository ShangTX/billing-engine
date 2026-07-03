package cn.shang.charging.promotion.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * FREE_MINUTES 时段化结果（TODO-20260702-004：由策略侧调用 FreeMinuteAllocator 产出）
 */
@Accessors(chain = true)
@Data
public class FreeMinuteAllocationResult {

    /**
     * 最终免费时间段（FREE_RANGE + 时段化后的 FREE_MINUTES，已合并）
     */
    List<FreeTimeRange> finalFreeRanges;

    /**
     * FREE_MINUTES 的 PromotionUsage（含 granted/used minutes，供回写外部池与 carryOver）
     */
    List<PromotionUsage> promotionUsages;

}
