# 前端页面设计思路

## 1. 前端架构选择

前端软件名为 **启衡 ERP**。

前端采用 `Vue 3 + TypeScript + Vite + shadcn-vue + Tailwind CSS + Pinia + Vue Router + Axios`。

选择这套技术栈的原因：

- 项目是企业内部 ERP 管理系统，核心页面以表格、筛选、表单、弹窗、状态流转和操作确认为主；shadcn-vue 提供可控的无样式基础组件，便于统一视觉并避免组件库默认样式侵入业务页面
- Tailwind CSS 负责布局和局部样式，颜色、字体、圆角和间距通过全局 token 与共享页面类统一，不在各模块重复维护大段局部 CSS
- Vue 3 + TypeScript 能保证开发效率，同时让接口字段、登录态和业务 DTO 有明确类型约束
- Vite 启动和构建速度快，适合按模块逐步生成、逐步审核的开发节奏
- Pinia 用于保存登录态、当前用户和后续模块级缓存，状态结构清晰
- Axios 统一处理 `satoken` 请求头、响应错误和登录失效跳转，避免每个模块重复处理鉴权逻辑

## 2. 目录组织思路

前端工程放在 `erp-web` 目录下，按“基础能力 + 业务模块”组织：

```text
erp-web
├── src
│   ├── api                 Axios 实例和统一请求函数
│   ├── layouts             后台主布局
│   ├── router              路由和登录守卫
│   ├── shared              通用类型、常量、组件和工具
│   ├── modules             按业务域拆分页面、接口和状态
│   └── styles              全局样式
└── package.json
```

这样设计的好处是每个模块可以独立审核：页面、接口封装、类型定义和局部状态都放在自己的模块目录下；公共能力只沉淀到 `shared` 和 `api`，避免模块之间互相引用过深。

## 3. 整体页面风格

启衡 ERP 面向企业内部采购、销售、仓储、管理人员和系统管理员，页面风格以“稳定、清晰、可长时间使用”为主。

设计原则：

- 后台主布局采用左侧菜单 + 顶部用户区 + 主内容区，符合 ERP 用户的日常操作习惯
- 左侧导航和顶部状态栏使用同一组深色中性 token，内容区使用低对比浅灰背景，避免出现两个互不相关的视觉系统
- 全站使用中文系统字体栈，不依赖远程字体；页面业务正文、输入、下拉和表格主文字复用 shadcn `text-sm` 的 `14px` 基准，按钮文字统一为 `13px` 常规字重，避免操作文字压过数据内容
- 列表页的四项概览合并为单条摘要栏，筛选区和表格各保留一个容器，减少卡片堆叠和页面碎片感
- 页面优先使用列表页、筛选区、表单弹窗、详情抽屉、状态标签和确认弹窗，服务高频业务处理
- 中等宽度下筛选区自动切换为规整两列；筛选下拉使用 shadcn `Popover + Command` 组合并固定从触发框下方等宽展开；输入焦点仅显示柔和模糊辉光
- 用户与角色列表底栏保持 `52px` 轻量尺寸，左侧提供总数和每页条数选择，中间使用 shadcn 原生分页组件，右侧展示总页数
- 页面跳转保留短时加载遮罩与淡入反馈，侧栏子菜单使用基于内容高度的折叠动画，避免固定最大高度造成布局卡顿
- 所有弹窗受视口最大高度约束，长表单和权限列表必须支持纵向滚动，不能截断底部操作区
- 表格工具栏复用 shadcn `Button` 与 `Tooltip`，可操作按钮悬停时加深背景或边框，禁用批量操作提示需要先选择数据
- 所有 shadcn 文字按钮统一使用 `13px` 常规字重，行内操作、工具栏和弹窗按钮仅通过组件变体与尺寸区分操作层级
- 新增和编辑表单提交前统一执行 `trim`、必填、长度、格式与数组非空校验；账号、角色编码等唯一字段冲突回填到对应字段，不只显示全局提示
- 查询操作使用短防抖并忽略过期响应，保存、确认、重置密码等异步动作在处理中禁用触发按钮，避免重复请求
- 视觉上避免营销页式大面积装饰，重点突出信息密度、可读性和操作确定性
- 菜单先固定展示，权限以后端接口校验为准，符合 MVP 阶段“页面入口统一展示、进入页面或查询时校验权限码”的数据库设计
- 前端不展示接口文档入口，接口文档只维护在 `docs/api/erp-openapi.yaml`，用于导入 Apifox

## 4. 权限与登录态设计

系统权限库表设计中，MVP 阶段保留：

```text
sys_dept
sys_user
sys_role
sys_user_role
```

角色权限码直接保存在 `sys_role.permission_codes`，登录后由服务端把用户基础信息、角色编码和权限码放入 Redis session。前端不自行推导权限，只保存服务端返回的当前用户上下文。

请求鉴权采用 Sa-Token 原始 token。前端登录成功后保存 token，并在 Axios 请求拦截器中通过 `satoken` 请求头传给后端。

## 5. 基础框架 + 登录模块

首个基础模块包含：

- 登录页 `/login`
- 后台主布局
- 工作台 `/dashboard`
- 路由守卫
- 当前用户状态
- 退出登录
- 404 页面

登录页默认在开发环境填入 `admin / 123456`，方便前端审核。后端接入后，可通过环境变量关闭 mock 登录并切换到真实接口。

后台主布局采用固定左侧菜单。MVP 阶段不设计 `sys_menu` 和动态路由表，权限由后端接口和页面进入后的权限码校验控制。后续如果拆出菜单权限表，可以把当前静态菜单迁移为后端菜单数据。

## 6. 模块生成规则

每生成一个模块，需要同步更新：

```text
docs/frontend-page-design.md
docs/api/erp-openapi.yaml
```

模块页面生成顺序：

```text
1. 基础框架 + 登录模块
2. 系统权限模块
3. 产品模块
4. 仓库库存模块
5. 采购模块
6. 销售模块
7. AI 模块
```

每个模块完成后暂停，由用户审核页面代码和接口文档，再继续下一个模块。

## 7. 系统权限模块：用户管理

### 7.1 页面范围

本次生成系统权限模块中的第一个页面：`/system/users` 用户管理。

页面包含：

- 用户指标概览
- 账号关键词、部门、角色、状态筛选
- 用户分页表格
- 分页底栏支持选择每页 `10 / 20 / 50` 条，翻页组件相对数据容器居中
- 新增用户
- 编辑用户
- 单独绑定角色
- 批量启用 / 停用账号
- 批量重置密码
- 批量删除用户

对应库表：

```text
sys_user
sys_dept
sys_role
sys_user_role
```

### 7.2 设计考虑

用户管理属于系统基础能力，操作频率不一定最高，但影响权限边界和账号安全，所以页面优先保证清晰、克制、可确认。

页面采用上方指标、筛选区、表格区、弹窗表单的后台标准结构。这样做的好处是：

- 管理员进入页面后能快速知道账号总量、启用数量、超级管理员数量和角色绑定覆盖情况
- 筛选条件和表格保持在同一视线范围内，适合日常查找账号
- 状态筛选值保持接口定义的数字 `0 | 1`，“全部”不发送给接口；部门、角色、状态等多个条件按 AND 组合
- 新增、编辑和角色绑定使用弹窗，避免频繁跳转
- 行内操作只保留“编辑”和“角色绑定”两个高频动作，减少固定操作列拥挤和悬停裁切风险
- 停用、删除、重置密码放在表格工具栏中作为批量操作，必须先勾选账号再执行，降低误操作风险，也便于后端统一实现批量接口
- 表格字段严格贴合库表和关联关系，后端接入时不需要重新调整页面结构

### 7.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 用户ID | `sys_user.id` |
| 登录账号 | `sys_user.username` |
| 用户姓名 | `sys_user.real_name` |
| 所属部门 | `sys_user.dept_id` 关联 `sys_dept.dept_name` |
| 超级管理员 | `sys_user.is_admin` |
| 状态 | `sys_user.status` |
| 最近登录 | `sys_user.last_login_at` |
| 创建时间 | `sys_user.created_at` |
| 更新时间 | `sys_user.updated_at` |
| 绑定角色 | `sys_user_role` 关联 `sys_role` |

### 7.4 接口设计考虑

用户管理接口维护在 `docs/api/erp-openapi.yaml` 中，可导入 Apifox。

本页面使用的接口包括：

```text
GET    /system/users
POST   /system/users
GET    /system/users/{userId}
PUT    /system/users/{userId}
PATCH  /system/users/{userId}/status
PATCH  /system/users/{userId}/password
PUT    /system/users/{userId}/roles
DELETE /system/users/{userId}
PATCH  /system/users/batch/status
PATCH  /system/users/batch/password
POST   /system/users/batch/delete
GET    /system/roles/options
GET    /system/depts/options
```

接口字段采用前端友好的 camelCase，后端落库时映射到 snake_case 字段。`bigint` 主键统一用字符串返回，避免前端数字精度丢失。

### 7.5 后端接入方式

当前页面通过模块 API 层的开发环境 mock 数据展示交互效果，页面组件不直接维护临时数据。

后端接口完成后，只需要关闭 `VITE_USE_MOCK_API`，页面继续调用以下 API 函数：

```text
listSystemUsers
createSystemUser
updateSystemUser
updateSystemUserStatus
resetSystemUserPassword
bindSystemUserRoles
deleteSystemUser
batchUpdateSystemUserStatus
batchResetSystemUserPassword
batchDeleteSystemUsers
listRoleOptions
listDeptOptions
```

这些函数已经在 `erp-web/src/modules/system/users/api.ts` 中预留，接口路径与 OpenAPI 文档保持一致。

## 8. 系统权限模块：角色管理

### 8.1 页面范围

本次继续生成系统权限模块中的第二个页面：`/system/roles` 角色管理。

页面包含：

- 角色指标概览
- 角色编码、角色名称、状态筛选
- 角色状态筛选
- 角色分页表格
- 分页底栏支持选择每页 `10 / 20 / 50` 条，翻页组件相对数据容器居中
- 新增角色
- 编辑角色
- 单独配置权限码
- 批量启用 / 停用角色
- 批量删除角色

对应库表：

```text
sys_role
sys_user_role
```

### 8.2 设计考虑

角色管理直接决定用户能访问哪些业务能力，因此页面设计重点是让“角色本身”和“权限码集合”都能被快速看清。

页面沿用用户管理页确认过的后台列表结构：上方指标、筛选区、表格工具栏、数据表格和弹窗表单。这样做的好处是：

- 系统权限模块内的页面交互保持一致，管理员切换用户管理和角色管理时不需要重新学习
- 行内只保留“编辑”和“权限配置”，避免操作列拥挤，也避免悬停浮层被固定列裁切
- 权限码配置单独弹窗处理，符合角色维护的高风险特征，后续接后端时也能独立调用权限更新接口
- 批量启用、批量停用、删除放到表格工具栏，和用户管理页保持一致，便于后端统一实现批量状态和批量删除接口
- 表格中的权限码改为摘要展示，点击“查看明细”打开只读弹窗按权限组展示完整权限码，避免表格被权限码标签撑高

### 8.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 角色ID | `sys_role.id` |
| 角色编码 | `sys_role.role_code` |
| 角色名称 | `sys_role.role_name` |
| 权限码 | `sys_role.permission_codes` |
| 状态 | `sys_role.status` |
| 备注 | `sys_role.remark` |
| 创建时间 | `sys_role.created_at` |
| 更新时间 | `sys_role.updated_at` |
| 绑定用户数 | `sys_user_role` 按 `role_id` 聚合 |

### 8.4 接口设计考虑

角色管理接口维护在 `docs/api/erp-openapi.yaml` 中，可导入 Apifox。

本页面使用的接口包括：

```text
GET    /system/roles
POST   /system/roles
GET    /system/roles/{roleId}
PUT    /system/roles/{roleId}
PATCH  /system/roles/{roleId}/status
PATCH  /system/roles/{roleId}/permissions
DELETE /system/roles/{roleId}
PATCH  /system/roles/batch/status
POST   /system/roles/batch/delete
```

接口字段继续采用 camelCase，后端落库时映射到 snake_case 字段。角色查询条件按库表字段拆为 `roleCode`、`roleName`、`status`，不使用跨字段关键词混搜，避免后端在 `role_code`、`role_name`、`remark` 上写过宽的查询逻辑。`permissionCodes` 对应数据库 JSON 字段，前端以字符串数组维护；`userCount` 不落库，由后端根据 `sys_user_role` 聚合返回。

### 8.5 后端接入方式

当前页面通过模块 API 层的开发环境 mock 数据展示交互效果，页面组件不直接维护临时数据。

后端接口完成后，只需要关闭 `VITE_USE_MOCK_API`，页面继续调用以下 API 函数：

```text
listSystemRoles
getSystemRole
createSystemRole
updateSystemRole
updateSystemRoleStatus
updateSystemRolePermissions
deleteSystemRole
batchUpdateSystemRoleStatus
batchDeleteSystemRoles
```

这些函数已经在 `erp-web/src/modules/system/roles/api.ts` 中预留，接口路径与 OpenAPI 文档保持一致。

## 9. 系统权限模块：部门管理

### 9.1 页面范围

本次继续生成系统权限模块中的第三个页面：`/system/depts` 部门管理。

页面包含：

- 部门指标概览
- 部门名称、状态筛选
- 部门树形表格
- 新增部门
- 新增下级部门
- 编辑部门
- 批量启用 / 停用部门
- 删除部门

对应库表：

```text
sys_dept
sys_user
```

### 9.2 设计考虑

部门管理的核心不是分页列表，而是维护组织层级。后端只需要返回 `sys_dept` 扁平列表，前端根据 `parent_id` 构建树形结构，并在页面侧计算层级路径。

这样设计的好处是：

- 管理员能直接看到部门和小组之间的父子关系，不需要在分页列表里反复搜索上级部门
- 新增下级部门放在行内，是部门管理的高频动作；编辑和删除也保持在行内，操作路径短
- 删除前检查是否存在下级部门和员工归属，符合后端实际约束，避免误删组织节点
- 顶部指标展示部门总数、启用部门、下级部门和员工数量，让管理员快速判断组织数据是否完整
- 筛选条件按 `sys_dept` 字段拆为 `deptName` 和 `status`，避免后端写过宽的关键词查询
- 表格部门列只展示部门名称和层级展开控件，避免把后端长 ID 堆在名称下面
- 现有 `sys_dept` 表没有部门编码字段，所以本阶段不展示、不编辑、不查询部门编码；后续如果库表新增 `dept_code`，再把它补入筛选区和编辑弹窗
- 树形层级由前端固定展开按钮和占位控制，确保同一级部门在收起、展开后左右间隔一致
- 勾选父级部门时自动勾选其下级部门，避免批量启停时漏掉组织分支内的子节点
- 停用父级部门时必须级联停用全部下级；上级仍停用时，不允许单独启用下级。前端负责交互预校验，后端接口负责最终约束
- 无父节点统一显示为“无上级部门”，不使用“顶级部门”等口语化命名

### 9.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 部门ID | `sys_dept.id`，仅用于接口主键和前端行标识，不直接展示为业务编码 |
| 上级部门 | `sys_dept.parent_id` 关联 `sys_dept.id` |
| 祖级路径 | `sys_dept.ancestors`，接口可原样返回；页面展示路径由前端按 `parent_id` 递归计算 |
| 部门名称 | `sys_dept.dept_name` |
| 状态 | `sys_dept.status` |
| 创建时间 | `sys_dept.created_at` |
| 更新时间 | `sys_dept.updated_at` |
| 员工数量 | `sys_user` 按 `dept_id` 聚合，接口字段暂沿用 `userCount` |

### 9.4 接口设计考虑

部门管理接口维护在 `docs/api/erp-openapi.yaml` 中，可导入 Apifox。

本页面使用的接口包括：

```text
GET    /system/depts
POST   /system/depts
GET    /system/depts/{deptId}
PUT    /system/depts/{deptId}
PATCH  /system/depts/{deptId}/status
DELETE /system/depts/{deptId}
PATCH  /system/depts/batch/status
POST   /system/depts/batch/delete
GET    /system/depts/options
```

接口字段继续采用 camelCase，后端落库时映射到 snake_case 字段。`GET /system/depts` 返回扁平数组，不能返回预组装的 `children`；前端按 `parentId` 构建树表和层级路径。`userCount` 不落库，由后端根据 `sys_user.dept_id` 聚合返回。部门状态接口必须执行父子级联约束。

### 9.5 后端接入方式

当前页面通过模块 API 层的开发环境 mock 数据展示交互效果。mock 接口同样返回扁平部门列表，用于验证真实后端接入边界。

后端接口完成后，只需要关闭 `VITE_USE_MOCK_API`，页面继续调用以下 API 函数：

```text
listSystemDepts
getSystemDept
createSystemDept
updateSystemDept
updateSystemDeptStatus
deleteSystemDept
batchUpdateSystemDeptStatus
batchDeleteSystemDepts
```

这些函数已经在 `erp-web/src/modules/system/depts/api.ts` 中预留，接口路径与 OpenAPI 文档保持一致。

## 10. 系统权限模块：权限码配置

### 10.1 页面范围

本次生成系统权限模块第四个页面：`/system/permissions` 权限码配置。

页面包含权限指标概览、关键词/模块/操作类型/状态筛选、分页列表、新增与编辑、单条启停、批量启停、批量删除和角色引用保护。筛选查询使用 250ms 防抖，所有写操作均设置提交锁，防止重复请求。

### 10.2 字段与校验

| 页面字段 | 后端来源 | 校验规则 |
|---|---|---|
| 权限码 | `sys_permission.permission_code` | 必填、唯一、最长 100；小写字母或数字按冒号分段；创建后不可修改 |
| 权限名称 | `sys_permission.permission_name` | 必填、最长 100 |
| 所属模块 | `sys_permission.module_code` | 必填、最长 64 |
| 操作类型 | `sys_permission.action_type` | 必填，限定 query/create/update/delete/manage/execute |
| 状态 | `sys_permission.status` | 必填，1 启用、0 停用 |
| 排序值 | `sys_permission.sort_order` | 0-9999 整数 |
| 权限说明 | `sys_permission.description` | 选填、最长 500 |
| 模块名称 | 前端根据 `sys_permission.module_code` 和固定模块字典映射 | 不要求后端返回 `moduleName` |
| 角色引用数 | 后端根据 `sys_role.permission_codes` JSON 聚合 | 只读，不落库；通配符 `*` 视为引用全部权限码 |

权限码由后端鉴权逻辑和角色授权共同引用，创建后不可修改，更新接口也不接收 `permissionCode`；删除前必须确认没有角色引用。保留权限 `*` 仅供超级管理员使用，不在普通权限目录中维护。

### 10.3 接口

```text
GET    /system/permissions
POST   /system/permissions
GET    /system/permissions/{permissionId}
PUT    /system/permissions/{permissionId}
PATCH  /system/permissions/{permissionId}/status
DELETE /system/permissions/{permissionId}
PATCH  /system/permissions/batch/status
POST   /system/permissions/batch/delete
GET    /system/permissions/options
```

`GET /system/permissions/options` 只返回启用权限，并按模块分组供角色授权弹窗使用。权限码唯一性冲突返回 `409 Conflict`；删除仍被角色引用的权限码也返回 `409 Conflict`。

## 11. 前端风格规范

后续页面的视觉和交互细节统一参考 `docs/frontend-style-guide.md`。

当前确认的风格方向是：深色应用壳与浅色内容区形成稳定层级；页面使用单条摘要栏、紧凑筛选区和一个数据容器；主操作使用品牌蓝，普通行内操作使用 `ghost`，危险操作仅保留红色文字；状态标签使用低饱和浅色语义；表格行内只保留少量高频操作；批量操作放在表格工具栏；树形数据优先使用树表，不强行分页。
