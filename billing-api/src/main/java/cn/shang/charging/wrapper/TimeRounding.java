package cn.shang.charging.wrapper;

import java.time.LocalDateTime;

/**
 * 时间取整工具（供外部预处理）。
 * <p>
 * 引擎内部统一向下取整（{@link #truncate}），按分钟精度计算，不再提供向上取整模式。
 * 调用方若有「进场多算」（beginTime 向上）、「优惠尽量长」（endTime 向上）等业务需求，
 * 应在构造 {@link cn.shang.charging.billing.pojo.BillingRequest} 前自行调用本工具预处理；
 * 预处理后传入的时间已对齐到分钟，引擎向下取整不再改变。
 */
public final class TimeRounding {

    private TimeRounding() {
    }

    /**
     * 向下取整（秒数置0）。引擎内部统一使用本方法。
     */
    public static LocalDateTime truncate(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        if (time.getSecond() == 0 && time.getNano() == 0) {
            return time;
        }
        return time.withSecond(0).withNano(0);
    }

    /**
     * 向上取整（秒数大于0时增加一分钟，秒数置0）。仅供外部预处理使用，引擎内部不调用。
     */
    public static LocalDateTime ceil(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        if (time.getSecond() == 0 && time.getNano() == 0) {
            return time;
        }
        return time.plusMinutes(1).withSecond(0).withNano(0);
    }
}
