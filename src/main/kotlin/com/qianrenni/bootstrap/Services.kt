package com.qianrenni.bootstrap

/**
 * 应用服务组合根（手工组合根 + 领域分组模式）。
 *
 * 顶层只聚合领域服务组（见 [ServiceGroups]），各领域组内的服务由
 * `ServiceRegistrar.configService` 按依赖顺序装配：
 * - Service 构造函数的参数即为它的全部依赖，可读、可测、可替换；
 * - Controller / 定时任务通过参数注入拿到 `services.<group>.<service>` 所需依赖，
 *   不再依赖 Application 的隐式扩展属性；
 * - 基础设施（DatabaseManager / RedisManager / AppConfig / Logger）仍由
 *   `Application.databaseManager` 等扩展属性提供，不重复挂载。
 *
 * 由 [Application.configService] 创建并挂到 Application attributes，
 * 通过 `Application.services` 访问（插件等跨切面代码的唯一入口）。
 */
class Services(
    val infra: InfraServices,
    val user: UserServices,
    val book: BookServices,
    val admin: AdminServices,
    val author: AuthorServices,
    val system: SystemServices,
)
