package cn.shang.billing;

import cn.shang.billing.pojo.BillingRequest;
import cn.shang.billing.pojo.SchemeChange;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BillingSchemaServiceImpl implements BillingSchemaService {

    @Override
    public List<SchemeChange> getSchemeChanges(BillingRequest request) {
        return List.of();
    }

}
