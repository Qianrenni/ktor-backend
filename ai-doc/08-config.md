# 08-config — 动态配置场景引导

> 何时阅读：新增/修改可运行期调整的配置项、排查配置不生效、理解配置分层时。

## 配置分层

| 类型 | 来源 | 生效时机 | 示例 |
| ---- | ---- | -------- | ---- |
| 静态配置 | 环境变量（`.env` → `common/AppConfig`） | 启动时读取，重启生效 | `MYSQL_DSN`、`REDIS_URL`、`SECRET_KEY`、`SMTP_*` |
| 动态配置 | Redis 配置中心（`config:{domain}`） | 运行期可调，写后即失效/订阅生效 | 限流、缓存过期、compact 阈值 |

- **敏感项一律不进动态区**（密钥、连接串等走环境变量）。

## 架构（Redis 配置中心 + 本地缓存 + 按域失效 + 默认值兜底）

```mermaid
flowchart LR
    Admin[管理 API PUT /system/config/{domain}] -->|合并+校验+写 Redis| R[(Redis config:{domain})]
    Admin -->|失效本地| L1[ConfigService]
    R -->|PUBLISH config:change| P{Pub/Sub}
    P -->|其他实例失效| L2[ConfigService]
    S[业务读取 get] -->|命中| L[本地缓存]
    L -->|未命中回源| R
    R -->|无值/不可达/解析失败→默认值兜底| D[DEFAULT]
```

- **本实例**：`PUT` 写 Redis 后立即失效本地缓存，下次读取回源；
- **其它实例**：订阅 `config:change` 频道收到通知后按域失效；
- **兜底**：Redis 无值/不可达/解析失败 → 返回默认值，且**不缓存**（Redis 恢复后自动取到最新）。

## 相关代码

- `common/config/ConfigDomain.kt` — 领域枚举（`RATE_LIMIT` / `CACHE` / `COMPACT`）+ 默认值。
- `common/config/DynamicConfig.kt` — 各领域不可变配置类（`RateLimitConfig` / `CacheConfig` / `CompactConfig`），
  含严格 `parse`（校验）与 `toValues()`。
- `common/config/ConfigService.kt` — 本地缓存 + `get`/`update`/`invalidate` + 默认值兜底。
- `common/config/ConfigSource.kt` — 数据源抽象；生产实现 `infrastructure/config/RedisConfigSource.kt`
  （读 `HGETALL`、写 `DEL+HSET`、发布订阅 `config:change`）。
- 管理入口：`modules/system/SystemConfigService.kt` + `SystemConfigController.kt`。

## 使用方式

### 1. 修改现有动态配置（运维）

```text
GET /system/config                       # 查看所有领域的当前生效配置
PUT /system/config/compact               # 更新某领域（部分字段合并，未传字段沿用当前值）
body: {"garbageThreshold": "0.5"}
```

权限：`PERMISSION:READ:ALL`（读）/ `PERMISSION:UPDATE:ALL`（写），均需登录。

### 2. 业务代码消费动态配置

```kotlin
// 注入 ConfigService（组合根已装配，见 InfraServices.configService）
class ContentStoreCompactor(
    private val config: AppConfig,
    private val configService: ConfigService,
    // ...
) {
    suspend fun compactAll(force: Boolean = false) {
        val dynamic = configService.compact()   // 读取动态阈值
        // ...
    }
}
```

### 3. 新增一个动态配置领域

1. `common/config/DynamicConfig.kt` 新增 `data class XxxConfig`（默认值 + 严格 `parse` + `toValues()`）；
2. `common/config/ConfigDomain.kt` 新增枚举值，`defaultValues()` 关联默认值；
3. `ConfigService` 增加便捷读取方法（`fun xxx() = get(...)`），并在 `update`/`validate` 的 `when` 中登记；
4. 消费方注入 `ConfigService` 调用；
5. 补测试：`common/config/TestConfigService.kt`（可用 `testutil/InMemoryConfigSource`）。

## 注意事项

- 动态配置写入覆盖整组配置（`save` 用 `DEL + HSET`）；`PUT` 会先合并当前生效值再严格校验。
- 校验失败（`parse` 返回 null）拒绝写入，返回错误响应。
- 测试环境 Redis 不可达时：配置订阅启动失败被容错跳过，读取回退默认值，不影响用例。
- 单元测试**不要依赖真实 Redis**，用 `testutil/InMemoryConfigSource` 注入 `ConfigService`。
- `ConfigService.get` 仅缓存「成功解析」的结果，兜底值不会被缓存。

## 检查清单

- [ ] 敏感配置未放入动态区？
- [ ] 新增领域时 `ConfigDomain` / `DynamicConfig` / `ConfigService` 三处已同步？
- [ ] 管理 API 有权限保护？
- [ ] 测试使用 `InMemoryConfigSource`，不依赖真实 Redis？
