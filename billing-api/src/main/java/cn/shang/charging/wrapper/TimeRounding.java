package cn.shang.charging.wrapper;

import java.time.LocalDateTime;

/**
 * Request-entry time normalization helpers.
 * <p>
 * The core engine calculates at minute precision; any second-level truncation
 * inside core is only defensive protection. This helper belongs to the
 * billing-api facade and is the recommended place for callers to make rounding
 * intent explicit before invoking core.
 */
public final class TimeRounding {

    private TimeRounding() {
    }

    /**
     * Truncates second/nano fields to minute precision.
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
     * Rounds up to minute precision when second/nano fields are present.
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
