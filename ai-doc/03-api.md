# 03-api — HTTP API / 权限场景引导

> 何时阅读：新增/修改路由、调整认证与权限、定义请求/响应 DTO 时。

## 路由结构

- 每个模块的 Controller 是 `Routing` 扩展函数，如 `fun Routing.user(...)`，内部用 `route("/user") { ... }` 分组。
- 在 `bootstrap` 的路由装配处调用该扩展并传入所需 Service（参考 `Application.kt` / `Routing.kt`）。

```kotlin
fun Routing.user(userService: UserService, captchaService: CaptchaService) {
    route("/user") {
        get("/count") { ... }
        post("/register") { ... }
    }
}
```

## 认证（需要登录的接口）

- 需要登录的路由用 `authenticate("auth-jwt") { ... }` 包裹。
- 公开接口（如验证码、注册、登录）不包认证块。
- 当前用户信息通过 JWT 携带（参考现有 `AuthController` / 用户相关接口的取法）。

## 权限（细粒度授权）

统一用 `common/web` 提供的权限机制：

```kotlin
call.requirePermission(
    listOf(
        generatePermissionCode(
            resource = ResourceTypeEnum.XXX,
            action = ActionEnum.READ,      // 或 WRITE / DELETE 等
            scope = ScopeEnum.ALL,          // 或 SELF 等
        )
    )
)
```

- `ResourceTypeEnum` / `ActionEnum` / `ScopeEnum` 定义在 `common/AppEnums.kt`。
- 新增资源类型/动作需同步枚举与权限数据；先查是否已有对应枚举，避免重复定义。

## 请求 / 响应

- 请求/响应 DTO 用 `@Serializable data class`（如 `RegisterUser`），通过 `call.receive<T>()` 反序列化。
- 响应统一包装为 `com.qianrenni.common.ResponseModel`（`call.respond(...)`）。
- 参数来源：路径 `call.parameters`、查询 `call.request.queryParameters`、请求体 `call.receive<T>()`。
- 错误：抛业务异常，由 `common/web/StatusPages.kt` 统一转换为 `ResponseModel` 错误响应；勿在各 Controller 重复写错误处理样板。

## 检查清单

- [ ] 新增路由已挂载到路由装配处？
- [ ] 需登录的接口是否包了 `authenticate("auth-jwt")`？
- [ ] 敏感操作是否加了 `requirePermission`，枚举是否复用现有值？
- [ ] DTO 是否 `@Serializable`，响应是否用 `ResponseModel`？
- [ ] `documentation.yaml`（OpenAPI）是否需要同步？
