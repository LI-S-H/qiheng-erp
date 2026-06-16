# Git 协作开发流程

本文档用于说明启衡 ERP 项目的 GitHub 协作方式，适合前端、后端、文档一起维护的开发流程。

当前仓库地址：

```text
https://github.com/LI-S-H/qiheng-erp.git
```

## 1. 仓库结构

项目采用一个仓库管理前端、后端和文档，便于接口、库表和页面开发保持一致。

```text
qiheng-erp/
├── docs/          项目文档、库表设计、接口文档
├── erp-web/       前端工程，Vue 3 + TypeScript
├── erp-server/    后端工程，Spring Boot 3 + Maven 多模块
├── .gitignore
└── AGENTS.md
```

后端工程内部结构：

```text
erp-server/
├── pom.xml              父 POM（依赖版本管理）
├── erp-common/          通用基础（Result、异常、枚举、工具）
├── erp-security/        登录鉴权（Sa-Token + Redis Session）
├── erp-system/          用户、角色、部门、权限码
├── erp-product/         产品、分类
├── erp-warehouse/       仓库、库存、出入库
├── erp-purchase/        供应商、采购订单
├── erp-sales/           客户、销售订单
├── erp-ai/              AI 智能体（后续）
├── erp-job/             定时任务（后续）
└── erp-admin/           启动模块（配置文件 + 启动类）
```

## 2. 分支规则

项目采用 **main + feature** 两层分支模型，不设 dev 分支。开发人员不多时，功能分支直接合并到 main，流程简洁。

```text
main
稳定分支，只放已经审核通过的代码

feature/*
功能分支，从 main 拉出，完成后通过 PR 合并回 main
```

分支命名规范：

```text
feature/backend-foundation          后端脚手架
feature/backend-auth                登录鉴权
feature/backend-system              用户/角色/部门/权限
feature/backend-product             产品/分类
feature/backend-warehouse           仓库/库存/出入库
feature/backend-purchase            供应商/采购
feature/backend-sales               客户/销售
feature/backend-ai                  AI 模块

feature/web-login                   前端登录
feature/web-product                 前端产品
feature/web-warehouse               前端仓库

fix/backend-login-error             修复
fix/web-menu-animation              修复
```

## 3. 当前项目 Git 状态

```text
已初始化 Git 仓库
已创建 main 分支
已添加 .gitignore
已提交前端基础骨架和项目文档
已推送到 GitHub 仓库
```

当前 `.gitignore` 已排除：

```text
node_modules/
dist/
build/
target/
日志文件
浏览器截图
临时目录
本地 Agent 配置
.idea/
*.iml
```

## 4. 新成员第一次拉取项目

```powershell
git clone https://github.com/LI-S-H/qiheng-erp.git
cd qiheng-erp
git checkout main
git pull
```

## 5. 功能开发流程

### 5.1 从 main 拉出功能分支

```powershell
git checkout main
git pull
git checkout -b feature/backend-foundation
```

### 5.2 开发并提交

```powershell
# 开发过程中随时提交
git add 需要提交的文件
git commit -m "feat: 初始化后端工程脚手架"
```

### 5.3 推送分支

```powershell
git push -u origin feature/backend-foundation
```

### 5.4 创建 Pull Request

在 GitHub 上创建 PR：

```text
feature/backend-foundation -> main
```

PR 描述建议写清楚：

```text
本次变更：
- 新增 erp-server 后端工程（Maven 多模块）
- 初始化 10 个子模块
- 配置 Spring Boot 3 + MyBatis-Plus + Sa-Token + RocketMQ + Knife4j

验证方式：
- mvn clean compile 通过
- 本地启动成功，Knife4j 文档页面可访问
```

### 5.5 审核通过后合并

PR 审核通过后合并到 main，然后删除远程功能分支。

### 5.6 同步本地 main

```powershell
git checkout main
git pull
git branch -d feature/backend-foundation   # 删除本地分支
```

## 6. 并行开发

假设两个人同时开发不同模块：

开发 A（系统权限）：

```powershell
git checkout main && git pull
git checkout -b feature/backend-system
# 开发...
git push -u origin feature/backend-system
# 创建 PR -> main
```

开发 B（产品模块）：

```powershell
git checkout main && git pull
git checkout -b feature/backend-product
# 开发...
git push -u origin feature/backend-product
# 创建 PR -> main
```

两人互不干扰，各自 PR 合并后对方再 pull main 即可。

## 7. 每天开始开发前

```powershell
git checkout main
git pull
git checkout feature/自己的分支名
git merge main
```

把 main 上别人已合并的代码同步过来，减少后续冲突。

## 8. 遇到冲突怎么办

合并 main 时如果出现冲突：

1. 打开冲突文件
2. 手动选择要保留的代码
3. 删除 Git 冲突标记（`<<<<<<<`、`=======`、`>>>>>>>`）
4. 重新提交

```powershell
git add 冲突文件
git commit -m "fix: 解决合并冲突"
git push
```

## 9. 代码审核规则

每个 PR 至少检查：

- 是否只包含本次功能相关文件
- 是否误提交 `target`、`dist`、`node_modules`、日志文件
- 接口是否和 `docs/api/erp-openapi.yaml` 保持一致
- 表结构是否和 `docs/database` 保持一致
- 是否能本地启动或通过测试
- 提交信息是否清晰

## 10. 提交信息规范

```text
feat: 新增功能
fix: 修复问题
docs: 文档变更
refactor: 重构（不新增功能、不修复问题）
style: 格式调整（不影响逻辑）
test: 测试相关
chore: 构建/配置/工具变更
```

示例：

```text
feat: 初始化后端工程脚手架
feat: 添加用户管理接口
fix: 修复登录 token 未存入 Redis 的问题
docs: 消息队列从 RabbitMQ 改为 RocketMQ
```

## 11. 常用安全命令

```powershell
git status
git diff
git branch
git checkout 分支名
git checkout -b 新分支名
git pull
git add 文件名
git commit -m "提交说明"
git push
```

前端常用：

```powershell
cd erp-web
npm.cmd install
npm.cmd run dev
npm.cmd run build
```

后端常用：

```powershell
cd erp-server
mvn clean compile
mvn test
mvn spring-boot:run -pl erp-admin
```

## 12. 谨慎使用的命令

```powershell
git reset --hard
git clean -fd
git push --force
Remove-Item -Recurse
```

使用前必须确认：

- 当前代码是否已经提交
- 是否有未保存的重要改动
- 删除范围是否明确

## 13. 协作口诀

```text
先拉 main
再建分支
自己开发
提交推送
创建 PR
审核合并
同步 main
继续开发
```
