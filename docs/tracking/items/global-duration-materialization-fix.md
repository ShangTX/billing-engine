# GLOBAL 时长模式 FREE_MINUTES 时段化修复

---
id: TODO-20260706-001
type: bug
priority: P1
status: done
source_git: dfaa576
created_at: 2026-07-06
completed_at: 2026-07-06
completed_git: 610d656
---

## 背景

GLOBAL 时长模式下,`DayNightDurationStrategy` 用 `deductFreeMinutesGlobal` 处理 FREE_MINUTES——不时段化,直接从窗口起点顺序扣减 `chargedMinutes`。

历史推论演进:DURATION 不需时段化 → GLOBAL 不需 → **GLOBAL 也需**。

验证结论:`deductFreeMinutesGlobal` 与 `FreeMinuteAllocator` 用同一分配策略(从窗口起点顺序消费),**最终金额(含每 period 金额)等价**。问题不在金额:

1. **DurationSegment 失去同质性**:一个段时间跨度 240min、chargedMinutes=180,免费部分藏在段内不独立,违背"同质段"契约,可追溯性受损
2. **业务语义焊死**:"从窗口起点消费"硬编码在扣减循环,未来改分配策略会静默算错

spec [2026-07-02 3.3](../superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md) 有范畴错误:从"封顶不需时间位置"正确推出"金额不需时段化",却错误推广到"明细也不需时段化"。封顶确实不需(GLOBAL 全局倍乘),但明细产出需(保证段同质)。

详见 [2026-07-06 spec 3.1](../superpowers/specs/2026-07-06-rule-abstraction-refactor-design.md)。

## 目标

GLOBAL 模式改走前置时段化(复用 `materializeFreeMinutes`),删 `deductFreeMinutesGlobal` 与 `buildDurationSegmentsGlobalMode` 的分钟扣减路径。免费段独立成段,DurationSegment 恢复同质。

## 范围

### 包含

- `DayNightDurationStrategy.calculate` 的 GLOBAL 分支改前置时段化
- 删 `deductFreeMinutesGlobal`
- `buildDurationSegmentsGlobalMode` 改为接收时段化后的 freeTimeRanges(与 PERIOD 一致),仅封顶数学不同
- 纠正 spec 3.3 表述

### 不包含

- 主体规则抽象重构(见 [rule-abstraction-refactor.md](rule-abstraction-refactor.md))
- SMART_FREE_MINUTES(决策 D,主体重构阶段 5)
- 其他规则族的 DurationMode 接入(主体重构阶段 4)

## 验收标准

- 现有 GLOBAL 模式测试金额不变(`DurationBillingModeTest` 等)
- DurationSegment 同质:免费段时间跨度 = chargedMinutes(0),收费段时间跨度 = chargedMinutes
- 明细:免费段独立,不再"揉进"收费段
- `deductFreeMinutesGlobal` 及其单测删除
- spec 3.3 表述已纠正

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightDurationStrategy.java`(GLOBAL 路径 + 删 deductFreeMinutesGlobal)
- `docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md`(3.3 纠正)
- `bill-test/src/test/java/cn/shang/charging/DurationBillingModeTest.java`(验证)
- `bill-test/src/test/java/cn/shang/charging/FreeMinutesMaterializationTest.java`(验证)

## 备注

- 此修复独立于主体重构,可先行(降风险前置)
- 修复后 GLOBAL 与 PERIOD 的 FREE_MINUTES 处理统一(都前置时段化),差异收敛到封顶数学,为主体重构阶段 4(拆两个时长策略)铺路
- 验证命令:`mvn -t C:/Users/shang/.m2/toolchains.xml test -pl bill-test -Dtest=DurationBillingModeTest,FreeMinutesMaterializationTest`
