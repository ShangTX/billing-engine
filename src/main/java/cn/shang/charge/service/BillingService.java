package cn.shang.charge.service;

import cn.shang.charge.pojo.BillingContext;
import cn.shang.charge.pojo.BillingResult;
import cn.shang.charge.pojo.ChargeDTO;
import cn.shang.charge.pojo.RuleConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class BillingService {

    public BillingResult getCost(ChargeDTO dto) {

        // 转换入场离场时间
        var queryTime = dto.getQueryTime(); // BizUtil.getEndTime(dto.getQueryTime()); // 离场时间
        var beginTime = dto.getBeginTime(); // BizUtil.getBeginTime(dto.getBeginTime()); // 开始时间
        int duration = (int) Duration.between(beginTime, queryTime).toMinutes();

        if (duration <= 0) {
            log.info("dto={} 停车时长为0分钟", dto);
            // 返回0
            return BillingResult.createZeroMinutesResult();
        }
        // 获取计费上下文
        BillingContext context = null;
        BillingResult result = null;
        //
        if (dto.getOrderId() == null || dto.getOrderId() == 0L) {
            // 仅单次计费
            context = createNewContext(dto);
        } else {
            // 从数据库或缓存获取计费上下文
            result = getResultFromCache(dto.getOrderId());
            if (result == null) {
                // 需要继续计算获取计费结果
                // 刷新计费颗粒
//                refreshCharging(dto, context, dto.getQueryTime(), basicRuleConfig, campaignRuleConfigs);
            }
        }

        return result;
    }


    /**
     * 刷新计费
     * @param dto 计费DTO
     * @param context 计费上下文
     * @param queryTime 查询时间
     */
    private void refreshCharging(ChargeDTO dto, BillingContext context, LocalDateTime queryTime,
                                 RuleConfig basicRuleConfig, List<RuleConfig> campaignRuleConfigs) {


     /*   // 活动规则
        campaignRuleConfigs.forEach(campaignRuleConfig -> {
            var discounts = getRule(campaignRuleConfig.getClassName()).generateDiscounts(campaignRuleConfig, queryTime, context);
            if (discounts != null && !discounts.isEmpty()) {
                context.getRemainingDiscounts().addAll(discounts);
            }
        });

        // 基础计费规则的配置和实例
        var basicRuleChargingInstance = getRule(basicRuleConfig.getClassName());


        var discountsFromBasicRule = basicRuleChargingInstance.generateDiscounts(basicRuleConfig, queryTime, context);
        if (discountsFromBasicRule != null && !discountsFromBasicRule.isEmpty()) {
            context.getRemainingDiscounts().addAll(discountsFromBasicRule);
        }

        // TODO 计算上次计算位置后面有无额外优惠，如果有，将其取出，重新加入新增优惠中
        if (!context.getRemainingDiscounts().isEmpty()) {
            for (int i = context.getLastTimePeriodIndex() + 1; i < context.getTimePeriods().size(); i++) {
                var timePeriod = context.getTimePeriods().get(i);
                if (timePeriod.isFree()) {
                    context.getTimePeriods().remove(i);
                    i--;
                    // TODO
                    context.getRemainingDiscounts().add(new ChargingDTO.Discount());
                } else {
                    throw new ServiceException("保存数据有误");
                }
            }
        }

        // 未使用的免费时间区间
        var unusedPeriodList = applyFreeTimePeriods(context);

        // 免费时长
        applyFreeMinutes(context);

        // 计算费用
        try {
            basicRuleChargingInstance.refreshPellets(basicRuleConfig, queryTime, context, dto);
        } catch (Exception e) {
            log.error("计费过程异常 dto={} ", JacksonUtils.toJsonString(dto), e);
        } finally {
            if (context.loopCount >= SysConstants.MAX_LOOP) {
                log.error("计费疑似发生死循环 dto={} ", JacksonUtils.toJsonString(dto));
            }
        }

        context.setRemainingDiscounts(unusedPeriodList);*/
    }

    /**
     * 创建一个新的计费上下文
     */
    private BillingContext createNewContext(ChargeDTO dto) {
        return null;
    }

    /**
     * 从缓存中获取计费结果, (如果查询时间被包含在已经计算的时长之中)
     *
     * @param orderId 订单id
     */
    private BillingResult getResultFromCache(Long orderId) {
/*        var key = RedisKeyConstants.CHARGING_RESULT + orderId;
        var value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        return JacksonUtils.parse(value, ChargingResult.class);*/
        return null;
    }

    /**
     * 获取规则
     *
     * @param value    配置字符串
     * @param ruleCode 编号
     */
    private RuleConfig getRuleConfigByName(String value, String ruleCode) {
/*        var ruleConfig = switch (ruleCode) {
            case "dayNight" -> JacksonUtils.parse(value, DayNightConfig.class);
            case "freeMinutes" -> JacksonUtils.parse(value, FreeTimeConfig.class);
            case "totallyFree" -> JacksonUtils.parse(value, TotallyFreeConfig.class);
            case "relativeTimePeriod" -> JacksonUtils.parse(value, RelativeTimePeriodConfig.class);
            default -> throw new ServiceException("未知计费规则类型");
        };
        if (ruleConfig == null) {
            // TODO 国际化
            throw new ServiceException("计费规则配置错误");
        }
        return ruleConfig.setClassName(ruleCode);*/
        return null;
    }

}
