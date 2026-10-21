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
REPOSITORY_DIR="$DEPLOY_ROOT/repository.git"
CONFIG_FILE="$CONFIG_DIR/.env"

for required_command in docker git flock timeout tar sha256sum; do
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
git check-ref-format --branch "${DEPLOY_BRANCH:-main}" >/dev/null

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

fetch_release() {
    if [[ ! -d "$REPOSITORY_DIR" ]]; then
        git init --bare --quiet "$REPOSITORY_DIR"
        git --git-dir="$REPOSITORY_DIR" remote add origin "$DEPLOY_REPO"
    fi
    local configured_remote
    configured_remote=$(git --git-dir="$REPOSITORY_DIR" remote get-url origin)
    [[ "$configured_remote" == "$DEPLOY_REPO" ]] || fail '缓存仓库地址与配置不一致，请人工检查，脚本不会覆盖它。'
    log "检查 ${DEPLOY_BRANCH:-main} 分支。"
    GIT_TERMINAL_PROMPT=0 timeout -k 10s 180 git --git-dir="$REPOSITORY_DIR" \
        -c http.lowSpeedLimit=1024 -c http.lowSpeedTime=30 fetch --quiet --depth=1 origin \
        "+refs/heads/${DEPLOY_BRANCH:-main}:refs/heads/deployment"
    candidate_version=$(git --git-dir="$REPOSITORY_DIR" rev-parse refs/heads/deployment)
    [[ "$candidate_version" =~ ^[0-9a-f]{40}$ ]] || fail '没有获取到合法提交编号。'
}

prepare_release() {
    local release_dir="$RELEASES_DIR/$candidate_version"
    if [[ -f "$release_dir/.complete" ]]; then
        return
    fi
    mkdir -p "$release_dir"
    git --git-dir="$REPOSITORY_DIR" archive "$candidate_version" \
        erp-server erp-web deploy/docker .dockerignore | tar -x -C "$release_dir"
    touch "$release_dir/.complete"
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
        fail '用法：deploy.sh init|pull-images|infra-up|import-db SQL文件|deploy|retry|rollback|pause|resume|status|logs'
        ;;
esac
