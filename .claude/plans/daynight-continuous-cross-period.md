# DayNight CONTINUOUS 支持跨日夜单元按比例归属

## 背景与现状

- **旧版本** DayNight CONTINUOUS 的 `splitTimeAxis` 只在**免费时段边界**切分，**不在日夜边界切分**。单元跨日夜时由 `DayNightPriceResolver.determineUnitPriceForContinuous` 按 `crossPeriodMode`（默认 BLOCK_WEIGHT）+ `blockWeight` 归属白天/夜晚价。
- **现在版本** [DayNightContinuousStrategy.calculateBoundaryDriven](core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightContinuousStrategy.java:240) 的边界 providers 含**日夜边界 provider**（第 240-263 行），`BoundaryDrivenLoop` 在日夜边界切断单元。这是相对旧版本的**回归**。
- 用户需要 CONTINUOUS 恢复跨日夜归属（业务过渡），但 CONTINUOUS 与 UNIT_BASED 的优惠处理方式不同（CONTINUOUS 免费段切断、部分覆盖免费；UNIT_BASED 完整覆盖才免费），无法用 UNIT_BASED 替代。

## 方案（配置开关 opt-in）

### 1. DayNightConfig 加字段

[DayNightConfig](core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightConfig.java) 加 `splitDayNightBoundary`（Boolean，`@Builder.Default` 默认 `true` 保持现状）：

```java
/**
 * CONTINUOUS 模式是否在日夜边界切断单元。
 * true（默认）：日夜边界切断单元，每单元纯 day/night（现状）。
 * false：不在日夜边界切断，单元跨日夜时按 crossPeriodMode（默认 BLOCK_WEIGHT）+ blockWeight 归属白天/夜晚价。
 * 仅 CONTINUOUS 模式生效；UNIT_BASED 固定单元对齐本就不切断；时长模式按时长计费不涉及单元归属。
 */
@Builder.Default
Boolean splitDayNightBoundary = true;
```

### 2. DayNightContinuousStrategy.calculateBoundaryDriven 按 开关控制日夜边界 provider

```java
if (!Boolean.FALSE.equals(config.getSplitDayNightBoundary())) {
    // 默认 true：含日夜边界 provider（现状）
    providers.add(日夜边界 provider);
}
```

- `splitDayNightBoundary=true`（默认）：含日夜边界 provider，单元在日夜边界切断（现状，现有测试不变）
- `splitDayNightBoundary=false`：不含日夜边界 provider，单元跨日夜，由 `buildSegmentForDayNight` 调用 `determineUnitPriceForContinuous`（已含 MIXED/BLOCK_WEIGHT 归属）定价

### 3. 无需改动的部分

- **跨日夜单元定价**：[DayNightPriceResolver.determineUnitPriceForContinuous](core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightPriceResolver.java:89) 已处理 MIXED（BLOCK_WEIGHT 按 `dayMinutes/duration >= blockWeight` 归属），无需改
- **优惠切断**：`freeRangeEdges` provider 保留，免费段仍切断单元（CONTINUOUS 优惠语义不变）
- **周期封顶**：`cycleEnd`（24h）provider 保留，跨日夜单元不跨周期（日夜边界在周期内）
- **compact 合并**：[CompactMerger](core/src/main/java/cn/shang/charging/charge/rules/CompactMerger.java:76) 按 `unitPrice` 合并，跨日夜单元单价（归属后 day 或 night）与相邻不同则不合并，无需改
- **简化路径**：`generateUnitsByGlobalGaps` 的头尾/优惠段走 `calculateBoundaryDriven`，开关同样生效；简化段本身对齐周期边界，不受影响

### 4. 测试

- **现有测试不变**（默认 `splitDayNightBoundary=true`）
- 新增 `DayNightContinuousCrossPeriodTest`（bill-test）：
  - `splitDayNightBoundary=false` + BLOCK_WEIGHT：单元跨日夜按占比归属（如 15:52-16:52，day 25min/night 35min，blockWeight=0.5 -> 归 night）
  - 与 `splitDayNightBoundary=true`（切断）对账：金额不同（跨日夜归属 vs 切断分别计价）
  - 优惠仍切断：跨日夜单元被免费段切断，免费段内免费
  - 与 UNIT_BASED 对账：同样跨日夜归属，但优惠处理不同（CONTINUOUS 切断 vs UNIT_BASED 完整覆盖）

### 5. 文档

- [USER_GUIDE.md](docs/USER_GUIDE.md) DayNight 配置表加 `splitDayNightBoundary`
- [billing-engine-capabilities-zh.md](docs/billing-engine-capabilities-zh.md) DayNight 能力说明加跨日夜归属开关

## 不做

- 不改 RelativeTime/NaturalTime/CompositeTime 的 CONTINUOUS（用户只要 DayNight）
- 不改默认行为（`splitDayNightBoundary` 默认 true，现状不变）
- 不强制校验 `splitDayNightBoundary=false` 时 `crossPeriodMode` 的取值（用户配置责任；BEGIN_TIME_TRUNCATE 与 false 矛盾，但不拦截）

## 涉及文件

**修改**：
- `core/.../daynight/DayNightConfig.java`（加 `splitDayNightBoundary` 字段）
- `core/.../daynight/DayNightContinuousStrategy.java`（calculateBoundaryDriven 按开关控制日夜边界 provider）

**新增**：
- `bill-test/.../DayNightContinuousCrossPeriodTest.java`

**文档**：
- `docs/USER_GUIDE.md`、`docs/billing-engine-capabilities-zh.md`

## 验证

- `mvn install -pl core -am -DskipTests` 编译通过
- `mvn test` 全量测试通过（现有测试不变，新增测试验证 opt-in 跨日夜归属）
