# 移除冗余的 CrossPeriodMode.BEGIN_TIME_TRUNCATE

## 元数据

- **ID**: TODO-20260722-002
- **类型**: refactor
- **优先级**: P3
- **状态**: done
- **创建时间**: 2026-07-22
- **创建提交**: 5d51764
- **完成时间**: 2026-07-22
- **完成提交**: cd6ccf2

## 背景

`CrossPeriodMode.BEGIN_TIME_TRUNCATE`（语义注释"取开始时间价格，并用自然时段边界截断单元"）在现有边界驱动体系下已无独立意义：

- **定价与 `BEGIN_TIME_PRICE` 完全相同**：`DayNightPriceResolver.determineFinalAmount` 中两者同处一个 case 分支，都走 `getBeginTimePrice(begin, config)`，"截断"并未在定价器中实现。
- **"截断单元"职责已被 `splitDayNightBoundary` 承担**：CONTINUOUS 下 `splitDayNightBoundary=true` 在日夜边界切断单元（即"截断"），`false` 则把跨边界单元孤立成段按 `crossPeriodMode` 定价。是否截断由 `splitDayNightBoundary` 表达，`BEGIN_TIME_TRUNCATE` 不再承担任何独占语义。
- 全仓库仅枚举定义与 resolver case 两处引用，无任何测试、配置或外部场景使用。

## 目标

删除冗余常量，收敛 `CrossPeriodMode` 到真正有区分度的 6 种模式，避免调用方误用一个与 `BEGIN_TIME_PRICE` 等价、且"截断"名不副实的选项。

## 范围

包含：
- `CrossPeriodMode` 删除 `BEGIN_TIME_TRUNCATE` 常量及 javadoc。
- `DayNightPriceResolver.determineFinalAmount` 的 case 分支去掉 `BEGIN_TIME_TRUNCATE`（保留 `BEGIN_TIME_PRICE, BLOCK_WEIGHT`）。
- 标注历史 tracking item `cross-period-unit-handling.md` 的模式表（七种→6 种）。

不包含：
- 其余 6 种模式（`BLOCK_WEIGHT`/`BEGIN_TIME_PRICE`/`END_TIME_PRICE`/`HIGHER_PRICE`/`LOWER_PRICE`/`PROPORTIONAL`）行为不变。
- 非 BLOCK_WEIGHT 模式缺乏测试覆盖、跨午夜段恒判 MIXED 的潜在定价问题（另行评估，见 TODO-20260722-001 备注）。

## 验收标准

- 全仓库无 `BEGIN_TIME_TRUNCATE` 残留引用；`core` 编译通过；`DayNightPriceResolver` switch 对剩余 6 个常量仍穷尽。
- 既有 dayNight 测试（`DayNightContinuousCrossPeriodTest` / `DayNightParkingParityTest` / `DayNightContinuousNoSplitBoundaryTest`）保持通过（均用默认 BLOCK_WEIGHT，不受影响）。

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CrossPeriodMode.java`
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightPriceResolver.java`
- `docs/tracking/items/cross-period-unit-handling.md`

## 备注

- **破坏性 API 变更**：`CrossPeriodMode` 为公开枚举（已随 3.0.2 发布到 Maven Central），删除常量会破坏引用 `CrossPeriodMode.BEGIN_TIME_TRUNCATE` 的调用方（源码与二进制兼容）。发布时建议主版本号升级（如 4.0.0）。若需保守，可改为先 `@Deprecated` 标注、下个主版本再移除——本次按项目所有者指示直接移除。
- README / USER_GUIDE / 能力文档均未枚举 `CrossPeriodMode` 具体取值（仅引用类型名），故无需更新值清单。

## 验证命令

```bash
# IDE 编译 core；grep 全仓库确认无 BEGIN_TIME_TRUNCATE 残留；
# 运行 DayNightContinuousCrossPeriodTest / DayNightParkingParityTest / DayNightContinuousNoSplitBoundaryTest。
```
