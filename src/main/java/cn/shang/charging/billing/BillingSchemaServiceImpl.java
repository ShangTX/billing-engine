package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.SchemeChange;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BillingSchemaServiceImpl implements BillingSchemaService {

    @Override
    public List<SchemeChange> getSchemeChanges(BillingRequest request) {
        return List.of();
    }

}
