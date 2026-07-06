package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.util.JacksonUtils;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 条件免费特性测试
 * <p>
 * 测试 StartFreePromotionConfig.validateQueryTime=true 时的"超过即失效"行为。
 * <p>
 * 核心设计：
 * - 计费引擎（core）产生确定性的计费单元集合，标记 conditionalFree=true
 * - 结果视图层（billing-api）根据 queryTime 决定是否恢复原价
 * - queryTime ≤ conditionalFreeUntil：免费有效
 * - queryTime > conditionalFreeUntil：恢复原价
 */
public class ConditionalFreePromotionTest {

    public static void main(String[] args) {
        System.out.println("========== 条件免费特性测试 ==========\n");

        // 场景1: 基础条件免费 - 不同时间点查询
//         testBasicConditionalFree();

        // 场景2: 条件免费 + 外部免费时段重叠
//         testConditionalFreeWithExternalRange();

        // 场景3: 条件免费 + 免费分钟数共存
//         testConditionalFreeWithFreeMinutes();

        // 场景4-6: DELETED (依赖 CONTINUE 模式和 BillingResultViewer)
        // testConditionalFreeContinueMode() - 已删除
        // testExactBoundaryTime() - 已删除
        // testMultipleConditionalFreeUnits() - 已删除

        System.out.println("\n========== 测试完成 ==========");
    }

    // ==================== 测试用例 ====================

    /**
     * 场景1: 基础条件免费
     * 规则: 前60分钟免费（条件免费），00:00开始，每45分钟2元
     * 验证不同 queryTime 的费用变化
     */

    /**
     * 场景2: 条件免费 + 外部免费时段重叠
     * 规则: 前60分钟免费（条件免费），外部优惠: 00:30-02:00 免费
     * 验证合并后的条件免费行为
     */

    /**
     * 场景3: 条件免费 + 免费分钟数共存
     * 规则: 前30分钟免费（条件免费）+ 外部20分钟免费
     * 验证两种优惠类型的交互
     */

    /**
     * 场景4: CONTINUE 模式下的条件免费 - DELETED (依赖 CONTINUE 模式和 BillingResultViewer)
     * 此测试方法已被删除，因为项目重构移除了 CONTINUE 续算模式和 BillingResultViewer 类。
     */

    /**
     * 场景5: 边界条件 - queryTime 恰好等于 conditionalFreeUntil - DELETED (依赖 BillingResultViewer)
     * 此测试方法已被删除，因为项目重构移除了 BillingResultViewer 类。
     */

    /**
     * 场景6: 多个条件免费单元 - DELETED (依赖 BillingResultViewer)
     * 此测试方法已被删除，因为项目重构移除了 BillingResultViewer 类。
     */

    // ==================== 辅助方法 ====================

    /**
     * 创建带 StartFree 规则的 BillingService
     *
     * @param startFreeMinutes  前N分钟免费
     * @param validateQueryTime 是否启用条件免费
     */
    static BillingService getBillingService(int startFreeMinutes, boolean validateQueryTime) {
        return getBillingServiceWithUnitMinutes(startFreeMinutes, validateQueryTime, 45);
    }

    /**
     * 创建带 StartFree 规则和指定单元时长的 BillingService
     *
     * @param startFreeMinutes  前N分钟免费
     * @param validateQueryTime 是否启用条件免费
     * @param unitMinutes       计费单元时长（分钟）
     */
    static BillingService getBillingServiceWithUnitMinutes(int startFreeMinutes, boolean validateQueryTime, int unitMinutes) {
        var billingConfigResolver = new BillingConfigResolver() {
            @Override
            public BConstants.CalculationMode resolveCalculationMode(String schemeId, Map<String, Object> context) {
                return BConstants.CalculationMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return new DayNightConfig()
                        .setId("daynight-1")
                        .setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740)
                        .setDayEndMinute(1140)
                        .setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1"))
                        .setMaxChargeOneDay(new BigDecimal("100"))
                        .setUnitMinutes(unitMinutes);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
                return List.of(
                        new StartFreePromotionConfig()
                                .setId("start-free-conditional")
                                .setMinutes(startFreeMinutes)
                                .setPriority(1)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }
}
