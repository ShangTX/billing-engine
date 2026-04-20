package cn.shang.charging.billing.value;

import java.time.LocalDateTime;

public interface UnitValueSpec {

    UnitValueProjection project(LocalDateTime queryTime, LocalDateTime unitBeginTime, LocalDateTime unitEndTime);
}
