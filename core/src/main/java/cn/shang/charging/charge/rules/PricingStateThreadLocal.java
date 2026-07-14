package cn.shang.charging.charge.rules;

/**
 * ThreadLocal工具类，用于在边界循环和边界提供器之间传递状态快照。
 * <p>
 * 使用场景：DayNight Provider需要在snap时修改stateSnapshot，但Provider接口不支持返回修改后的状态。
 * 通过ThreadLocal，Provider可以获取并修改当前的状态快照，段构建器会使用修改后的快照。
 * <p>
 * 注意：必须在每次使用后调用remove()清理，避免内存泄漏。
 */
public final class PricingStateThreadLocal {

    private static final ThreadLocal<PricingState> HOLDER = new ThreadLocal<>();

    private PricingStateThreadLocal() {
    }

    /**
     * 设置当前线程的状态快照。
     *
     * @param state 状态快照
     */
    public static void set(PricingState state) {
        HOLDER.set(state);
    }

    /**
     * 获取当前线程的状态快照。
     *
     * @return 状态快照，可能为null
     */
    public static PricingState get() {
        return HOLDER.get();
    }

    /**
     * 清除当前线程的状态快照。
     * 必须在每次使用后调用，避免内存泄漏。
     */
    public static void remove() {
        HOLDER.remove();
    }
}