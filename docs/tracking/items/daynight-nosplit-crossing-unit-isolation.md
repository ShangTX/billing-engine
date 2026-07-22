# DayNight splitDayNightBoundary=false 跨日夜边界单元孤立成段（修复 12:09 错误截断）

## 元数据

- **ID**: TODO-20260722-001
- **类型**: bug
- **优先级**: P1
- **状态**: done
- **创建时间**: 2026-07-22
- **创建提交**: 5d51764
- **完成时间**: 2026-07-22
- **完成提交**: （待提交，工作区；提交后回填主要实现提交 SHA）
- **相关测试**:
  - `DayNightContinuousNoSplitBoundaryTest`（新增回归）
  - `DayNightContinuousCrossPeriodTest`（既有，保持通过）
  - `DayNightParkingParityTest`（既有，保持通过）

## 背景

`BillingPlaygroundTest.scenario_cust2`（dayNight + CONTINUOUS + `splitDayNightBoundary=false`，dayBegin=08:03、dayEnd=12:09、计费 07:50–19:51）在结果的 **12:09（原始 dayEnd）** 处出现错误截断：单元明细被切成 `[11:50,12:09)@日价[截断]` + `[12:09,...)@夜价`，等价于在该处按 split=true 切断，违背 `splitDayNightBoundary=false`「不在日夜边界切断、跨日夜单元整体归属」的语义。

根因：`DayNightContinuousStrategy.createDayNightBoundaryProvider` 的 snap 分支中，当首个日夜边界（08:03 dayBegin）的跨边界单元 `[07:50,08:50)` 按 blockWeight 归属 day、snap 落点塌缩到 `current`（07:50，不满足 `isAfter(current)`）时，fallback **原样返回下一个原始日夜边界**（12:09 dayEnd），跳过了对它的 snap。于是原始 dayEnd 泄漏成硬边界。

该 snap 逻辑还存在两处隐患：
- blockWeight 归属在 snap（`belongsToDay`）与 `determineUnitPriceForContinuous`（BLOCK_WEIGHT 分支）两处重复实现，可能分叉。
- snap 固定按 blockWeight 归属，忽略 `crossPeriodMode`（如 PROPORTIONAL/HIGHER_PRICE），与 `buildSegment` 的真实定价不一致。

## 目标

- 消除 12:09（及任意原始日夜边界）的错误截断。
- 用更简单、唯一定价点的实现替换 snap：`splitDayNightBoundary=false` 时把「跨越日夜边界的单元」孤立成独立 segment，由 `buildSegmentForDayNight → determineUnitPriceForContinuous` 按 `crossPeriodMode` 定价。
- BLOCK_WEIGHT（默认）下单个跨边界单元的归属结果与旧 snap 保持一致（金额不变）；非 BLOCK_WEIGHT 模式下改为正确尊重该模式。

## 范围

包含：
- `DayNightContinuousStrategy.createDayNightBoundaryProvider` 的 `splitDayNightBoundary=false` 分支重写：只吐出跨边界单元的两条单元边（`unitStart`/`unitEnd`），不再数分钟、不判归属、无 fallback。
- 删除随之失效的私有辅助方法：`snapDayNightBoundary`、`countDayMinutes`、`isInDay`、`isDayBeginBoundaryPoint`。
- 新增回归测试 `DayNightContinuousNoSplitBoundaryTest`。
- 同步能力文档（中英）。

不包含：
- `splitDayNightBoundary=true`（默认切断）路径，保持不变。
- UNIT_BASED / DURATION_PERIOD / DURATION_GLOBAL 模式，不涉及。
- 单元对齐基准从 `current` 改为 `calcBegin` 的稳健性增强（既有脆弱点，与本次 bug 正交，另行评估）。

## 验收标准

- `scenario_cust2` 结果的单元边界不再出现原始 dayEnd 12:09；跨 dayEnd 的单元为 `[11:50,12:50)` 且按 blockWeight（白天 19/60 < 0.5）归夜价；跨 dayBegin 的单元为 `[07:50,08:50)` 且按 blockWeight（白天 47/60 ≥ 0.5）归日价。最终金额仍撞每日封顶 50.00。
- `DayNightContinuousNoSplitBoundaryTest` 通过。
- `DayNightContinuousCrossPeriodTest`（4 用例）、`DayNightParkingParityTest` 保持通过（BLOCK_WEIGHT 下金额不变）。

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightContinuousStrategy.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightPriceResolver.java`（定价唯一落点，未改动）
- `bill-test/src/test/java/cn/shang/charging/DayNightContinuousNoSplitBoundaryTest.java`（新增）
- `docs/billing-engine-capabilities.md` / `docs/billing-engine-capabilities-zh.md`

## 备注

- **等价性论证**：对单个完整单元，snap 归属条件 `dayMinutes >= blockWeight × unitMinutes` 与 `determineUnitPriceForContinuous` 的 BLOCK_WEIGHT 条件 `dayMinutes/duration >= blockWeight`（整单元 duration=unitMinutes）逐字等价，故 BLOCK_WEIGHT 下逐单元定价不变；变化的只是 segment 分组（跨边界单元单独成段，输出略不紧凑，已确认接受）。
- **行为变化点**：`splitDayNightBoundary=false` + 非 BLOCK_WEIGHT `crossPeriodMode` 时，旧 snap 仍按 blockWeight 归纯日/夜价（实为 bug），新实现按该 mode 定价（如 PROPORTIONAL 真正按比例）。默认 BLOCK_WEIGHT 不受影响。
- **provider 无死循环**：`return unitStart` 仅在 `unitIndex≥1`（unitStart>current）时到达；`current.equals(unitStart)` 时返回 `unitEnd>current`；边界恰在单元边时返回 `nearestBoundary>current`。
- 取代 `daynight-boundary-provider-pricing-architecture.md`（TODO-20260710-001）中描述的 snap 归属方案。

## 验证命令

```bash
# IDE 运行（JDK 21）：
#   DayNightContinuousNoSplitBoundaryTest
#   DayNightContinuousCrossPeriodTest
#   DayNightParkingParityTest
# 或重跑 BillingPlaygroundTest.main（scenario_cust2）观察单元明细无 12:09 截断。
```
