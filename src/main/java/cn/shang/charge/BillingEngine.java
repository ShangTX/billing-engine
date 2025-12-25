package cn.shang.charge;

import cn.shang.charge.pojo.BillingContext;
import cn.shang.charge.pojo.BillingResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BillingEngine {


    private final BillingCalculator calculator;
    private final BillingResultHandler resultHandler;

    /**
     * 计费统一入口
     * @param context 计费会话
     */
    public Object calculate(BillingContext context) {

        // 1️⃣ 纯计算（不关心存储）
        BillingResult result = calculator.calculate(context);

        // 2️⃣ 按模式处理结果（缓存 / 持久化 / 无操作）
        resultHandler.handle(context, result);

        // 参数校验
        return result;
    }

}
