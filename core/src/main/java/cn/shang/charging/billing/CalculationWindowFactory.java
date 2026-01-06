package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.CalculationWindow;

import java.time.LocalDateTime;

public class CalculationWindowFactory {

    /**
     * 创建计算窗口
     */
    public static CalculationWindow create(LocalDateTime beginTime,
                                           BillingSegment segment,
                                           BConstants.SegmentCalculationMode segmentCalculationMode) {
        var calculationWindow = new CalculationWindow();
        if(segmentCalculationMode == BConstants.SegmentCalculationMode.GLOBAL_ORIGIN) {
            calculationWindow.setCalculationBegin(beginTime);
        } else {
            calculationWindow.setCalculationBegin(segment.getBeginTime());
        }
        calculationWindow.setCalculationEnd(segment.getEndTime());
        calculationWindow.setClipBegin(segment.getBeginTime());
        calculationWindow.setClipEnd(segment.getEndTime());
        return calculationWindow;
    }
}
