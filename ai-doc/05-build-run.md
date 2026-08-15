# 05-build-run — 构建 / 运行 / 配置场景引导

> 何时阅读：编译、启动服务、修改配置、排查环境问题（不含提交）时。

## 常用命令

```sh
./gradlew build        # 全量编译 + 测试
./gradlew test         # 仅测试
./gradlew run          # 启动开发服务（默认端口 8080）
./gradlew clean build  # 清理后重新构建
```

> Windows 用 `.\gradlew.bat` 或 `./gradlew`；要求 JDK 21。

## 配置

- 主配置：`src/main/resources/application.conf`（Ktor 配置）。
- **静态配置**（启动时读取、重启生效、含敏感项）：环境变量/密钥在仓库根 `.env`（参考 `.env.example`），
  由 `common/AppConfig` 读取。
- **动态配置**（运行期可调，无需重启）：存于 Redis 配置中心（key `config:{domain}`），
  通过管理 API `GET/PUT /system/config/{domain}` 修改，多实例经 Pub/Sub 失效，默认值兜底。见 `08-config.md`。
- 日志：`src/main/resources/logback.xml`。
- 端口默认 8080；改端口/环境通过配置或环境变量，勿硬编码。

## 依赖服务（本地运行必需）

- **MySQL**：生产数据库，连接串在 `.env`（`mysqlDsn`）。
- **Redis**：缓存（Lettuce 客户端），地址在 `.env`。

启动前确认 MySQL、Redis 可用；连不上会在启动或首次访问时抛错（见 `07-troubleshooting.md`）。

## 环境区分

- 配置里 `environment = dev` 时开启 SQL 日志（`StdOutSqlLogger`）等调试输出。
- 测试环境走 H2，不读取真实 `.env` 依赖（见 `04-testing.md`）。

## 检查清单

- [ ] 命令是否在仓库根目录执行？
- [ ] `.env` 是否存在且 MySQL / Redis 可连接？
- [ ] 端口 8080 未被占用？
- [ ] 构建/启动失败时按 `07-troubleshooting.md` 排查？
