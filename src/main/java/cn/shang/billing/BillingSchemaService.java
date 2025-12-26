package cn.shang.billing;

import cn.shang.billing.pojo.BillingRequest;
import cn.shang.billing.pojo.SchemeChange;

import java.util.List;

/**
 * 计费方案Service
 */
public interface BillingSchemaService {

    /**
     * 获取方案变更记录
     */
    List<SchemeChange> getSchemeChanges(BillingRequest request);

}
