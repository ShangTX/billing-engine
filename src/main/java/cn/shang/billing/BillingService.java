package cn.shang.billing;

import cn.shang.billing.pojo.*;
import cn.shang.charge.ChargingEngine;
import cn.shang.charge.pojo.ChargingResult;
import cn.shang.promotion.PromotionEngine;
import cn.shang.promotion.pojo.PromotionAggregate;
import cn.shang.settlement.SettlementEngine;
import cn.shang.settlement.pojo.SettlementResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class BillingService {


    private final PromotionEngine promotionEngine;
    private final ChargingEngine chargingEngine;
    private final SettlementEngine settlementEngine;
    private final BillingSchemaService billingSchemaService;

    /**
     * 计费计算
     * @param request 计费参数
     */
    public BillingResult calculate(BillingRequest request) {

        BillingContext context = buildContext(request);

        // === Stage 1：优惠时间同化 ===
        PromotionAggregate promotionAggregate = promotionEngine.evaluate(context);

        // === Stage 2：单位时间计费 ===
        ChargingResult chargingResult = chargingEngine.calculate(context, promotionAggregate);

        // === Stage 3：金额结算调整 ===
        SettlementResult settlementResult = settlementEngine.settle(chargingResult);

        return BillingResult.of(chargingResult, settlementResult);
    }

    private BillingContext buildContext(BillingRequest request) {
        return switch (request.getMode()) {
            case BConstants.BillingMode.STATELESS -> {
                yield createContext(request);
            }
            case BConstants.BillingMode.CACHE -> {
                // TODO 待实现 缓存模式
                throw new IllegalArgumentException("not implemented " + request.getMode());
            }
            case BConstants.BillingMode.PERSIST -> {
                // TODO 待实现 持久化模式
                throw new IllegalArgumentException("not implemented " + request.getMode());
            }
            default -> throw new IllegalArgumentException("Invalid mode " + request.getMode());
        };
    }

    /**
     * 创建一个新的context
     * @param request 计费请求
     */
    private BillingContext createContext(BillingRequest request) {
        var schemaChanges = billingSchemaService.getSchemeChanges(request);
        return BillingContext.builder()
                .id(request.getId())
                .entryTime(request.getEntryTime())
                .billingEndTime(request.getBillingEndTime())
                .mode(request.getMode())
                .progress(BillingProgress.create())
                .promotionRules(request.getPromotionRules())
                .promotionTracker(new PromotionUsageTracker())
                .schemeChanges(schemaChanges)
                .build();
    }
}
