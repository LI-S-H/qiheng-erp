# Git 协作开发流程

本文档用于说明启衡 ERP 项目的 GitHub 协作方式，适合前端、后端、文档一起维护的开发流程。

当前仓库地址：

```text
https://github.com/LI-S-H/qiheng-erp.git
```

## 1. 推荐仓库结构

项目采用一个仓库管理前端、后端和文档，便于接口、库表和页面开发保持一致。

```text
qiheng-erp/
├── docs/          项目文档、库表设计、接口文档
├── erp-web/       前端工程，Vue + Element Plus
├── erp-system/    后端工程，后续脚手架放这里
├── .gitignore
└── README.md
```

这样做的好处：

- 前端、后端和接口文档放在同一个仓库，方便同步审核
- 一个模块的页面、接口、库表可以一起提交，减少信息断层
- 后端两个人协作时可以按模块拆分分支，互不干扰
- 项目负责人可以通过 Pull Request 统一审核代码质量和接口设计

## 2. 分支规则

建议使用三类分支。

```text
main
稳定分支，只放已经审核通过、可以作为阶段成果的代码

dev
开发集成分支，前端和后端日常开发完成后先合并到这里

feature/*
功能分支，每个人开发具体功能时从 dev 拉出
```

推荐分支示例：

```text
feature/backend-foundation
feature/backend-auth
feature/backend-system-permission
feature/backend-product
feature/backend-warehouse
feature/backend-purchase
feature/backend-sales
feature/backend-ai

feature/web-login
feature/web-product
feature/web-warehouse

fix/backend-login-error
fix/web-menu-animation
```

## 3. 当前项目已经完成的 Git 状态

当前项目已经完成以下初始化：

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
```

后续上传代码前，仍然建议先执行：

```powershell
git status
```

确认没有把临时文件、构建产物、依赖目录提交进去。

## 4. 项目负责人第一次创建 dev 分支

如果仓库目前只有 `main`，建议先创建 `dev` 分支作为日常开发集成分支。

```powershell
git checkout main
git pull
git checkout -b dev
git push -u origin dev
```

之后所有功能开发都从 `dev` 分支拉出。

## 5. 新成员第一次拉取项目

后端开发人员第一次参与项目时，执行：

```powershell
git clone https://github.com/LI-S-H/qiheng-erp.git
cd qiheng-erp
git checkout dev
git pull
```

如果本地没有 `dev` 分支，可以执行：

```powershell
git checkout -b dev origin/dev
```

## 6. 后端脚手架初始化上传流程

你准备先搭建后端脚手架时，建议不要直接在 `main` 上提交，而是创建一个后端基础分支。

### 6.1 拉取最新代码

```powershell
git checkout dev
git pull
```

### 6.2 创建后端脚手架分支

```powershell
git checkout -b feature/backend-foundation
```

### 6.3 在根目录创建后端工程

推荐后端工程目录名：

```text
erp-system/
```

搭建完成后的结构示例：

```text
qiheng-erp/
├── docs/
├── erp-web/
├── erp-system/
│   ├── pom.xml
│   ├── src/
│   └── README.md
├── .gitignore
└── README.md
```

### 6.4 检查不要提交无关文件

提交前先执行：

```powershell
git status
```

需要避免提交：

```text
target/
.idea/
.vscode/
*.log
本地数据库文件
本地密钥文件
.env
```

如果后端使用 Maven，通常可以提交：

```text
pom.xml
src/
mvnw
mvnw.cmd
.mvn/wrapper/
```

不要提交：

```text
target/
*.class
本地运行日志
本地配置密钥
```

### 6.5 本地验证

如果是 Maven 项目，提交前建议执行：

```powershell
mvn test
```

或者使用 Maven Wrapper：

```powershell
.\mvnw.cmd test
```

如果暂时没有测试，至少执行启动或编译命令，确认脚手架能跑起来。

### 6.6 提交后端脚手架

```powershell
git add erp-system .gitignore
git commit -m "feat: 初始化后端工程脚手架"
```

### 6.7 推送分支

```powershell
git push -u origin feature/backend-foundation
```

### 6.8 创建 Pull Request

在 GitHub 上创建 PR：

```text
feature/backend-foundation -> dev
```

PR 描述建议写清楚：

```text
本次变更：
- 新增 erp-system 后端工程
- 初始化项目依赖和基础目录
- 添加启动配置
- 添加基础健康检查接口

验证方式：
- 已执行 mvn test
- 已本地启动后端服务
```

审核通过后再合并到 `dev`。

## 7. 两个后端开发人员如何并行开发

假设后端有两个人：

```text
开发 A：系统权限模块
开发 B：产品中心模块
```

开发 A 创建分支：

```powershell
git checkout dev
git pull
git checkout -b feature/backend-system-permission
```

开发 B 创建分支：

```powershell
git checkout dev
git pull
git checkout -b feature/backend-product
```

两个人分别开发自己的模块，完成后分别推送自己的分支并创建 PR。

开发 A：

```powershell
git add erp-system docs
git commit -m "feat: 添加系统权限后端接口"
git push -u origin feature/backend-system-permission
```

开发 B：

```powershell
git add erp-system docs
git commit -m "feat: 添加产品管理后端接口"
git push -u origin feature/backend-product
```

然后分别创建 PR：

```text
feature/backend-system-permission -> dev
feature/backend-product -> dev
```

## 8. 每天开始开发前的固定动作

每个人每天开始写代码前，都先同步 `dev`。

```powershell
git checkout dev
git pull
```

然后回到自己的功能分支：

```powershell
git checkout feature/自己的分支名
git merge dev
```

这样可以提前拿到别人已经合并的代码，减少后面冲突。

## 9. 开发完成后的固定动作

开发完成后不要直接合并到 `main` 或 `dev`，先提交自己的分支。

```powershell
git status
git diff
git add 需要提交的文件
git commit -m "feat: 添加具体功能说明"
git push
```

如果是第一次推送这个分支：

```powershell
git push -u origin feature/自己的分支名
```

然后在 GitHub 创建 PR，等待审核。

## 10. 代码审核和合并规则

每个 PR 至少检查以下内容：

- 是否只包含本次功能相关文件
- 是否误提交 `target`、`dist`、`node_modules`、日志文件
- 接口是否和 `docs/api/erp-openapi.yaml` 保持一致
- 表结构是否和 `docs/database` 保持一致
- 是否能本地启动或通过测试
- 提交信息是否清晰

PR 合并后，开发人员需要同步最新 `dev`：

```powershell
git checkout dev
git pull
```

## 11. 遇到冲突怎么办

如果执行下面命令时出现冲突：

```powershell
git merge dev
```

说明当前分支和 `dev` 修改了同一个文件的同一部分。

处理方式：

1. 打开冲突文件
2. 手动选择要保留的代码
3. 删除 Git 自动生成的冲突标记
4. 重新提交

冲突标记通常长这样：

```text
<<<<<<< HEAD
当前分支的代码
=======
dev 分支的代码
>>>>>>> dev
```

处理完成后：

```powershell
git add 冲突文件
git commit -m "fix: 解决合并冲突"
git push
```

## 12. 阶段发布流程

当 `dev` 上的前后端功能稳定后，再把 `dev` 合并到 `main`。

```powershell
git checkout main
git pull
git merge dev
git push
```

建议只有项目负责人执行这个操作。

如果使用 GitHub PR，也可以创建：

```text
dev -> main
```

审核通过后合并。

## 13. 常用安全命令

这些命令日常可以放心使用：

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
mvn test
mvn package
```

或者：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

## 14. 谨慎使用的命令

下面这些命令可能会删除或回滚代码，不要随便执行：

```powershell
git reset --hard
git clean -fd
Remove-Item -Recurse
```

如果必须使用，需要先确认：

- 当前代码是否已经提交
- 是否有未保存的重要改动
- 删除范围是否明确
- 是否已经备份

## 15. 最简单的协作口诀

```text
先拉 dev
再建分支
自己开发
提交推送
创建 PR
审核合并
同步 dev
继续开发
```

只要坚持这个流程，两个人同时写后端也不会乱，前端、后端和文档也能保持一致。
