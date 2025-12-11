package cn.shang.charge.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public abstract class RuleConfig {

    Long id;
    // 类型标识
    String className;

    String version; // 版本号
}
