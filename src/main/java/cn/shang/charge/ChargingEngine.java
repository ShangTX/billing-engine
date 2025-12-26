package cn.shang.charge;

import cn.shang.billing.pojo.BillingContext;
import cn.shang.charge.pojo.ChargingResult;
import cn.shang.promotion.pojo.PromotionAggregate;
import org.springframework.stereotype.Service;

@Service
public class ChargingEngine {
    public ChargingResult calculate(BillingContext context, PromotionAggregate promotionAggregate) {
        return null;
    }
}
