package cn.shang.charging.promotion.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 分配免费分钟数的结果
 */
@Accessors(chain = true)
@Data
public class FreeMinuteAllocationResult {

    List<FreeTimeRange> generatedFreeRanges;

    List<PromotionUsage>  promotionUsages;

}
