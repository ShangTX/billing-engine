package cn.shang.charge;

import cn.shang.charge.pojo.BillingDTO;
import cn.shang.charge.pojo.BillingResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class BillingService {

    private final BillingEngine billingEngine;
    private final BillingContextFactory billingContextFactory;


    public BillingResult calculate(BillingDTO dto) {
        // TODO 参数校验

        // 构建计费会话
        var context = billingContextFactory.create(dto);

        // 进行计算
        return billingEngine.calculate(context);
    }

}
