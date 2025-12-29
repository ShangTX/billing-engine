package cn.shang.promotion;

import cn.shang.billing.pojo.CalculationWindow;
import cn.shang.promotion.pojo.FreeMinuteAllocationResult;
import cn.shang.promotion.pojo.FreeTimeRange;
import cn.shang.promotion.pojo.PromotionContribution;

import java.util.List;

public class FreeMinuteAllocator {
    public FreeMinuteAllocationResult allocate(List<PromotionContribution> freeMinutesPromotions,
                                               List<FreeTimeRange> explicitFreeRanges,
                                               CalculationWindow window) {
        return null;
    }
}
