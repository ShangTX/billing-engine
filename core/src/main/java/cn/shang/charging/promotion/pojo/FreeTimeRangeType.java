package cn.shang.charging.promotion.pojo;

/**
 * 免费时间段类型
 */
public enum FreeTimeRangeType {
    /**
     * 普通免费时间段
     * 不影响周期边界，仅标记时间免费
     */
    NORMAL,

    /**
     * 气泡型免费时间段
     * 延长计费周期边界，后续相对时间段边界整体后移
     */
    BUBBLE
}