package cn.shang.charging.billing.pojo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方案切换记录。
 * <p>
 * 描述一次计费方案变更：在 changeTime 时刻从 lastSchemeId 切换到 nextSchemeId。
 * 引擎按此时间轴将计费区间切割为多段，各段使用对应方案的规则配置。
 */
@Data
public class SchemeChange {

    /** 变更前的方案ID */
    String lastSchemeId;
    /** 变更后的方案ID */
    String nextSchemeId;
    /** 变更发生时间（分段切割点） */
    LocalDateTime changeTime;

}
