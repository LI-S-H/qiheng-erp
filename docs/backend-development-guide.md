# 后端开发规范

## 1. 文档定位

本文档只约束启衡 ERP 的后端开发，包括模块结构、代码分层、接口契约、数据库操作和异常处理。前端代码规范应单独成文，不得混入本文档。

每次新增或修改后端代码前，必须先阅读：

1. `docs/backend-development-guide.md`（本文档）
2. 当前业务模块在 `docs/database/` 下的数据库设计
3. `docs/api/erp-openapi.yaml` 中对应接口
4. `docs/database/database-design-conventions.md` 数据库设计约定
5. `docs/erp-core-architecture-thinking.md` 模块边界和依赖关系

若接口契约、数据库设计和业务逻辑互相冲突，先停止编码，说明冲突并修正文档契约，不得用代码绕过。

## 2. 模块结构与依赖

### 2.1 模块依赖链

```
erp-admin → erp-security, erp-system, erp-product, erp-warehouse, erp-purchase, erp-sales
erp-security → erp-common
erp-system → erp-common, erp-security
erp-product → erp-common
erp-warehouse → erp-common
erp-purchase → erp-common, erp-product, erp-warehouse, erp-system
erp-sales → erp-common, erp-product, erp-warehouse, erp-system
erp-ai → erp-common, erp-security, erp-product, erp-warehouse, erp-purchase, erp-sales
erp-job → erp-common
```

### 2.2 依赖原则

- **单向依赖**：上层模块可以依赖下层模块，下层模块不得依赖上层模块。
- **禁止循环依赖**：erp-warehouse 不能依赖 erp-purchase，erp-product 不能依赖 erp-system。
- **跨模块调用必须通过 Service**：采购入库必须调用仓储模块的 Service，不能直接操作仓储模块的 Mapper。
- **AI 模块不直接写表**：AI Tool 必须调用已有业务 Service，不直接查表或写表。

### 2.3 模块内部标准分层

```
com.qiheng.erp.xxx
├── controller/       REST 接口，只做参数校验和调用 Service
├── service/          业务逻辑
│   └── impl/
├── mapper/           MyBatis-Plus Mapper 接口
├── domain/           实体类（对应数据库表）
├── dto/              请求 DTO 和响应 DTO
└── enums/            模块内枚举
```

## 3. 代码分层职责

### 3.1 Controller 层

- 只做参数接收、`@Valid` 校验、调用 Service、返回 `Result<T>`。
- **禁止**在 Controller 写业务逻辑。
- **禁止**在 Controller 直接调用 Mapper。
- 分页查询参数继承 `PageQuery`，响应使用 `PageResult<T>`。

```java
@RestController
@RequestMapping("/system/users")
@Validated
public class SystemUserController {

    private final SystemUserService userService;

    @GetMapping
    public Result<PageResult<SystemUserDTO>> page(SystemUserQuery query) {
        return Result.ok(userService.page(query));
    }

    @PostMapping
    public Result<Void> create(@Valid @RequestBody SystemUserCreateRequest request) {
        userService.create(request);
        return Result.ok();
    }
}
```

### 3.2 Service 层

- 承载所有业务逻辑和事务控制。
- 格式校验由 `@Valid` 完成，**业务校验**（重复、关联、状态）用 `if + throw BizException`。
- 跨模块调用必须注入对方模块的 Service，不能注入对方模块的 Mapper。
- Service 方法必须加 `@Transactional`，只读方法加 `@Transactional(readOnly = true)`。

```java
@Service
public class SystemUserServiceImpl implements SystemUserService {

    @Override
    @Transactional(readOnly = true)
    public PageResult<SystemUserDTO> page(SystemUserQuery query) {
        // 查询 + 转换
    }

    @Override
    @Transactional
    public void create(SystemUserCreateRequest request) {
        // 业务校验
        if (userMapper.existsByUsername(request.getUsername())) {
            throw new BizException(ErrorCode.USER_PASSWORD_ERROR); // 示例，实际用对应错误码
        }
        // 保存
    }
}
```

### 3.3 Mapper 层

- 继承 `BaseMapper<T>`，只写自定义 SQL。
- 简单 CRUD 用 MyBatis-Plus 内置方法，不写 XML。
- 复杂查询（多表关联、聚合统计）写自定义方法 + XML。

### 3.4 Domain（Entity）层

- 和数据库表字段一一对应，用 `@TableName`、`@TableId`、`@TableField` 注解。
- **禁止**在 Entity 里加前端展示字段（如 `deptName`、`roleNames`）。
- 数量字段：评分/百分率用 `int`（100 倍整数），金额用 `BigDecimal`，数量用 `BigDecimal`。

```java
@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String passwordHash;
    private String realName;
    private Long deptId;
    private Integer isAdmin;
    private Integer status;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

### 3.5 DTO 层

- **请求 DTO**：前端提交的数据，加 `@Valid` 校验注解。分页查询继承 `PageQuery`。
- **响应 DTO**：返回前端的数据，ID 字段用 `String` 类型（防 JS 精度丢失），时间字段用 `String`（格式化后返回）。
- **请求 DTO 和响应 DTO 必须分开**：创建请求有 password，响应没有；响应有 deptName，请求没有。

## 4. 接口契约规范

### 4.1 统一返回格式

所有接口必须返回 `Result<T>`：

```json
// 成功
{ "code": 0, "message": "success", "data": { ... } }

// 业务失败
{ "code": 30001, "message": "用户名或密码错误", "data": null }

// 分页
{ "code": 0, "message": "success", "data": { "records": [...], "total": 100, "pageNum": 1, "pageSize": 10 } }
```

### 4.2 HTTP 状态码使用

| 场景 | HTTP 状态码 | 说明 |
|---|---|---|
| 业务校验失败 | 200 + code != 0 | 前端在成功回调里检查 code |
| 参数格式错误 | 400 | `@Valid` 校验失败 |
| 未登录/token 过期 | 401 | Sa-Token 拦截 |
| 无权限 | 403 | 权限码校验失败 |
| 资源不存在 | 404 | 查询数据为空 |
| 唯一键冲突 | 409 | 并发重复提交 |
| 服务器异常 | 500 | 未知异常 |

### 4.3 查询接口规范

- 一个筛选参数对应一个明确的数据库字段或关联字段。
- 禁止使用含义不明的 `keyword` 同时匹配多个字段。
- 字符串筛选使用包含匹配（LIKE），状态和 ID 筛选使用精确匹配。
- 分页参数统一使用 `pageNum`（从 1 开始）和 `pageSize`。
- 未传的筛选参数表示不限制该字段，不得把空字符串或 `all` 当作有效值。

### 4.4 层级数据规范

- 部门、产品分类等层级数据返回**扁平列表**，不返回 `children`、`parentName` 或层级路径。
- 前端根据 `parentId` 构建树。
- 产品按分类查询时，选中父分类表示查询该分类及全部后代分类，后端负责解析后代 ID。

### 4.5 编码生成规则

- 产品编码、仓库编码、供应商编码、客户编码、业务单号由后端统一生成。
- 编码生成使用唯一索引兜底，不允许前端提交编码字段。

## 5. 数据库操作规范

### 5.1 主键策略

- 统一使用 `bigint` + MyBatis-Plus `ASSIGN_ID`（雪花算法）。
- ID 在 JSON 中序列化为 `String`，防止 JS 精度丢失。

### 5.2 逻辑删除

- 主数据、配置表和可删除的业务主表使用 `deleted tinyint`，`0` 正常，`1` 删除。
- 关系表、订单明细表、库存余额表、入库/出库作业明细、库存流水表、审计日志表**不使用逻辑删除**。
- MyBatis-Plus `@TableLogic` 注解自动处理，查询时自动加 `WHERE deleted = 0`。

### 5.3 数值字段

- 金额：`decimal(18,2)`，Java 用 `BigDecimal`。
- 数量：`decimal(18,4)`，Java 用 `BigDecimal`。
- 评分/百分率：`int`（100 倍整数），Service 层做转换，接口用真实值。
- **禁止**用 `float` 或 `double` 存金额和数量。

### 5.4 时间字段

- `create_time`：`DEFAULT CURRENT_TIMESTAMP`，数据库自动填充。
- `update_time`：`DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`，数据库自动更新。
- 业务动作时间（`confirmed_at`、`last_login_at` 等）由业务代码显式写入，不用自动更新时间代替。

### 5.5 状态字段

- 统一使用 `tinyint`，`1` 启用/正常，`0` 停用/禁用。
- 状态流转必须按业务规则校验，禁止随意修改状态。

## 6. 异常处理规范

### 6.1 异常分类

| 异常类型 | 触发方式 | 处理方式 |
|---|---|---|
| 格式校验失败 | `@Valid` 注解 | GlobalExceptionHandler 自动捕获，返回 400 |
| 业务校验失败 | `throw new BizException(ErrorCode.XXX)` | GlobalExceptionHandler 自动捕获，返回 200 + code |
| 未登录 | Sa-Token 拦截 | 返回 401 |
| 未知异常 | 运行时异常 | GlobalExceptionHandler 兜底，返回 500 |

### 6.2 错误码分段

```
1xxxx  通用错误（参数错误、数据不存在、数据重复）
2xxxx  认证错误（未登录、无权限、Token 无效）
3xxxx  系统模块（用户、角色、部门、权限）
4xxxx  产品模块（分类、产品）
5xxxx  仓库模块（仓库、库存、出入库）
6xxxx  采购模块（供应商、采购订单）
7xxxx  销售模块（客户、销售订单）
99999  未知错误
```

### 6.3 异常信息原则

- 业务异常返回具体的中文提示，如"同级别下分类名称已重复"。
- 未知异常只返回"系统内部错误"，**禁止**把堆栈信息、SQL 语句暴露给前端。
- 异常详情只记录在后端日志中。

## 7. 事务规范

### 7.1 事务边界

- Service 方法是事务的最小单位。
- Controller 不加 `@Transactional`。
- 跨模块调用（如采购入库 → 仓储库存更新）必须在同一个 Service 方法内完成，保证事务一致性。

### 7.2 只读事务

- 查询方法必须加 `@Transactional(readOnly = true)`，数据库可以进行优化。

### 7.3 事务传播

- 默认使用 `REQUIRED`，不需要显式指定。
- 禁止使用 `REQUIRES_NEW` 除非有明确的事务隔离需求。

## 8. 安全规范

### 8.1 认证

- 使用 Sa-Token 原始 UUID token。
- Token 存储在 Redis Session 中。
- 前端通过 `satoken` 请求头传递 token。

### 8.2 权限

- 使用权限码模式，角色绑定权限码 JSON。
- 接口权限校验使用 `@SaCheckPermission("system:user:create")`。
- 超级管理员（`isAdmin = true`）跳过权限校验。

### 8.3 密码

- 密码存储使用哈希（Hutool `SecureUtil.md5()` 或 BCrypt），禁止明文存储。
- 密码字段不返回给前端。

## 9. 命名规范

### 9.1 包命名

```
com.qiheng.erp.{模块名}.controller
com.qiheng.erp.{模块名}.service
com.qiheng.erp.{模块名}.service.impl
com.qiheng.erp.{模块名}.mapper
com.qiheng.erp.{模块名}.domain
com.qiheng.erp.{模块名}.dto
com.qiheng.erp.{模块名}.enums
```

### 9.2 类命名

| 类型 | 命名规则 | 示例 |
|---|---|---|
| Entity | 和表名对应，驼峰 | `SysUser`、`ProductCategory` |
| 请求 DTO | 动作 + Request | `SystemUserCreateRequest`、`SystemUserUpdateRequest` |
| 响应 DTO | 业务名 + DTO | `SystemUserDTO`、`ProductDTO` |
| 查询 DTO | 业务名 + Query | `SystemUserQuery`、`ProductQuery` |
| Service 接口 | 业务名 + Service | `SystemUserService` |
| Service 实现 | 业务名 + ServiceImpl | `SystemUserServiceImpl` |
| Mapper | 业务名 + Mapper | `SystemUserMapper` |
| Controller | 业务名 + Controller | `SystemUserController` |

### 9.3 方法命名

| 操作 | 方法名 | 示例 |
|---|---|---|
| 分页查询 | `page` | `page(SystemUserQuery)` |
| 详情查询 | `getById` / `getDetail` | `getById(Long)` |
| 新增 | `create` | `create(SystemUserCreateRequest)` |
| 修改 | `update` | `update(Long, SystemUserUpdateRequest)` |
| 修改状态 | `updateStatus` | `updateStatus(Long, Integer)` |
| 删除 | `delete` | `delete(Long)` |
| 批量修改状态 | `batchUpdateStatus` | `batchUpdateStatus(List<Long>, Integer)` |
| 批量删除 | `batchDelete` | `batchDelete(List<Long>)` |

## 10. 开发检查清单

编写代码前确认：

- 已阅读本文档、模块数据库设计和对应 OpenAPI。
- 每个响应字段都有明确数据来源（数据库字段或关联查询）。
- 请求 DTO 不包含只读展示字段，响应 DTO 不包含密码等敏感字段。
- 每个查询参数对应一个明确的数据库字段。
- 层级数据返回扁平列表，不返回树结构。
- 模块依赖方向正确，没有循环依赖。

提交代码前确认：

- Entity、DTO、Service、Controller、OpenAPI 和数据库设计保持一致。
- `@Valid` 校验覆盖所有必填和格式要求。
- 业务校验使用 `BizException` + `ErrorCode`，错误码在正确分段内。
- 事务注解正确，跨模块调用在同一事务内。
- Long 类型 ID 在 JSON 中序列化为 String。
- 编译通过，无警告。
