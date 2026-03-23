# Spring Boot Starter 多版本支持设计

## 概述

创建两个独立的 Spring Boot Starter 模块，分别支持不同版本的 Spring Boot，长期并行维护。

---

## 模块结构

```
charge/
├── core/                              # 核心计费引擎（不变）
├── billing-api/                       # 便捷 API（不变）
├── billing-v3-spring-boot-starter/    # 重命名自 billing-spring-boot-starter
│   ├── pom.xml                        # Spring Boot 3.x（3.5 以下），JDK 21
│   └── src/main/java/.../autoconfigure/
│       ├── BillingAutoConfiguration.java
│       └── BillingProperties.java
├── billing-v4-spring-boot-starter/    # 新建
│   ├── pom.xml                        # Spring Boot 3.5/4.x，JDK 21/23/25
│   └── src/main/java/.../autoconfigure/
│       ├── BillingAutoConfiguration.java
│       └── BillingProperties.java
└── bill-test/                         # 测试模块（不变）
```

---

## 版本范围

| Starter | Spring Boot | JDK | 状态 |
|---------|-------------|-----|------|
| billing-v3-spring-boot-starter | 3.0.x - 3.4.x | 21 | 活跃维护 |
| billing-v4-spring-boot-starter | 3.5.x - 4.x | 21/23/25 | 活跃维护 |

### 版本边界说明

Spring Boot 3.5 归入 v4 的原因：
1. **JDK 要求变更**：Spring Boot 3.5+ 支持 JDK 23/25，而 3.4 及以下仅支持 JDK 21
2. **API 变更**：Spring Boot 3.5 引入了部分新 API，与 4.x 保持一致
3. **命名简化**：避免命名混乱（如 billing-v3.5-spring-boot-starter）

---

## 设计决策

### 1. 共享核心，独立 starter

两个 starter 都依赖 `billing-api` 模块，各自只包含 Spring Boot 版本相关的自动配置代码。

**优势：**
- 保证功能一致性
- 避免代码重复
- 降低维护成本

### 2. 包名保持一致

两个 starter 使用相同的包名 `cn.shang.charging.spring.boot.autoconfigure`。

**用户迁移：** 只需更换 Maven 依赖的 artifactId，代码无需修改。

### 3. 长期并行维护

两个版本都会持续迭代，修复 bug、添加新功能。

### 4. 每个模块独立管理 Spring Boot 版本

各个 starter 在自己的 pom.xml 中定义 `spring-boot.version` 属性，不由父 POM 统一管理。

**原因：** 不同 starter 需要不同版本的 Spring Boot，独立管理更灵活。

### 5. 版本号与父项目一致

两个 starter 的版本号与 `charge` 父项目保持一致（如 `0.0.1-SNAPSHOT`）。

**原因：** 简化版本管理，用户只需关注选择哪个 starter，不需要关心版本号差异。

---

## 依赖配置

### billing-v3-spring-boot-starter

```xml
<artifactId>billing-v3-spring-boot-starter</artifactId>
<properties>
    <spring-boot.version>3.2.0</spring-boot.version>
    <java.version>21</java.version>
</properties>
<dependencies>
    <dependency>
        <groupId>cn.shang</groupId>
        <artifactId>billing-api</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### billing-v4-spring-boot-starter

```xml
<artifactId>billing-v4-spring-boot-starter</artifactId>
<properties>
    <spring-boot.version>4.0.0</spring-boot.version>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
</properties>
<dependencies>
    <dependency>
        <groupId>cn.shang</groupId>
        <artifactId>billing-api</artifactId>
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

---

## 自动配置类

两个 starter 的 `BillingAutoConfiguration` 和 `BillingProperties` 功能相同，代码基本一致。

### BillingAutoConfiguration

主要功能：
- 注册 `BillingRuleRegistry`（内置计费规则）
- 注册 `PromotionRuleRegistry`（内置优惠规则）
- 注册 `PromotionEngine`、`FreeTimeRangeMerger`、`FreeMinuteAllocator`
- 注册 `BillingService`
- 注册 `BillingTemplate`

### BillingProperties

主要功能：
- Scheme 元配置（未来扩展）
- 目前保持与现有实现一致

---

## AutoConfiguration.imports 文件

**路径：** `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**内容：**
```
cn.shang.charging.spring.boot.autoconfigure.BillingAutoConfiguration
```

---

## Spring Boot 版本差异

两个 starter 的代码完全相同，不需要针对 Spring Boot 3.5/4.x 做特殊处理。

**原因：** 当前使用的 Spring Boot API（`@Configuration`、`@Bean`、`@ConditionalOnClass` 等）在 3.x 和 4.x 中保持稳定，无破坏性变更。

---

## 改动清单

| 操作 | 文件/目录 | 说明 |
|------|----------|------|
| 重命名 | `billing-spring-boot-starter/` → `billing-v3-spring-boot-starter/` | 现有模块改名 |
| 修改 | `billing-v3-spring-boot-starter/pom.xml` | 更新 artifactId |
| 新建 | `billing-v4-spring-boot-starter/` | 新 starter 模块 |
| 新建 | `billing-v4-spring-boot-starter/pom.xml` | Spring Boot 4.x 配置 |
| 新建 | `billing-v4-spring-boot-starter/src/.../BillingAutoConfiguration.java` | 自动配置类 |
| 新建 | `billing-v4-spring-boot-starter/src/.../BillingProperties.java` | 属性配置类 |
| 新建 | `billing-v4-spring-boot-starter/src/.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 自动配置注册 |
| 修改 | `pom.xml`（根目录） | 添加新模块 |
| 修改 | `CLAUDE.md` | 添加两个 starter 的文档说明 |

---

## 用户迁移说明

两个 starter API 完全相同，迁移只需更换 Maven 依赖：

```xml
<!-- v3 版本 -->
<dependency>
    <groupId>cn.shang</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
</dependency>

<!-- v4 版本 -->
<dependency>
    <groupId>cn.shang</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
</dependency>
```

---

## JDK 兼容性说明

| Starter | 编译 JDK | 运行时 JDK |
|---------|---------|-----------|
| billing-v3-spring-boot-starter | JDK 21 | JDK 21 |
| billing-v4-spring-boot-starter | JDK 21 | JDK 21/23/25 |

**说明：**
- v4 starter 使用 JDK 21 编译，确保 class 文件兼容性
- 运行时可使用更高版本 JDK（JVM 向后兼容）

---

## 维护流程

### 功能同步

新功能开发流程：
1. 在 `billing-api` 模块实现功能
2. 两个 starter 无需修改（自动获得新功能）
3. 如需新的配置项，同步修改两个 starter 的 `BillingProperties`

### Bug 修复

1. `billing-api` 或 `core` 的 bug：修复后两个 starter 自动生效
2. starter 特有 bug（如配置问题）：分别修复

---

## 测试策略

### 当前阶段

- `bill-test` 模块继续使用 `core` 和 `billing-api` 进行测试
- 两个 starter 主要在用户项目中验证

### 未来扩展

如需针对不同 Spring Boot 版本的集成测试：
1. 创建 `billing-starter-test-v3` 和 `billing-starter-test-v4` 模块
2. 使用不同的 Spring Boot 版本运行集成测试
3. CI 中并行执行两套测试