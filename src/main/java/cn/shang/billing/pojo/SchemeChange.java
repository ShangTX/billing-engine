package cn.shang.billing.pojo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方案切换记录
 */
@Data
public class SchemeChange {

    // 上一个方案id
    String lastSchemeId;
    // 下一个方案id
    String nextSchemeId;
    // 变更时间
    LocalDateTime changeTime;

}
