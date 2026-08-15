# 02-database — 数据库 / Exposed 场景引导

> 何时阅读：新增/修改表、编写查询、涉及事务、执行迁移时。

## 表定义（Exposed）

- 新表定义在 `models/tables/`（如 `user.kt`、`book.kt`），为一个继承 `org.jetbrains.exposed.sql.Table` 的 object。
- 列类型使用 Exposed 映射（`varchar`/`integer`/`long`/`datetime`/`bool`/`json` 等），并给主键、索引、外键。
- 表名与列名遵循现有命名习惯（参考同目录已有表）。
- 领域对象（DTO/枚举）放 `models/domain/`，与表定义分离。

## 事务

使用 `DatabaseManager`（位于 `infrastructure/database/Database.kt`）：

```kotlin
// 同步
db.transaction { UserTable.selectAll().toList() }

// 挂起（协程环境）
suspendTransaction { ... }   // 即 db.suspendedTransaction { ... }
```

- Service 通过构造参数拿到 `db`（或直接依赖需要的 Repo/Service），在方法内开启事务。
- 涉及多表写操作时保证在同一事务内；只读操作可用 `readOnly = true`。
- 生产 MySQL、测试 H2 —— 避免依赖 MySQL 特有 SQL/函数，保证跨库可用。

## 新增/修改表后必须同步

- `database.sql`（仓库根）：追加/修改对应的 `CREATE TABLE` / `ALTER TABLE` DDL，与 Exposed 表定义保持一致。
- 测试环境使用 H2，若测试涉及新表，参考现有测试如何建表/清理（见 `04-testing.md`）。

## 查询与写入建议

- 优先用类型安全的 Exposed DSL（`Table.select {}` / `insert` / `update` / `deleteWhere`），避免裸 SQL。
- 涉及分页、统计参考 `modules/book/StatisticsService.kt`、`ShelfService.kt` 等现有实现。
- 大批量写入考虑分批；连接池参数在 `DatabaseManager` 已集中配置，勿在代码里另起连接。

## 检查清单

- [ ] 表定义在 `models/tables/` 且符合现有风格？
- [ ] 事务用 `DatabaseManager.transaction` / `suspendedTransaction`？
- [ ] `database.sql` 已同步 DDL？
- [ ] 没有 MySQL 专有语法导致 H2 测试失败？
