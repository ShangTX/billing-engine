package cn.shang.charging.charge.rules;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 定价状态对象，在边界循环过程中管理动态定价状态。
 * <p>
 * 核心思路：将定价状态从段构建器中提取出来，在边界循环外统一管理。
 * 边界提供器可以访问和修改状态（如价格切换），段构建器直接从状态读取价格。
 * <p>
 * 优点：
 * <ul>
 *   <li>避免重复判断：边界提供器已经处理了价格切换，段构建器无需重复判断 isInDay()</li>
 *   <li>通用性强：不同规则可以用不同的状态修改逻辑（DayNight、RelativeTime）</li>
 *   <li>职责清晰：边界提供器负责状态转换，段构建器负责段生成</li>
 * </ul>
 */
public class PricingState {

    /**
     * 当前单价（随边界切换而变化）。
     */
    private BigDecimal currentUnitPrice;

    /**
     * 单位时间（分钟）。
     */
    private int unitMinutes;

    /**
     * 周期起点（用于单元对齐）。
     */
    private LocalDateTime cycleOrigin;

    /**
     * 规则特定的扩展状态。
     * 例如：DayNight 可存储 dayBeginMin/dayEndMin，RelativeTime 可存储当前阶段索引。
     */
    private Object ruleSpecificState;

    // ==================== Getters & Setters ====================

    public BigDecimal getCurrentUnitPrice() {
        return currentUnitPrice;
    }

    public PricingState setCurrentUnitPrice(BigDecimal currentUnitPrice) {
        this.currentUnitPrice = currentUnitPrice;
        return this;
    }

    public int getUnitMinutes() {
        return unitMinutes;
    }

    public PricingState setUnitMinutes(int unitMinutes) {
        this.unitMinutes = unitMinutes;
        return this;
    }

    public LocalDateTime getCycleOrigin() {
        return cycleOrigin;
    }

    public PricingState setCycleOrigin(LocalDateTime cycleOrigin) {
        this.cycleOrigin = cycleOrigin;
        return this;
    }

    public Object getRuleSpecificState() {
        return ruleSpecificState;
    }

    public PricingState setRuleSpecificState(Object ruleSpecificState) {
        this.ruleSpecificState = ruleSpecificState;
        return this;
    }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BigDecimal currentUnitPrice;
        private int unitMinutes;
        private LocalDateTime cycleOrigin;
        private Object ruleSpecificState;

        public Builder currentUnitPrice(BigDecimal currentUnitPrice) {
            this.currentUnitPrice = currentUnitPrice;
            return this;
        }

        public Builder unitMinutes(int unitMinutes) {
            this.unitMinutes = unitMinutes;
            return this;
        }

        public Builder cycleOrigin(LocalDateTime cycleOrigin) {
            this.cycleOrigin = cycleOrigin;
            return this;
        }

        public Builder ruleSpecificState(Object ruleSpecificState) {
            this.ruleSpecificState = ruleSpecificState;
            return this;
        }

        public PricingState build() {
            PricingState state = new PricingState();
            state.currentUnitPrice = this.currentUnitPrice;
            state.unitMinutes = this.unitMinutes;
            state.cycleOrigin = this.cycleOrigin;
            state.ruleSpecificState = this.ruleSpecificState;
            return state;
        }
    }
}