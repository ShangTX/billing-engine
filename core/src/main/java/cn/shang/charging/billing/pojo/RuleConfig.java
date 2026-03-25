package cn.shang.charging.billing.pojo;

public interface RuleConfig {

    String getId();
    String getType();

    /**
     * 是否支持简化计算
     * null 表示默认支持
     */
    default Boolean getSimplifiedSupported() {
        return null;
    }

}
