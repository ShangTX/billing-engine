package cn.shang.charging.spring.boot.autoconfigure;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeRule;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 计费引擎自动配置
 */
@Configuration
@ConditionalOnClass(BillingService.class)
@EnableConfigurationProperties(BillingProperties.class)
public class BillingAutoConfiguration {

    /**
     * 计费规则注册表（自动注册内置规则）
     */
    @Bean
    @ConditionalOnMissingBean
    public BillingRuleRegistry billingRuleRegistry() {
        BillingRuleRegistry registry = new BillingRuleRegistry();
        // 注册内置规则
        registry.register(BConstants.ChargeRuleType.RELATIVE_TIME, new RelativeTimeRule());
        // DAY_NIGHT 和 COMPOSITE_TIME 已在 BillingRuleRegistry 构造函数中注册
        return registry;
    }

    /**
     * 优惠规则注册表（自动注册内置规则）
     */
    @Bean
    @ConditionalOnMissingBean
    public PromotionRuleRegistry promotionRuleRegistry() {
        PromotionRuleRegistry registry = new PromotionRuleRegistry();
        registry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        return registry;
    }

    /**
     * 优惠引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public PromotionEngine promotionEngine(
            BillingConfigResolver configResolver,
            FreeTimeRangeMerger freeTimeRangeMerger,
            FreeMinuteAllocator freeMinuteAllocator,
            PromotionRuleRegistry promotionRuleRegistry) {
        return new PromotionEngine(configResolver, freeTimeRangeMerger, freeMinuteAllocator, promotionRuleRegistry);
    }

    /**
     * 免费时段合并器
     */
    @Bean
    @ConditionalOnMissingBean
    public FreeTimeRangeMerger freeTimeRangeMerger() {
        return new FreeTimeRangeMerger();
    }

    /**
     * 免费分钟分配器
     */
    @Bean
    @ConditionalOnMissingBean
    public FreeMinuteAllocator freeMinuteAllocator() {
        return new FreeMinuteAllocator();
    }

    /**
     * 计费服务
     */
    @Bean
    @ConditionalOnMissingBean
    public BillingService billingService(
            BillingConfigResolver configResolver,
            BillingRuleRegistry billingRuleRegistry,
            PromotionEngine promotionEngine) {
        return new BillingService(
                new SegmentBuilder(),
                configResolver,
                promotionEngine,
                new BillingCalculator(billingRuleRegistry),
                new ResultAssembler());
    }

    /**
     * 计费模板（便捷 API）
     */
    @Bean
    @ConditionalOnMissingBean
    public BillingTemplate billingTemplate(
            BillingService billingService,
            BillingConfigResolver configResolver) {
        return new BillingTemplate(billingService, configResolver);
    }
}