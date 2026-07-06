package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.CalculationWindow;

import java.time.LocalDateTime;

public class CalculationWindowFactory {

    /**
     * 创建计算窗口。
     * <p>
     * TODO-20260706-003：GLOBAL_ORIGIN 已废弃，SINGLE / SEGMENT_LOCAL 统一以分段起点为 calculationBegin。
     * {@code segmentCalculationMode} 参数保留作扩展点（未来恢复 4A 减法方案时加回分支）。
     */
    public static CalculationWindow create(LocalDateTime beginTime,
                                           BillingSegment segment,
                                           BConstants.SegmentCalculationMode segmentCalculationMode) {
        var calculationWindow = new CalculationWindow();
        calculationWindow.setCalculationBegin(segment.getBeginTime());
        calculationWindow.setCalculationEnd(segment.getEndTime());
        return calculationWindow;
    }
}
