#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
DEPLOY_ROOT=${DEPLOY_ROOT:-/opt/qiheng-erp}
ACTION=${1:-deploy}
LOCK_HELD=false
if [[ "$ACTION" == --lock-held ]]; then
    LOCK_HELD=true
    shift
    ACTION=${1:-deploy}
fi

log() {
    printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

fail() {
    log "$*" >&2
    exit 1
}

[[ "$DEPLOY_ROOT" == /* && "$DEPLOY_ROOT" != / ]] || fail 'DEPLOY_ROOT 必须是非根目录的绝对路径。'
CONFIG_DIR="$DEPLOY_ROOT/config"
STATE_DIR="$DEPLOY_ROOT/state"
RELEASES_DIR="$DEPLOY_ROOT/releases"
CONFIG_FILE="$CONFIG_DIR/.env"
REPOSITORY_DIR="$DEPLOY_ROOT/gitcode-repository.git"

for required_command in docker git python3 flock timeout tar sha256sum; do
    command -v "$required_command" >/dev/null || fail "缺少命令：$required_command"
done
docker compose version >/dev/null
mkdir -p "$CONFIG_DIR" "$STATE_DIR" "$RELEASES_DIR" "$DEPLOY_ROOT/bin"
if [[ "$LOCK_HELD" == false ]]; then
    if flock --exclusive --nonblock --conflict-exit-code 75 --close "$STATE_DIR/deploy.lock" \
        bash "$SCRIPT_DIR/deploy.sh" --lock-held "$@"; then
        exit 0
    else
        lock_status=$?
        if [[ "$lock_status" == 75 ]]; then
            log '已有部署或维护任务正在运行，本次跳过。请用 jobs -l 和 sudo lslocks 检查占锁任务，不要删除锁文件。'
            exit 0
        fi
        exit "$lock_status"
    fi
fi

if [[ "$ACTION" == init ]]; then
    command -v openssl >/dev/null || fail '初始化需要 openssl 生成随机密码。'
    if [[ ! -f "$CONFIG_FILE" ]]; then
        sed -e "s/^MYSQL_ROOT_PASSWORD=.*/MYSQL_ROOT_PASSWORD=$(openssl rand -hex 24)/" \
            -e "s/^MYSQL_PASSWORD=.*/MYSQL_PASSWORD=$(openssl rand -hex 24)/" \
            -e "s/^REDIS_PASSWORD=.*/REDIS_PASSWORD=$(openssl rand -hex 24)/" \
            "$SCRIPT_DIR/.env.example" > "$CONFIG_FILE"
    fi
    if [[ ! -f "$CONFIG_DIR/compose.infra.yaml" ]]; then
        cp "$SCRIPT_DIR/compose.infra.yaml" "$CONFIG_DIR/compose.infra.yaml"
    fi
    if [[ "$SCRIPT_DIR/deploy.sh" != "$DEPLOY_ROOT/bin/deploy.sh" ]]; then
        cp "$SCRIPT_DIR/deploy.sh" "$DEPLOY_ROOT/bin/deploy.sh"
    fi
    chmod 600 "$CONFIG_FILE"
    chmod 700 "$DEPLOY_ROOT/bin/deploy.sh"
    log "初始化完成。配置位于 $CONFIG_FILE，已有密码及基础设施配置不会被覆盖。"
    log '下一步：确认镜像来源，再执行 infra-up。此命令尚未启动任何服务。'
    exit 0
fi

[[ -f "$CONFIG_FILE" ]] || fail '请先在仓库根目录执行：sudo bash deploy/docker/deploy.sh init'
set -a
source "$CONFIG_FILE"
set +a
unset APP_VERSION
for secret_name in MYSQL_ROOT_PASSWORD MYSQL_PASSWORD REDIS_PASSWORD; do
    secret_value=${!secret_name:-}
    [[ -n "$secret_value" && "$secret_value" != CHANGE_ME* ]] || fail "请配置 $secret_name。"
done
[[ "${MYSQL_DATABASE:-erp}" =~ ^[a-zA-Z0-9_]+$ ]] || fail '数据库名只允许字母、数字和下划线。'
[[ "${WEB_PORT:-8088}" =~ ^[0-9]+$ ]] || fail 'WEB_PORT 必须是端口数字。'
infra() {
    docker compose --env-file "$CONFIG_FILE" -p qiheng-erp-infra \
        -f "$CONFIG_DIR/compose.infra.yaml" "$@"
}

application() {
    local version=$1
    shift
    [[ "$version" =~ ^[0-9a-f]{40}$ ]] || fail '应用版本必须是完整 Git 提交编号。'
    [[ -f "$RELEASES_DIR/$version/deploy/docker/compose.app.yaml" ]] || fail "版本目录不存在：$version"
    APP_VERSION="$version" timeout -k 10s 1800 docker compose --env-file "$CONFIG_FILE" -p qiheng-erp-app \
        -f "$RELEASES_DIR/$version/deploy/docker/compose.app.yaml" "$@"
}

database() {
    infra exec -T mysql sh -ec \
        'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" "$@"' \
        sh "$@"
}

mqadmin() {
    timeout -k 5s 50s docker compose --env-file "$CONFIG_FILE" -p qiheng-erp-infra \
        -f "$CONFIG_DIR/compose.infra.yaml" exec -T broker sh -ec \
        'command -v timeout >/dev/null || { echo "容器缺少 timeout，无法安全执行管理命令。" >&2; exit 1; }; exec timeout -k 5s 40s sh mqadmin "$@"' \
        sh "$@" </dev/null
}

create_topics() {
    local topic_name command_output attempt broker_ready=false
    for attempt in 1 2 3; do
        log "等待 Broker 注册到 NameServer（$attempt/3，每次最多 55 秒）。"
        if command_output=$(mqadmin clusterList -n erp-namesrv:9876 2>&1); then
            if awk '$1 == "DefaultCluster" && $2 == "erp-broker" && $3 == "0" { found=1 } END { exit !found }' <<< "$command_output"; then
                broker_ready=true
                break
            fi
        fi
        printf '%s\n' "$command_output"
        if [[ "$attempt" != 3 ]]; then
            sleep 5
        fi
    done
    [[ "$broker_ready" == true ]] || { log '未确认 Broker 注册，停止创建 Topic，请检查 Broker 日志。'; return 1; }
    for topic_name in erp-supplier-score-recalc erp-system-exception-record; do
        log "创建或确认 Topic：$topic_name，每次最多 55 秒。"
        if command_output=$(mqadmin updateTopic -n erp-namesrv:9876 \
            -c DefaultCluster -t "$topic_name" -r 4 -w 4 2>&1); then
            printf '%s\n' "$command_output"
            grep -Eq '^create topic to .+ success\.$' <<< "$command_output" || return 1
        else
            printf '%s\n' "$command_output"
            log "Topic 管理命令失败或超时：$topic_name。"
            return 1
        fi
    done
    touch "$STATE_DIR/infra-ready"
}

gitcode_git() {
    local request_timeout=600
    if [[ "${1:-}" == ls-remote ]]; then
        request_timeout=40
    fi
    local credential_helper='!f() {
        [ "$1" = get ] || exit 0
        protocol= host= path=
        while IFS= read -r line && [ -n "$line" ]; do
            case "$line" in
                protocol=*) protocol=${line#protocol=} ;;
                host=*) host=${line#host=} ;;
                path=*) path=${line#path=} ;;
            esac
        done
        [ "$protocol" = https ] && [ "$host" = gitcode.com ] && [ "$path" = "$DEPLOY_GITCODE_PATH" ] || exit 1
        printf "username=%s\n" "$DEPLOY_GITCODE_USER"
        printf "password=%s\n" "$(tr -d "\r\n" < "$DEPLOY_GITCODE_TOKEN_FILE")"
    }; f'
    DEPLOY_GITCODE_USER="${GITCODE_USERNAME:-LI_124301012}" \
    DEPLOY_GITCODE_PATH="$repository_slug.git" \
    DEPLOY_GITCODE_TOKEN_FILE="${GITCODE_TOKEN_FILE:-$CONFIG_DIR/gitcode.token}" \
    GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/bin/false \
    GIT_TRACE=0 GIT_TRACE_CURL=0 GIT_TRACE_REDACT=1 \
    env -u GIT_CURL_VERBOSE timeout -k 10s "$request_timeout" git --git-dir="$REPOSITORY_DIR" \
        -c http.proxy= -c http.followRedirects=false \
        -c credential.helper= -c credential.helper="$credential_helper" -c credential.useHttpPath=true \
        -c http.lowSpeedLimit=1 -c http.lowSpeedTime=120 "$@" </dev/null
}

configure_source() {
    local source_metadata configured_remote
    source_metadata=$(python3 - "$DEPLOY_REPO" "${DEPLOY_BRANCH:-main}" <<'PY'
import re
import sys
from urllib.parse import urlsplit

url = urlsplit(sys.argv[1])
path = url.path.removesuffix(".git")
if (url.scheme != "https" or url.netloc != "gitcode.com" or url.query
        or url.fragment or not re.fullmatch(r"/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", path)):
    raise SystemExit("DEPLOY_REPO 必须是 GitCode 仓库的 HTTPS 地址，不能包含账号或令牌。")
if any(part in (".", "..") for part in path.split("/")[1:]):
    raise SystemExit("仓库地址无效。")
branch = sys.argv[2]
if not branch or any(ord(character) < 32 for character in branch):
    raise SystemExit("分支名无效。")
print(path.lstrip("/"))
PY
    ) || fail '无法解析 GitCode 仓库和分支配置。'
    repository_slug=$source_metadata
    git check-ref-format --branch "${DEPLOY_BRANCH:-main}" >/dev/null || fail '分支名无效。'
    [[ "${GITCODE_USERNAME:-LI_124301012}" =~ ^[A-Za-z0-9_.-]+$ ]] || fail 'GitCode 用户名格式无效。'
    python3 - "${GITCODE_TOKEN_FILE:-$CONFIG_DIR/gitcode.token}" <<'PY'
import os
import stat
import sys
from pathlib import Path

path = Path(sys.argv[1])
if path.is_symlink() or not path.is_file():
    raise SystemExit("缺少 GitCode 令牌文件，或文件是符号链接。")
metadata = path.stat()
if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o077:
    raise SystemExit("令牌文件必须属于执行用户，且权限为 600 或更严格。")
if path.parent.stat().st_mode & 0o022:
    raise SystemExit("令牌所在目录不能允许组或其他用户写入。")
token = path.read_text(encoding="utf-8").rstrip("\r\n")
if not token or len(token) > 4096 or not token.isascii() or any(character.isspace() for character in token):
    raise SystemExit("令牌文件应只包含一个有效令牌，不要添加引号或其他配置。")
PY
    if [[ ! -d "$REPOSITORY_DIR" ]]; then
        git init --bare --quiet "$REPOSITORY_DIR"
        git --git-dir="$REPOSITORY_DIR" remote add origin "https://gitcode.com/$repository_slug.git"
    fi
    configured_remote=$(git --git-dir="$REPOSITORY_DIR" remote get-url origin)
    [[ "$configured_remote" == "https://gitcode.com/$repository_slug.git" ]] || fail 'GitCode 缓存仓库地址与配置不一致，请人工检查。'
}

fetch_release() {
    configure_source
    log "通过 GitCode 检查并获取 ${DEPLOY_BRANCH:-main} 分支，最多等待十分钟。"
    gitcode_git fetch --progress --depth=1 origin \
        "+refs/heads/${DEPLOY_BRANCH:-main}:refs/heads/deployment" \
        || fail 'GitCode 拉取失败；请检查网络、令牌有效期和仓库读取权限，不会更改现有应用。'
    candidate_version=$(git --git-dir="$REPOSITORY_DIR" rev-parse refs/heads/deployment)
    [[ "$candidate_version" =~ ^[0-9a-f]{40}$ ]] || fail '没有获取到合法提交编号。'
    log "GitCode 版本：$candidate_version。"
}

prepare_release() {
    local release_dir="$RELEASES_DIR/$candidate_version"
    if [[ -f "$release_dir/.complete" ]]; then
        return
    fi
    [[ ! -e "$release_dir" ]] || fail "发现未完成的版本目录，请先人工检查：$release_dir"
    local archive_file stage_dir
    mkdir -p "$DEPLOY_ROOT/downloads"
    archive_file="$DEPLOY_ROOT/downloads/$candidate_version.tar.gz"
    log '从 GitCode 缓存提取固定提交的前后端和部署文件。'
    timeout -k 5s 60s git --git-dir="$REPOSITORY_DIR" archive --format=tar.gz \
        --prefix="${repository_slug##*/}-$candidate_version/" "$candidate_version" \
        erp-server erp-web deploy/docker .dockerignore > "$archive_file.part" \
        || fail '源码归档失败，现有应用和数据库未修改。'
    mv -- "$archive_file.part" "$archive_file"
    stage_dir=$(mktemp -d "$RELEASES_DIR/.prepare-$candidate_version.XXXXXX")
    log '检查源码包并提取部署所需文件。'
    if ! python3 - "$archive_file" "$stage_dir" "${repository_slug##*/}-$candidate_version" <<'PY'
import shutil
import sys
import tarfile
from pathlib import Path, PurePosixPath

archive, destination, expected_root = sys.argv[1:]
destination = Path(destination)
with tarfile.open(archive, "r:gz") as source:
    selected = []
    seen = set()
    for member in source.getmembers():
        path = PurePosixPath(member.name)
        if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != expected_root:
            raise SystemExit("源码包包含不符合预期的路径。")
        relative = path.parts[1:]
        if not relative:
            continue
        allowed = (relative[0] in ("erp-server", "erp-web")
                   or relative[:2] == ("deploy", "docker")
                   or relative == (".dockerignore",))
        if not allowed:
            continue
        if not (member.isfile() or member.isdir()) or path in seen:
            raise SystemExit("部署目录包含链接、特殊文件或重复路径。")
        seen.add(path)
        selected.append((member, relative))
    for member, relative in selected:
        target = destination.joinpath(*relative)
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.extractfile(member) as reader, target.open("xb") as writer:
                shutil.copyfileobj(reader, writer)
            target.chmod(member.mode & 0o777)
required = (".dockerignore", "erp-server/pom.xml", "erp-web/package.json", "erp-web/package-lock.json",
            "deploy/docker/compose.app.yaml", "deploy/docker/Dockerfile.server", "deploy/docker/Dockerfile.web")
if any(not (destination / path).is_file() for path in required):
    raise SystemExit("源码包缺少必要构建或部署文件。")
PY
    then
        fail "源码包检查或提取失败，不会启用该版本；临时目录保留供检查：$stage_dir"
    fi
    sha256sum "$archive_file" > "$stage_dir/.source.sha256"
    touch "$stage_dir/.complete"
    mv -- "$stage_dir" "$release_dir"
}

activate() {
    local version=$1
    application "$version" up -d --no-build --pull never --wait --wait-timeout 180
}

read_version() {
    if [[ -f "$STATE_DIR/$1" ]]; then
        cat "$STATE_DIR/$1"
    fi
}

save_version() {
    printf '%s\n' "$2" > "$STATE_DIR/$1.tmp"
    mv "$STATE_DIR/$1.tmp" "$STATE_DIR/$1"
}

case "$ACTION" in
    check-source)
        configure_source
        log '检查 GitCode 私有仓库读取权限，最多约 50 秒，不下载完整代码、不更新容器。'
        source_reference=$(gitcode_git ls-remote --exit-code origin "refs/heads/${DEPLOY_BRANCH:-main}") \
            || fail '仓库检查失败，请检查网络、令牌权限、有效期和分支是否存在。'
        source_version=${source_reference%%$'\t'*}
        [[ "$source_version" =~ ^[0-9a-f]{40}$ && "$source_reference" == "$source_version"$'\t'"refs/heads/${DEPLOY_BRANCH:-main}" ]] \
            || fail 'GitCode 返回的分支或提交编号无效。'
        log "GitCode 版本：$source_version。"
        log 'GitCode 仓库读取检查通过。'
        ;;
    pull-images)
        for image_variable in MYSQL_IMAGE REDIS_IMAGE ROCKETMQ_IMAGE NODE_IMAGE NGINX_IMAGE MAVEN_IMAGE JAVA_IMAGE; do
            image_reference=${!image_variable:-}
            [[ -n "$image_reference" ]] || fail "请先配置 $image_variable。"
            log "准备镜像：$image_reference，每个镜像最多等待三分钟。"
            timeout -k 10s 180 docker pull "$image_reference" || fail '镜像下载失败或超时；已下载内容保留，请先处理镜像来源再重试。'
        done
        log '基础镜像已准备完成，尚未启动服务。'
        ;;
    infra-up)
        infra config --quiet
        timeout -k 10s 300 docker compose --env-file "$CONFIG_FILE" -p qiheng-erp-infra \
            -f "$CONFIG_DIR/compose.infra.yaml" up -d --pull never --wait --wait-timeout 240
        create_topics || fail '基础服务已启动，但 Topic 创建未完成；请查看日志后重新执行 infra-up。'
        log '基础服务与 Topic 已准备完成，下一步人工导入数据库。'
        ;;
    import-db)
        [[ -f "$STATE_DIR/infra-ready" ]] || fail '请先执行 infra-up。'
        database_file=${2:-}
        [[ -f "$database_file" ]] || fail '请提供当前完整数据库导出的 SQL 文件路径。'
        table_count=$(database --batch --skip-column-names -e \
            'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();')
        [[ "$table_count" == 0 ]] || fail '数据库已有表，拒绝重复导入；请人工检查，不会清空现有数据库。'
        database --one-database < "$database_file"
        essential_tables=$(database --batch --skip-column-names -e \
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('sys_user','sys_role','sys_permission','product','warehouse_stock','supplier','sales_order','return_order','system_exception');")
        [[ "$essential_tables" == 9 ]] || fail '导入后缺少必要业务表，请人工核对；不会自动重试或清空数据库。'
        sha256sum "$database_file" > "$STATE_DIR/database-ready"
        log '数据库已导入并记录来源校验值，后续应用发布不会再次导入 SQL。'
        ;;
    deploy|retry)
        [[ ! -f "$STATE_DIR/paused" ]] || { log '自动部署已暂停。'; exit 0; }
        [[ -f "$STATE_DIR/infra-ready" && -f "$STATE_DIR/database-ready" ]] || fail '基础服务或数据库尚未准备完成。'
        docker network inspect qiheng-erp-net >/dev/null
        fetch_release
        current_version=$(read_version current)
        [[ "$candidate_version" != "$current_version" ]] || { log '没有新版本。'; exit 0; }
        failed_version=$(read_version failed)
        if [[ "$candidate_version" == "$failed_version" && "$ACTION" != retry ]]; then
            log '该提交上次发布失败，请修复后推送新提交，或人工执行 retry。'
            exit 0
        fi
        prepare_release
        application "$candidate_version" config --quiet
        log "开始构建 $candidate_version，不运行项目测试。"
        if ! application "$candidate_version" build server web; then
            save_version failed "$candidate_version"
            fail '构建失败，原应用未替换。'
        fi
        if ! activate "$candidate_version"; then
            save_version failed "$candidate_version"
            if [[ -n "$current_version" ]]; then
                log "新版本未正常启动，正在恢复 $current_version。"
                activate "$current_version" || fail '恢复旧版本失败，请立即检查应用日志。'
            fi
            fail '发布失败，成功版本记录保持不变。'
        fi
        if [[ -n "$current_version" ]]; then
            save_version previous "$current_version"
        fi
        save_version current "$candidate_version"
        log "发布成功：$candidate_version。请在 Ubuntu 浏览器访问 http://${WEB_BIND:-127.0.0.1}:${WEB_PORT:-8088}"
        ;;
    rollback)
        previous_version=$(read_version previous)
        current_version=$(read_version current)
        [[ -n "$previous_version" && -n "$current_version" ]] || fail '没有可恢复的上一版本。'
        activate "$previous_version"
        save_version failed "$current_version"
        save_version current "$previous_version"
        save_version previous "$current_version"
        log "已恢复 $previous_version，原版本已标记失败，轮询不会立即重新发布它。数据库未变更。"
        ;;
    pause)
        touch "$STATE_DIR/paused"
        log '已暂停自动部署。'
        ;;
    resume)
        [[ ! -f "$STATE_DIR/paused" ]] || unlink "$STATE_DIR/paused"
        log '已恢复自动部署，下次计划任务将检查更新。'
        ;;
    status)
        infra ps -a
        current_version=$(read_version current)
        [[ -z "$current_version" ]] || application "$current_version" ps -a
        ;;
    logs)
        current_version=$(read_version current)
        [[ -n "$current_version" ]] || fail '尚无成功发布版本，请在宝塔 Docker 页面查看失败容器日志。'
        application "$current_version" logs --tail 100
        ;;
    *)
        fail '用法：deploy.sh init|check-source|pull-images|infra-up|import-db SQL文件|deploy|retry|rollback|pause|resume|status|logs'
        ;;
esac
