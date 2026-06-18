# MVP 系统权限库表设计：极简版

## 设计目标

本设计用于第一版 ERP MVP，优先保证系统能尽快开发出来。权限目录独立维护，页面入口统一展示；用户进入页面、调用接口或执行核心写操作时，根据当前用户角色里的有效权限码判断是否允许访问。

## 简化原则

- 暂不设计菜单权限表，不维护前端路由、组件路径、图标、显示隐藏等字段。
- 暂不设计动态按钮资源表；核心查询和写操作可以直接使用接口权限码校验。
- 权限目录独立维护在 `sys_permission`，角色授权结果继续保存在 `sys_role.permission_codes`，避免增加高频关联表查询。
- 暂不设计部门级、仓库级、自定义数据范围、字段级权限，后续功能稳定后再补。
- MVP 系统权限模块保留 5 张表：`sys_dept`、`sys_user`、`sys_role`、`sys_permission`、`sys_user_role`。

## 全局约定

- 数据库：MySQL 8。
- 主键：统一使用 `bigint`，由 MyBatis-Plus `ASSIGN_ID` 生成。
- 删除策略：使用 `deleted tinyint` 逻辑删除，`0` 正常，`1` 删除。
- 状态字段：`status tinyint`，`1` 启用，`0` 禁用。
- 外键策略：MVP 不创建物理外键，关系由业务层和索引保证。
- 评分和百分率字段如后续加入，统一遵守 `database-design-conventions.md`：用 `int` 存放大 100 倍后的整数。
- `sys_user_role` 是关系表，不使用 `deleted`，删除关系时直接物理删除关系记录。

## 表：sys_dept（部门表）

| 字段         | 类型           | 说明                 |
| ---------- | ------------ | ------------------ |
| id         | bigint PK    | 部门ID               |
| parent_id  | bigint       | 上级部门ID，顶级为 0       |
| ancestors  | varchar(500) | 祖级路径：当前部门所有祖先部门 ID 用英文半角逗号串联、以虚拟根 `0` 开头、不含自身；MVP 阶段不参与查询，仅在后续实现部门数据范围权限时作为 `LIKE '%,?,%'` 命中字段使用（详见下方"ancestors 字段详细说明"） |
| dept_name  | varchar(100) | 部门名称               |
| status     | tinyint      | 状态                 |
| create_time | datetime     | 创建时间               |
| update_time | datetime     | 更新时间               |
| deleted    | tinyint      | 逻辑删除               |

关系说明：`sys_user.dept_id` 关联本表。MVP 阶段部门只作为用户归属信息，不参与权限过滤。

部门业务约束：

- 上级部门不能设置为当前部门或当前部门的任意下级，避免形成循环层级。
- 停用父级部门时必须在同一事务内级联停用全部下级；启用下级前，全部上级必须已启用或包含在同一次批量启用请求中。
- 停用部门不会自动停用员工账号，但停用部门不能继续作为新增或调整员工归属。
- 存在下级部门或仍有未删除用户通过 `sys_user.dept_id` 归属时禁止删除，接口返回 `409 Conflict`。
- 编辑接口修改部门状态时必须复用状态接口的级联规则，不能成为绕过入口。

### ancestors 字段详细说明

> 本节为 `ancestors` 字段的扩展说明，**不改变 MVP 阶段"部门不参与权限过滤"的设计前提**，仅解释该字段在后续部门数据范围权限功能上线时如何被消费。MVP 阶段新增/编辑部门时由后端按 `parent_id` 自动维护，前端不消费。

#### 字段定位

`ancestors` 是 `sys_dept` 表的**冗余存储字段**（物化路径模式），用于记录"当前部门到根节点之间的所有祖先部门 ID 路径"。它**不是展示路径**（如 "采购部 / 采购跟单组"），而是**严格用部门 ID 串成的英文半角逗号串**，便于后续用一条 SQL 一次性查出"我 + 我所有下级"或"我 + 我所有上级"的部门 ID 集合。

| 关键属性 | 取值 |
| --- | --- |
| 格式 | 英文半角逗号 `,` 分隔的部门 ID 列表 |
| 起点 | 虚拟根 `0`（**固定**） |
| 终点 | 当前部门的**直接父级** |
| 是否包含自身 | **不包含** |
| 路径连续性 | 必须连续，不能跳级 |
| 分隔符 | **英文半角逗号**，**不带空格** |
| 最大长度 | 500 字符（`VARCHAR(500)` 硬限制） |

#### 取值示例

| 当前部门 | `parent_id` | `ancestors` |
| --- | --- | --- |
| 顶级（无上级） | `0` | `0` |
| 二级，父是顶级 | `1900000000000000102` | `0,1900000000000000102` |
| 三级，父是 `...102` | `1900000000000000107` | `0,1900000000000000102,1900000000000000107` |
| N 级，路径连续 | `...` | `0,id1,id2,...,parentId` |

#### 错误示例（禁止）

| 错误写法 | 问题 |
| --- | --- |
| `""`（空串） | SQL 默认值虽允许，但血缘链断裂，后续 `LIKE` 匹配不到自身之外的下级 |
| `"采购部 / 采购跟单组"` | 展示路径，不是 ID 路径，SQL 不可用 |
| `"0,1900000000000000107"`（跳过父） | 路径必须连续，禁止跳级 |
| `"0, 1900000000000000107"`（带空格） | 分隔符严禁带空格，会让 `LIKE '%,X,%'` 漏匹配 |
| `"1900000000000000107"`（不以 `0` 开头） | 必须以虚拟根 `0` 起头，否则无法判断"是否顶级" |
| `"0,1900000000000000102,1900000000000000107,"`（末尾逗号） | 末尾逗号会让 `FIND_IN_SET` 多一个空值 |

#### 生成规则（后端责任）

后端在 `POST /system/depts` 与 `PATCH /system/depts/{id}` 时，**不能**让前端传入 `ancestors`，必须由后端在事务内基于 `parent_id` 派生：

```text
function buildAncestors(parentId):
    if parentId == "0":
        return "0"
    parent = SELECT ancestors FROM sys_dept WHERE id = parentId AND deleted = 0
    if parent is null:
        throw 400: 上级部门不存在或已删除
    return parent.ancestors + "," + parentId
```

**为什么不让前端拼？** 前端变更父级时需要重算整条路径，任何漏算都会让数据范围权限 SQL 全部失效。后端基于**已落库的父级 `ancestors`** 派生是 O(1) 一次查询，可靠性最高。

**编辑时变更父级**：除写入新部门的 `ancestors` 外，必须在**同一事务**内同步更新所有子孙部门的 `ancestors` 前缀（例如把 `0,A,B` 改成 `0,X,B`），否则子孙会指向错误的祖先链。建议 SQL 形式：

```sql
UPDATE sys_dept
SET ancestors = REPLACE(ancestors, '0,OLD_PARENT_ID,', '0,NEW_PARENT_ID,')
WHERE ancestors LIKE CONCAT('0,', 'OLD_PARENT_ID', '%')
  AND deleted = 0;
```

#### 消费时机（后续部门数据范围权限功能）

`ancestors` 在 MVP 阶段**不参与任何查询**。它的真正消费场景是后续"部门数据范围权限"功能上线后。常见用法是配合业务表的 `dept_id` 做"本部门及下级/上级"的可见性过滤：

```sql
-- 场景：用户 A 的 dept_id = '1900000000000000108'，数据范围 = 本部门及下级
-- 步骤 1：算出"自己 + 所有下级部门"的 ID 集合
SELECT id FROM sys_dept
WHERE id = '1900000000000000108'                                                  -- ① 自己
   OR CONCAT(',', ancestors, ',') LIKE CONCAT('%,', '1900000000000000108', ',%');  -- ② 祖先链含本部门 ID = 下级

-- 步骤 2：把可见部门 ID 集合代入业务 SQL（以 sales_order 为例，假设表已加 dept_id）
SELECT o.*
FROM sales_order o
WHERE o.dept_id IN ( <步骤 1 的结果集> )
  AND o.deleted = 0;
```

**反向用法**（"本部门及以上"）则反过来查——拿自己 ancestors 链上的所有部门：

```sql
-- 已知 ancestors = "0,1900000000000000103,1900000000000000108"
-- 拆出 0、1900000000000000103、1900000000000000108 三个部门
SELECT id FROM sys_dept
WHERE FIND_IN_SET(id, REPLACE('0,1900000000000000103,1900000000000000108', ',', ','));  -- 拆 ancestors
```

#### 与 RBAC 的关系

`ancestors` **不替代** RBAC，二者是**两个独立维度**的权限控制：

| 维度 | 控制内容 | 数据来源 | `ancestors` 是否参与 |
| --- | --- | --- | --- |
| RBAC（功能级） | 能否进入页面、调用接口 | `sys_role.permission_codes` | **不参与** |
| 数据范围（数据级） | 进入页面后能看到哪些行 | `sys_user.dept_id` + 角色 `data_scope` + `sys_dept.ancestors` | **参与**（仅在数据范围为"本部门及下级/上级"时） |

判定流程必须**串行通过两道关卡**——RBAC 校验通过后，再按数据范围过滤业务数据，**两者都通过**才能返回给客户端。

#### 性能与替代方案对比

| 方案 | 查询次数 | 兼容性 | 推荐度 |
| --- | --- | --- | --- |
| 应用层递归 `parent_id` | 1（部门列表）+ N×层级 | 全版本 MySQL | ❌ N+1 |
| MySQL 8 递归 CTE（`WITH RECURSIVE`） | 1 条 SQL | 仅 MySQL 8.0+ | ⚠️ 兼容差、慢 |
| **物化路径（`ancestors`）LIKE 查询** | 1 条 SQL | 全版本 MySQL | ✅ **推荐** |

`ancestors` 是**用空间换时间**——单部门字段最长 500 字符，整张 `sys_dept` 表的 `ancestors` 总占用通常 < 100KB，几乎零成本；换来的"任意数据范围查询都是单条 SQL"在大数据量场景下价值显著。

## 表：sys_user（用户表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 用户ID |
| username | varchar(64) | 登录账号，唯一 |
| password_hash | varchar(255) | 密码哈希 |
| real_name | varchar(100) | 用户姓名 |
| dept_id | bigint NOT NULL | 所属部门ID，用户必须归属一个部门 |
| is_admin | tinyint | 是否超级管理员 |
| status | tinyint | 状态 |
| last_login_at | datetime | 最近登录时间 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |

关系说明：用户通过 `sys_user_role` 绑定角色。新增或编辑用户时至少绑定一个角色；超级管理员 `is_admin = 1` 默认拥有全部权限。

用户列表查询约束：

- 登录账号使用 `username` 参数，对应 `sys_user.username` 包含匹配。
- 用户姓名使用 `realName` 参数，对应 `sys_user.real_name` 包含匹配。
- 部门、角色和状态分别使用 `deptId`、`roleId`、`status` 精确筛选；多个条件按 AND 组合。
- 不使用跨账号、姓名和部门名称的通用 `keyword` 参数。

## 表：sys_role（角色表）

| 字段               | 类型           | 说明         |
| ---------------- | ------------ | ---------- |
| id               | bigint PK    | 角色ID       |
| role_code        | varchar(64)  | 角色编码，唯一    |
| role_name        | varchar(100) | 角色名称       |
| permission_codes | json         | 角色已授权权限码列表 |
| status           | tinyint      | 状态         |
| create_time      | datetime     | 创建时间       |
| update_time      | datetime     | 更新时间       |
| deleted          | tinyint      | 逻辑删除       |
| remark           | varchar(500) | 备注         |

权限码示例：

```json
[
  "product:query",
  "warehouse:query",
  "supplier:query",
  "customer:query",
  "purchase:query",
  "purchase:create",
  "sales:query",
  "sales:create",
  "ai:query:stock",
  "ai:query:sales",
  "ai:query:purchase"
]
```

关系说明：普通用户的候选权限来自所有启用角色 `permission_codes` 的并集，再与 `sys_permission` 中启用且未删除的权限码取交集。MVP 不设计动态按钮资源和字段级权限。

## 表：sys_permission（权限码目录表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 权限ID |
| permission_code | varchar(100) | 权限码，唯一，使用小写字母、数字和冒号分段 |
| permission_name | varchar(100) | 权限名称 |
| module_code | varchar(64) | 所属模块编码 |
| action_type | varchar(32) | 操作类型：query/create/update/delete/manage/execute |
| status | tinyint | 状态 |
| sort_order | int | 排序值，0-9999 |
| description | varchar(500) | 权限说明 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |

关系说明：`sys_permission.permission_code` 是可授权权限码的目录，`sys_role.permission_codes` 保存角色实际获得的权限码数组。由于角色字段为 JSON，MVP 不建立物理外键；新增或修改角色时必须校验权限码存在且已启用，删除权限码前必须检查是否仍被任一未删除角色引用。超级管理员使用保留值 `*`，不作为普通目录数据新增或编辑。

约束与查询说明：

- `permission_code` 创建后不可修改，并使用全局唯一索引；逻辑删除后也不允许复用旧权限码，避免历史审计和旧 Session 产生歧义。
- 常规列表查询使用 `(deleted, status, sort_order)` 和 `(module_code, action_type)` 索引。
- `roleCount` 通过 `JSON_CONTAINS(sys_role.permission_codes, JSON_QUOTE(permission_code))` 聚合；MVP 角色数量较少，不额外维护角色权限关系表。角色规模明显增长后再迁移到 `sys_role_permission`。
- `module_code`、`action_type` 和状态枚举由应用层与 OpenAPI 共同校验；数据库保留 `varchar` 以支持后续模块扩展。
- 权限列表使用 `permissionCode` 和 `permissionName` 分别查询 `permission_code`、`permission_name`，不使用跨字段 `keyword`；多个条件按 AND 组合。

## 表：sys_user_role（用户角色关系表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 关系ID |
| user_id | bigint | 用户ID |
| role_id | bigint | 角色ID |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |

关系说明：用户和角色多对多。建议唯一索引 `(user_id, role_id)`。

## 表间关系

- `sys_user.dept_id` -> `sys_dept.id`
- `sys_user_role.user_id` -> `sys_user.id`
- `sys_user_role.role_id` -> `sys_role.id`
- `sys_role.permission_codes` 中的普通权限码 -> `sys_permission.permission_code`（业务层校验，无物理外键）

## MVP 权限规则

- 页面菜单先统一展示，不从数据库动态生成。
- 用户进入页面或调用接口时校验权限码。
- 角色授权选项只读取 `sys_permission.status = 1 AND deleted = 0` 的权限码。
- 权限码停用后不再允许新授权，并从普通用户有效权限集合中排除；状态变化后必须清理受影响用户 session 或重新加载权限上下文。
- 大表查询优先校验粗粒度查询权限，例如：
  - 产品：`product:query`
  - 库存：`warehouse:query`
  - 供应商：`supplier:query`
  - 客户：`customer:query`
  - 采购：`purchase:query`
  - 销售：`sales:query`
- AI Tool 也先复用粗粒度权限码，例如 `ai:query:stock`、`ai:query:sales`、`ai:query:purchase`。
- 部门级、仓库级、本人数据范围这些细粒度数据权限暂不实现，后续业务稳定后再补。

## 登录态与 Session 规则

MVP 阶段使用 Sa-Token 原始 UUID token，不引入 JWT 插件。登录成功后，token 只作为客户端访问凭证，服务端登录态统一存入 Redis session。

Redis session 建议保存：

- `user_id`
- `username`
- `real_name`
- `dept_id`
- `is_admin`
- `role_codes`
- `permission_codes`
- 后续扩展的数据权限上下文，例如部门范围、仓库范围、字段权限标记

接口或 AI Tool 鉴权时，先由 Sa-Token 根据 token 找到 Redis session，再读取当前用户和权限上下文。`sys_permission` 定义有效权限目录，`sys_role.permission_codes` 保存角色授权结果；Redis session 只是登录态和权限上下文缓存。

用户禁用、重置密码、角色变更或权限码变更后，需要主动清理对应用户 session，或让权限上下文重新加载，避免旧权限继续生效。这样比 JWT 更适合 ERP 场景，因为服务端可以立即踢人下线、撤销权限和控制敏感 AI Tool 调用。

## 测试场景

- 用户可以登录并获取角色、权限码、部门信息。
- 无权限用户可以看到页面入口，但进入页面或查询接口会被拒绝。
- 有 `purchase:query` 的用户可以查询采购大表，没有该权限则不能查询。
- AI 查询库存时必须具备 `ai:query:stock`。
- 粗粒度权限通过后，接口可以查询对应模块数据。
