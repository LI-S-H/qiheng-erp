# 实验室内网 Docker 自动部署

适用于 Ubuntu 22.04、Docker Compose、宝塔面板。只通过向日葵管理服务器；服务器主动读取 GitCode 私有仓库，无需公网 IP、WebHook 或 GitHub Runner。

本地测试通过后推送 GitCode `main`，宝塔每五分钟调用一次部署脚本。通过 HTTPS 和专用只读令牌获取分支完整提交 SHA，再归档该 SHA 的前后端源码，发现新提交才打包和更新。不再依赖 GitHub API 或 codeload，不使用代理或第三方源码镜像。服务器不执行单元测试、浏览器测试或交付测试。前端仍需要执行生产构建及其类型检查，后端通过 `-Dmaven.test.skip=true` 跳过测试编译和执行。

## 已部署服务器切换到 GitCode

先暂停宝塔自动部署任务，保持现有容器运行。确认 GitCode 仓库设为私有，并已推送准备部署的 `main`。GitHub 可以作为另一个私有远程保留，但不再是服务器部署来源。两个仓库都不能提交实际密码、令牌和数据库导出。

为服务器创建专用只读 PAT，将令牌本身保存到 `/opt/qiheng-erp/config/gitcode.token`，root 所有，权限 `600`，不加引号、不放进 Git URL 或 `.env`。令牌到期后单独更换，不要复用开发电脑的写入令牌。若平台无法限定到单仓库，应由维护者确认授权范围，必要时使用仅有该仓库读取权限的专用账号。

替换 `/opt/qiheng-erp/bin/deploy.sh` 后，仅修改服务器 `.env` 中以下字段，保留其他原有配置：

```dotenv
DEPLOY_REPO=https://gitcode.com/LI_124301012/qiheng-erp.git
DEPLOY_BRANCH=main
GITCODE_USERNAME=LI_124301012
```

然后执行：

```bash
sudo bash /opt/qiheng-erp/bin/deploy.sh check-source
sudo bash /opt/qiheng-erp/bin/deploy.sh deploy
```

`check-source` 只查询指定分支并校验完整 SHA，不下载完整代码、不更改应用。成功后再执行 `deploy`，首次拉取最多等待十分钟，显示 Git 进度；同一 SHA 已部署时不会重新构建。确认成功后恢复宝塔每五分钟任务。迁移不需要重新运行 `init`、`infra-up` 或 `import-db`。

令牌由每次 Git 调用专用的临时 credential helper 读取，仅响应 HTTPS、`gitcode.com` 和精确仓库路径。禁用其他凭据 helper、交互、跟踪日志、代理及 HTTP 重定向；令牌不进入 URL、命令参数或镜像。若认证、重定向或权限检查失败，先人工排查，不关闭 TLS 校验或放宽凭据目标范围。

## 组件与数据边界

| 项目 | 内容 | 更新方式 |
|---|---|---|
| `qiheng-erp-infra` | MySQL 8、普通 Redis、RocketMQ NameServer/Broker | 首次部署一次，之后单独维护 |
| `qiheng-erp-app` | Java 21 后端、Nginx 前端 | 随 `main` 提交构建并更新 |
| `qiheng-erp-net` | 应用与基础服务共享的 Docker 网络 | 固定名称 |

MySQL、Redis、MQ 消息和日志使用固定 project 下的命名卷，不随应用发布删除。基础服务不映射主机端口。ERP 默认只在 Ubuntu 本机的 `http://127.0.0.1:8088` 提供访问，通过向日葵里的浏览器使用；更改网络访问范围需另行配置。

当前代码无需 MinIO；普通 Redis 满足会话、锁、缓存和 AI 检查点，规划中的 RedisStack 向量检索不属于这次部署。AI 默认关闭，之后在服务器配置中启用并填写自己的密钥。

## 1. 先确定镜像来源

Docker Hub 在该实验室的直连已超时，不能直接假定默认镜像能拉取。

2026-10-21，Ubuntu 已成功拉取 `m.daocloud.io/docker.io/library/busybox:1.37`。模板现采用 [DaoCloud 官方项目说明](https://github.com/DaoCloud/public-image-mirror)中的增加前缀方式，为七个基础镜像填写完整来源地址；不依赖修改 Docker 全局加速设置或重启 Docker。

该结果仅确认小镜像下载成功，其他镜像的具体版本、白名单和下载速度仍需在 Ubuntu 逐个验证。若实验室有自己的仓库，在服务器 `.env` 中替换完整镜像地址即可。已初始化的 `.env` 不会随模板修改而覆盖，首次初始化请使用本次更新后的文件。

不要把随机教程里的旧加速地址当成保证可用的服务。Docker 镜像加速不等于 Maven/npm 依赖加速，也不等于 GitHub 下载加速。

模板已将所有基础镜像参数化，部署前应固定到确认可用的具体版本或摘要。初始模板中的 `stable`、`3.9` 等浮动 tag 不提供严格可重复的基础环境；实际发布的应用镜像按完整 Git SHA 保存，但回退仍依赖旧镜像保留在本机。

## 2. 将部署文件推送到仓库

这批文件最初只在开发电脑中，必须先由维护者提交并推送到 GitCode `main`，Ubuntu 才能获取。请单独检查部署文件，不要把本机密钥和其他未完成改动混入此次提交。本工具不会自动替你提交或推送工作区。通过向日葵传输部署准备包来初始化基础环境；正式应用发布前，GitCode `main` 仍必须包含部署文件和准备发布的完整业务版本。

然后在 Ubuntu 通过向日葵终端操作。以下示例为首次安装目录；已有同名目录时应先确认内容，不覆盖已有业务目录。

```bash
sudo apt install -y ca-certificates git python3
mkdir -p ~/qiheng-erp-setup
tar -xzf ~/qiheng-erp-deploy.tar.gz -C ~/qiheng-erp-setup
cd ~/qiheng-erp-setup
sudo bash deploy/docker/deploy.sh init
```

上述 `qiheng-erp-deploy.tar.gz` 是维护者从本仓库 `.dockerignore` 与 `deploy/docker/` 打包并通过向日葵传入的准备包，不含业务源码或真实密钥。`init` 只创建 `/opt/qiheng-erp` 下的配置与脚本，不启动容器。首次生成三个独立随机密码，已有配置不会被重置。已经完成基础服务启动和数据库导入时，只替换固定入口脚本，不重复初始化或导入。

| 目录 | 用途 |
|---|---|
| `/opt/qiheng-erp/config/.env` | 私有密码、镜像地址、分支、AI 配置；仅在服务器保存 |
| `/opt/qiheng-erp/config/compose.infra.yaml` | 首次复制的基础设施配置，普通发布不覆盖 |
| `/opt/qiheng-erp/bin/deploy.sh` | 固定的任务入口，普通发布不替换 |
| `/opt/qiheng-erp/config/gitcode.token` | 仅 root 读取的 GitCode 专用令牌，不进入仓库 |
| `/opt/qiheng-erp/gitcode-repository.git` | 专用 GitCode 浅克隆缓存，不操作开发者工作区 |
| `/opt/qiheng-erp/downloads` | 固定 SHA 的本地源码归档；旧 GitHub 缓存和压缩包不会自动删除 |
| `/opt/qiheng-erp/releases/<完整SHA>` | 每次发布的不可变源码与应用 Compose |
| `/opt/qiheng-erp/state` | 已部署版本、失败版本、暂停及互斥锁 |

编辑服务器配置：

```bash
sudo nano /opt/qiheng-erp/config/.env
```

保持 `DEPLOY_BRANCH=main`，填写确认可用的镜像来源。按上面的 GitCode 切换说明保存专用令牌。默认数据库名为 `erp`，应与本地导出的数据库一致。`.env` 会由 Bash 和 Compose 共同读取，使用简单 `KEY=value` 或单引号字符串，不添加命令；密码与密钥不要放入代码仓库，不在共享日志中打印此文件。

Docker 子网默认 `172.30.21.0/24`，Broker 默认 `172.30.21.10`。若与本机已有网络冲突，在首次启动前一起调整 `INFRA_SUBNET` 与 `BROKER_IP`；数据产生后更改网络应视为单独维护。

## 3. 首次启动基础设施

确认镜像来源后，先逐个准备镜像，每个镜像最多等待三分钟，失败即停，不并发拉取所有大镜像：

```bash
sudo bash /opt/qiheng-erp/bin/deploy.sh pull-images
```

全部成功后再启动服务；启动阶段禁止隐式拉取缺失镜像：

```bash
sudo bash /opt/qiheng-erp/bin/deploy.sh infra-up
```

该操作准备持久化卷，启动基础服务，并创建 `erp-supplier-score-recalc`、`erp-system-exception-record` 两个 Topic。MQ 第 8 延迟等级为 4 分钟、第 9 等级为 5 分钟，与当前评分合并窗口一致。Broker 广播的是共享容器网内的固定地址，应用无需访问任何外部 MQ。

MQ 卷由一次性初始化容器赋予镜像用户 UID/GID 3000 权限；MQ 服务本身不以 root 运行。Redis 保留官方 entrypoint 的降权行为。

首次服务启动和 Topic 创建均可能失败，只有命令成功结束后才能进入下一步；不要为了让页面显示绿色而跳过错误。基础服务启动最多等待五分钟，超时可能已有部分服务启动，数据卷和容器会保留；排查后重试，不通过删卷重置环境。

## 4. 一次性导入现有完整数据库

项目目前没有一套可直接通配执行的自动迁移链。`docs/database/sql/` 混有初始化、回滚、开发修补及增量修改，**不要按文件名排序全部执行，也不要将整个目录挂到 MySQL 初始化目录**。

推荐从本地已通过验证的 MySQL 导出当前 `erp` 库的结构和所需数据，只导出这个业务数据库，不导出系统账号、其他数据库或本地 GTID 配置。通过数据库管理工具导出，或在本地有 `mysqldump` 时执行：

```text
mysqldump -h 本地数据库地址 -u 用户名 -p --single-transaction --no-tablespaces --set-gtid-purged=OFF erp > erp.sql
```

通过向日葵文件传输把 SQL 放到 Ubuntu，例如 `/home/cosinelab/erp.sql`。确认这是允许用于测试环境的数据后执行：

```bash
sudo bash /opt/qiheng-erp/bin/deploy.sh import-db /home/cosinelab/erp.sql
```

脚本只允许向空业务库导入，并使用仅有业务库权限的用户。导入后检查必要表并记录 SQL 校验值，不执行项目业务测试。若中途失败产生部分表，脚本拒绝再次覆盖，请人工审查错误后处理，不能删除数据卷来绕过问题。

此步骤不会随以后提交重复执行。首次导入成功后，更改数据库密码也不会自动修改已经存在的数据库用户，密码轮换应单独同步处理。

## 5. 首次构建与发布

```bash
sudo bash /opt/qiheng-erp/bin/deploy.sh deploy
```

脚本从 GitCode HTTPS 拉取指定分支，锁定完整提交 SHA，再从本地缓存归档该 SHA，而不是在构建期间重新读取可能已变化的分支。Git 调用最多等待十分钟，显示进度。仅提取前后端与部署文件，拒绝路径穿越、链接和特殊文件；临时目录验证成功后才发布为完整版本，失败保留临时目录供人工检查，不覆盖已有完整版本。构建两个应用镜像后等待容器启动。前端 `/api/` 代理会去掉 `/api` 前缀并转发到后端 `8081`，与 Vite 开发代理保持一致；AI SSE 禁用代理缓冲。

首次 Git 拉取会包含该提交需要的其他仓库文件；源码归档只包含构建所需目录。不保证首次完整拉取速度，网站返回 HTTP 200 或读取分支成功，也不等于完整代码已下载成功。网络失败时不改变当前应用、基础设施或数据库。

在 Ubuntu 浏览器打开：

```text
http://127.0.0.1:8088
```

Maven 默认使用阿里云公共仓库，npm 默认使用项目 lockfile 中已有的 npmmirror 来源，并使用 Docker 构建缓存。npm lockfile 中若有其他 registry 的绝对下载地址，也必须确保它们在服务器可达。镜像源可用不保证所有 Java/npm 依赖都能立即下载，首次完整构建需要在 Ubuntu 实际验证。

启动检查仅为后端监听端口和前端 `/healthz`，不能替代业务验收。单机切换可能短暂中断服务。构建失败不替换旧应用，启动失败会尝试恢复上个成功的应用版本；数据库变更不会随应用回退。

## 6. 开启自动更新

首次部署成功后，在宝塔进入「计划任务 → 添加任务」：

| 字段 | 设置 |
|---|---|
| 名称 | 启衡 ERP 自动部署 |
| 任务类型 | Shell 脚本 |
| 周期 | 每 N 分钟，N 为 5 |
| 执行内容 | `/bin/bash /opt/qiheng-erp/bin/deploy.sh deploy` |

该固定入口需要具备 Docker 管理和 `/opt/qiheng-erp` 目录访问权限。按本指南以管理员初始化时，宝塔任务使用 root 执行，只读取受维护者控制的 `main` 分支；它不接收外部 WebHook，也不执行 PR 分支。不要把脚本、配置目录的写权限开放给无关用户。公开仓库的 `.env`、开发数据库配置和 AI 本地密钥文件均不进入 Docker 构建上下文。

只将经过本地验证、确实准备发布的提交合并到 GitCode `main`，其他开发分支不会触发部署。没有新提交时不构建；上次尚未结束时跳过本次任务；失败的同一提交不反复构建。本项目开发电脑的 `origin` 仍指向 GitHub，`gitcode` 指向 GitCode，因此使用 `git push gitcode main` 发布；需要两边同步时另执行 `git push origin main`，两次推送分别检查结果。GitHub 也必须保持私有，避免另一边仍然公开代码。服务器须保持开机联网，不需要保持向日葵窗口打开。

不再使用 GitHub API，也不依赖其公开接口限额。仍建议每五分钟检查一次，避免频繁 Git 请求；平台限流、令牌失效或网络错误时应人工处理，不持续高频重试。修改 README 不会自动修改宝塔设置。首次部署成功前不要启用计划任务。

## 7. 日常维护

如果出现“已有部署或维护任务正在运行”，先用 `jobs -l`（原终端）和 `sudo lslocks` 检查占锁进程。`Ctrl+Z` 只会暂停任务，不会释放锁；原终端可用 `fg` 恢复后按 `Ctrl+C` 结束。不要删除锁文件或直接杀死所有 Docker 进程。旧脚本的子进程可能继承锁，须根据进程编号核对后处理。

当前脚本由外层 `flock --close` 管理锁，不向业务子进程传递锁描述符；RocketMQ 管理命令在容器内、宿主机均设强制超时。初始化先等待 Broker 注册，再创建并校验两个 Topic，失败不写入准备完成标记。更新脚本不能自动清理旧脚本遗留的暂停进程。

RocketMQ 管理命令单独从 `/dev/null` 读取标准输入，避免 Compose 在终端环境中等待输入。不要对所有容器命令统一关闭标准输入：`import-db` 必须保留 SQL 文件输入。

```bash
sudo bash /opt/qiheng-erp/bin/deploy.sh status
sudo bash /opt/qiheng-erp/bin/deploy.sh logs
sudo bash /opt/qiheng-erp/bin/deploy.sh pause
sudo bash /opt/qiheng-erp/bin/deploy.sh resume
sudo bash /opt/qiheng-erp/bin/deploy.sh retry
sudo bash /opt/qiheng-erp/bin/deploy.sh rollback
```

`retry` 用于网络或配置修复后人工重试失败提交；`rollback` 将当前版本标记失败，避免下次轮询又立即发布它。首次发布失败而无上一版本时，应在宝塔 Docker 页面查看失败容器，不会虚构回退成功。

基础设施升级、数据库增量迁移、部署脚本更新与备份恢复属于单独维护，不会因为普通应用推送而自动执行。先暂停自动发布、备份数据、审查变更，再实施；普通应用恢复不恢复数据库。需要更新稳定脚本时，人工核对后复制新脚本到 `bin`，不要重置服务器私有配置。

保留上一版本镜像、源码和命名卷。不执行 `docker compose down -v`，不使用全局镜像清理删除回退所需镜像。初版未加入自动清理，后续应根据备份与回退保留策略维护磁盘。
