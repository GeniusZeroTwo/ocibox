#!/bin/bash
# ================================================================
#  OCI Personal - 甲骨文云个人管理面板 一键部署脚本
#  完全面向客户自用，零商业租户与配额依赖，极简纯净 SQLite 架构
# ================================================================

set -uo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
PLAIN='\033[0m'

echo -e "${CYAN}"
cat << "BANNER"
  ____   ____ ___ ____              
 / __ \ / ___|_ _| __ )  _____  __ 
| |  | | |    | ||  _ \ / _ \ \/ / 
| |__| | |___ | || |_) | (_) >  <  
 \____/ \____|___|____/ \___/_/\_\ 
BANNER
echo -e "${PLAIN}"
echo -e "${GREEN}>>> 正在启动 OCIBox 个人专属管理面板一键部署向导...${PLAIN}\n"

# 1. 检查 root 权限
if [[ $EUID -ne 0 ]]; then
    echo -e "${RED}❌ 错误: 本安装脚本必须以 root 用户权限运行！请使用 sudo -i 或 su root 切换后重试。${PLAIN}"
    exit 1
fi

INSTALL_DIR="/root/ocibox"
# 自动检测并平滑迁移旧版目录 /root/oci-personal
if [[ -d "/root/oci-personal" && ! -d "${INSTALL_DIR}" ]]; then
    echo -e "${GREEN}📦 检测到历史数据目录 /root/oci-personal，正在自动平滑迁移至 ${INSTALL_DIR}...${PLAIN}"
    mv /root/oci-personal "${INSTALL_DIR}"
fi
mkdir -p "${INSTALL_DIR}/data" "${INSTALL_DIR}/keys" "${INSTALL_DIR}/logs"
cd "${INSTALL_DIR}" || exit 1

# 2. 自动检测历史配置与交互式收集
echo -e "${CYAN}------------------- [ 基础配置向导 ] -------------------${PLAIN}"

get_yaml_val() {
    local key="$1"
    local file="$2"
    if [[ -f "${file}" ]]; then
        grep -E "^[[:space:]]*${key}:" "${file}" | head -n 1 | sed -E "s/^[[:space:]]*${key}:[[:space:]]*\"?(.*)\"?[[:space:]]*$/\1/" | sed 's/"//g' | tr -d '\r'
    else
        echo ""
    fi
}

is_configured() {
    local val="$1"
    [[ -n "${val}" && ! "${val}" =~ ^\$\{ && "${val}" != "\"\"" && "${val}" != "\x27\x27" ]]
}

# 自动放行防火墙指定端口 (支持 ufw / firewalld / iptables / ip6tables)
open_port() {
    local target_port="$1"
    if [[ -z "${target_port}" ]]; then
        return 0
    fi

    echo -e "🛡️  正在自动放行系统防火墙端口: ${GREEN}${target_port}/tcp${PLAIN}..."

    # 1. 尝试 ufw (Ubuntu / Debian)
    if command -v ufw >/dev/null 2>&1; then
        if ufw status 2>/dev/null | grep -qi "active"; then
            ufw allow "${target_port}/tcp" >/dev/null 2>&1 || true
            ufw reload >/dev/null 2>&1 || true
        fi
    fi

    # 2. 尝试 firewalld (CentOS / RHEL / Fedora / AlmaLinux / RockyLinux / Oracle Linux)
    if command -v firewall-cmd >/dev/null 2>&1; then
        if systemctl is-active --quiet firewalld 2>/dev/null; then
            firewall-cmd --zone=public --add-port="${target_port}/tcp" --permanent >/dev/null 2>&1 || true
            firewall-cmd --reload >/dev/null 2>&1 || true
        fi
    fi

    # 3. 尝试 iptables (最通用底座，特别针对甲骨文云 OCI 自带 Linux 镜像)
    # 甲骨文云 Ubuntu / Oracle Linux 默认可能在 INPUT 链尾部带 REJECT/DROP 规则，故必须用 -I 插入到首行
    if command -v iptables >/dev/null 2>&1; then
        if ! iptables -C INPUT -p tcp --dport "${target_port}" -j ACCEPT 2>/dev/null; then
            iptables -I INPUT -p tcp --dport "${target_port}" -j ACCEPT 2>/dev/null || true
        fi

        # 规则持久化，防止服务器重启后失效
        if command -v netfilter-persistent >/dev/null 2>&1; then
            netfilter-persistent save >/dev/null 2>&1 || true
        elif [[ -d "/etc/iptables" ]]; then
            iptables-save > /etc/iptables/rules.v4 2>/dev/null || true
        elif command -v service >/dev/null 2>&1 && service iptables status >/dev/null 2>&1; then
            service iptables save >/dev/null 2>&1 || true
        fi
    fi

    # 4. 尝试 ip6tables (IPv6 防火墙放行)
    if command -v ip6tables >/dev/null 2>&1; then
        if ! ip6tables -C INPUT -p tcp --dport "${target_port}" -j ACCEPT 2>/dev/null; then
            ip6tables -I INPUT -p tcp --dport "${target_port}" -j ACCEPT 2>/dev/null || true
        fi
        if [[ -d "/etc/iptables" ]]; then
            ip6tables-save > /etc/iptables/rules.v6 2>/dev/null || true
        fi
    fi

    echo -e "  ${GREEN}✅ 端口 ${target_port}/tcp 防火墙规则已成功放行！${PLAIN}"
}

EXISTING_CONFIG="${INSTALL_DIR}/application.yml"
if [[ ! -f "${EXISTING_CONFIG}" && -f "/root/oci-personal/application.yml" ]]; then
    EXISTING_CONFIG="/root/oci-personal/application.yml"
fi
EXISTING_PORT=""
EXISTING_ADMIN_USER=""
EXISTING_ADMIN_PWD=""
EXISTING_BOT_TOKEN=""
EXISTING_ADMIN_ID=""

if [[ -f "${EXISTING_CONFIG}" ]]; then
    EXISTING_PORT=$(get_yaml_val "port" "${EXISTING_CONFIG}")
    EXISTING_ADMIN_USER=$(get_yaml_val "admin-username" "${EXISTING_CONFIG}")
    EXISTING_ADMIN_PWD=$(get_yaml_val "admin-password" "${EXISTING_CONFIG}")
    EXISTING_BOT_TOKEN=$(get_yaml_val "bot-token" "${EXISTING_CONFIG}")
    EXISTING_ADMIN_ID=$(get_yaml_val "admin-id" "${EXISTING_CONFIG}")
    echo -e "${GREEN}🔍 检测到本地已存在历史配置 (${EXISTING_CONFIG})，支持一键无损沿用！${PLAIN}\n"
fi

# 端口配置
while true; do
    if is_configured "${EXISTING_PORT}"; then
        read -rp "👉 请输入管理后台服务端口 [检测到原配置: ${EXISTING_PORT}, 回车直接沿用]: " INPUT_PORT
        PORT="${INPUT_PORT:-${EXISTING_PORT}}"
    else
        read -rp "👉 请输入管理后台服务端口 [默认: 5000]: " INPUT_PORT
        PORT="${INPUT_PORT:-5000}"
    fi

    if [[ "${PORT}" =~ ^[0-9]+$ ]] && [ "${PORT}" -ge 1 ] && [ "${PORT}" -le 65535 ]; then
        break
    else
        echo -e "${RED}❌ 端口格式无效，请输入 1-65535 之间的有效端口号！${PLAIN}"
    fi
done

# 🛡️ 自动放行设置的服务端口
open_port "${PORT}"

# 管理员账户名配置
if is_configured "${EXISTING_ADMIN_USER}"; then
    read -rp "👉 请输入管理员账户名 [检测到原配置: ${EXISTING_ADMIN_USER}, 回车直接沿用]: " INPUT_USER
    ADMIN_USER="${INPUT_USER:-${EXISTING_ADMIN_USER}}"
else
    read -rp "👉 请输入管理员账户名 [默认: admin]: " INPUT_USER
    ADMIN_USER="${INPUT_USER:-admin}"
fi

# 管理员密码配置 (自动生成32位带符号高强度密码)
if is_configured "${EXISTING_ADMIN_PWD}"; then
    read -rp "👉 请设置管理员登录密码 [检测到已有密码, 回车保留原密码不修改]: " INPUT_PWD
    ADMIN_PWD="${INPUT_PWD:-${EXISTING_ADMIN_PWD}}"
else
    DEFAULT_PWD=$(LC_ALL=C tr -dc 'A-Za-z0-9@#$%&*!_=+' < /dev/urandom 2>/dev/null | head -c 32)
    [[ -z "${DEFAULT_PWD}" || ${#DEFAULT_PWD} -lt 32 ]] && DEFAULT_PWD=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9@#$%&*!_=+' | head -c 32)
    read -rp "👉 请设置管理员登录密码 [回车自动生成32位带符号密码: ${DEFAULT_PWD}]: " INPUT_PWD
    ADMIN_PWD="${INPUT_PWD:-${DEFAULT_PWD}}"
fi

# Telegram 绑定选项
echo -e "\n${CYAN}------------------- [ Telegram 机器人配置 ] -------------------${PLAIN}"
BOT_TOKEN=""
ADMIN_ID=""
TG_STATUS="未绑定 (随时可在后台或配置文件中开启)"

if is_configured "${EXISTING_BOT_TOKEN}" && is_configured "${EXISTING_ADMIN_ID}"; then
    MASKED_TOKEN="${EXISTING_BOT_TOKEN:0:6}******${EXISTING_BOT_TOKEN: -4}"
    echo -e "${GREEN}🔔 【检测到您此前已成功绑定过 Telegram 运维机器人】${PLAIN}"
    echo -e "   - 👑 管理员 Chat ID : ${YELLOW}${EXISTING_ADMIN_ID}${PLAIN}"
    echo -e "   - 🔑 Bot Token 凭据 : ${YELLOW}${MASKED_TOKEN}${PLAIN}"
    read -rp "👉 是否直接保留现有 Telegram 机器人绑定？(Y/n, 默认 Y 沿用): " KEEP_TG
    KEEP_TG="${KEEP_TG:-Y}"
    if [[ "${KEEP_TG}" =~ ^[Yy]$ ]]; then
        BOT_TOKEN="${EXISTING_BOT_TOKEN}"
        ADMIN_ID="${EXISTING_ADMIN_ID}"
        TG_STATUS="已绑定 (Admin ID: ${ADMIN_ID}) [保留原配置]"
        echo -e "  ${GREEN}✅ 已自动沿用此前绑定的 Telegram 机器人！${PLAIN}"
    else
        echo -e "  ${YELLOW}ℹ️ 您选择了重新设置或解绑 Telegram 机器人。${PLAIN}"
        read -rp "👉 是否需要绑定新的 Telegram 机器人？(y/N, 默认 N 解除绑定): " RE_BIND_TG
        if [[ "${RE_BIND_TG}" =~ ^[Yy]$ ]]; then
            read -rp "  请输入 Telegram Bot Token (从 @BotFather 获取): " INPUT_BOT_TOKEN
            read -rp "  请输入管理员 Telegram Chat ID (从 @userinfobot 获取): " INPUT_ADMIN_ID
            BOT_TOKEN="${INPUT_BOT_TOKEN:-}"
            ADMIN_ID="${INPUT_ADMIN_ID:-}"
            if is_configured "${BOT_TOKEN}" && is_configured "${ADMIN_ID}"; then
                TG_STATUS="已绑定 (Admin ID: ${ADMIN_ID})"
                echo -e "  ${GREEN}✅ 新的 Telegram 机器人信息已记录！${PLAIN}"
            else
                BOT_TOKEN=""
                ADMIN_ID=""
                TG_STATUS="未绑定"
                echo -e "  ${YELLOW}⚠️ 输入不完整，已设置为未绑定状态。${PLAIN}"
            fi
        else
            BOT_TOKEN=""
            ADMIN_ID=""
            TG_STATUS="未绑定"
            echo -e "  ${YELLOW}ℹ️ 已解除 Telegram 机器人绑定。${PLAIN}"
        fi
    fi
else
    echo -e "说明: 机器人供您个人独占远程运维（查看实例、一键换IP、开关机、重装系统、流量预警推送）"
    read -rp "👉 是否需要绑定您的 Telegram 运维机器人？(y/N, 默认 N): " BIND_TG
    if [[ "${BIND_TG}" =~ ^[Yy]$ ]]; then
        read -rp "  请输入 Telegram Bot Token (从 @BotFather 获取): " INPUT_BOT_TOKEN
        read -rp "  请输入管理员 Telegram Chat ID (从 @userinfobot 获取): " INPUT_ADMIN_ID
        BOT_TOKEN="${INPUT_BOT_TOKEN:-}"
        ADMIN_ID="${INPUT_ADMIN_ID:-}"
        if is_configured "${BOT_TOKEN}" && is_configured "${ADMIN_ID}"; then
            TG_STATUS="已绑定 (Admin ID: ${ADMIN_ID})"
            echo -e "  ${GREEN}✅ Telegram 机器人信息已记录！${PLAIN}"
        else
            echo -e "  ${YELLOW}⚠️ 输入不完整，已跳过 Telegram 绑定。${PLAIN}"
        fi
    fi
fi

# 3. 安装依赖运行环境 (纯轻量化，免 Redis)
echo -e "\n${CYAN}------------------- [ 环境依赖安装 ] -------------------${PLAIN}"
echo -e "📦 正在安装 Java 21, SQLite3, curl, jq (无 Redis 依赖)..."

if command -v apt-get >/dev/null 2>&1; then
    apt-get update -y
    apt-get install -y openjdk-21-jre-headless sqlite3 curl jq tar gzip openssl || \
    apt-get install -y openjdk-17-jre-headless sqlite3 curl jq tar gzip openssl
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y java-21-openjdk-headless sqlite3 curl jq tar gzip openssl || \
    dnf install -y java-17-openjdk-headless sqlite3 curl jq tar gzip openssl
elif command -v yum >/dev/null 2>&1; then
    yum install -y java-21-openjdk-headless sqlite3 curl jq tar gzip openssl || \
    yum install -y java-17-openjdk-headless sqlite3 curl jq tar gzip openssl
fi

# 验证 Java 环境
if ! command -v java >/dev/null 2>&1; then
    echo -e "${RED}❌ Java 环境安装失败，请手动安装 OpenJDK 21 或 17 后重试。${PLAIN}"
    exit 1
fi
echo -e "${GREEN}✅ Java 运行环境就绪: $(java -version 2>&1 | head -n 1)${PLAIN}"

# 4. 生成 application.yml (保留已有 OCI 账号与高阶配置)
echo -e "\n${CYAN}------------------- [ 配置文件生成 ] -------------------${PLAIN}"

# 提取并保留已存在的 oci 账号配置段（防止重新拉取代码安装时抹除已添加的 OCI 账号）
OCI_SECTION=$'oci:\n  accounts: {}'
if [[ -f "${EXISTING_CONFIG}" ]] && grep -q "^oci:" "${EXISTING_CONFIG}"; then
    RAW_OCI=$(sed -n '/^oci:/,$p' "${EXISTING_CONFIG}")
    if echo "${RAW_OCI}" | grep -qE "user:|fingerprint:"; then
        OCI_SECTION="${RAW_OCI}"
        echo -e "  ${GREEN}✅ 已自动保留原有 OCI 云账号配置与本地密钥映射！${PLAIN}"
    else
        OCI_SECTION=$'oci:\n  accounts: {}'
    fi
    cp -f "${EXISTING_CONFIG}" "${EXISTING_CONFIG}.bak.$(date +%Y%m%d_%H%M%S)" 2>/dev/null || true
fi

cat << YAML > "${INSTALL_DIR}/application.yml"
server:
  port: ${PORT}
  address: 0.0.0.0
  servlet:
    encoding:
      charset: UTF-8
      force: true
      enabled: true
  tomcat:
    threads:
      max: 16
      min-spare: 1
    max-connections: 100
    accept-count: 20
    connection-timeout: 15000
    keep-alive-timeout: 30000

spring:
  main:
    banner-mode: "off"
  jmx:
    enabled: false
  mvc:
    async:
      request-timeout: 75000
  datasource:
    url: jdbc:sqlite:${INSTALL_DIR}/data/oci_personal.db?journal_mode=WAL&busy_timeout=60000&cache_size=-2000
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
      minimum-idle: 1
      connection-timeout: 30000
      max-lifetime: 0
      idle-timeout: 0
      pool-name: SQLitePool
      connection-init-sql: "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=60000; PRAGMA cache_size=-2000; PRAGMA synchronous=NORMAL;"
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: update
    show-sql: false
  threads:
    virtual:
      enabled: true
  task:
    scheduling:
      pool:
        size: 2

app:
  admin-username: "${ADMIN_USER}"
  admin-password: "${ADMIN_PWD}"
  bot-token: "${BOT_TOKEN}"
  admin-id: "${ADMIN_ID}"

${OCI_SECTION}
YAML

chmod 600 "${INSTALL_DIR}/application.yml"
chmod 700 "${INSTALL_DIR}/keys" "${INSTALL_DIR}/data"
echo -e "${GREEN}✅ 配置文件已安全生成至 ${INSTALL_DIR}/application.yml${PLAIN}"

# 5. 安装核心程序 JAR 包
# 如果当前执行目录下存在编译好的 target/*.jar，优先复制
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${SCRIPT_DIR}/target/oci-personal.jar" ]]; then
    cp -f "${SCRIPT_DIR}/target/oci-personal.jar" "${INSTALL_DIR}/app.jar"
elif [[ -f "${SCRIPT_DIR}/oci-personal.jar" ]]; then
    cp -f "${SCRIPT_DIR}/oci-personal.jar" "${INSTALL_DIR}/app.jar"
elif compgen -G "${SCRIPT_DIR}/target/*.jar" > /dev/null; then
    LATEST_JAR=$(ls -t "${SCRIPT_DIR}"/target/*.jar | head -n 1)
    cp -f "${LATEST_JAR}" "${INSTALL_DIR}/app.jar"
else
    echo -e "⬇️ 正在从 GitHub Releases 下载最新版本核心程序包 (app.jar)..."
    DOWNLOAD_URL="https://github.com/GeniusZeroTwo/ocibox/releases/download/latest/ocibox.jar"
    ALT_URL="https://github.com/GeniusZeroTwo/ocibox/releases/download/latest/oci-personal.jar"
    if ! curl -fL --retry 3 --connect-timeout 10 "${DOWNLOAD_URL}" -o "${INSTALL_DIR}/app.jar" 2>/dev/null && \
       ! curl -fL --retry 3 --connect-timeout 10 "${ALT_URL}" -o "${INSTALL_DIR}/app.jar"; then
        echo -e "${RED}❌ 下载核心程序包失败！请确认网络畅通或 GitHub Release 是否已生成。${PLAIN}"
        exit 1
    fi
fi

if [[ ! -s "${INSTALL_DIR}/app.jar" ]]; then
    echo -e "${RED}❌ 未检测到有效的核心程序包 (${INSTALL_DIR}/app.jar 不存在或为空)，无法启动！${PLAIN}"
    exit 1
fi

# 6. 配置 Systemd 服务 (服务名统一为 ocibox)
# 自动清理旧版 oci-personal 服务
if systemctl list-unit-files 2>/dev/null | grep -q "oci-personal.service" || [[ -f "/etc/systemd/system/oci-personal.service" ]]; then
    echo -e "🧹 正在平滑迁移旧版 oci-personal 服务至 ocibox 服务..."
    systemctl stop oci-personal >/dev/null 2>&1 || true
    systemctl disable oci-personal >/dev/null 2>&1 || true
    rm -f /etc/systemd/system/oci-personal.service
fi

cat << SERVICE > /etc/systemd/system/ocibox.service
[Unit]
Description=OCIBox Cloud Management Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${INSTALL_DIR}
ExecStart=$(command -v java) -Xms64m -Xmx384m -XX:+UseG1GC -jar ${INSTALL_DIR}/app.jar
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
SERVICE

systemctl daemon-reload
systemctl enable ocibox >/dev/null 2>&1
systemctl restart ocibox >/dev/null 2>&1

# 7. 安装全局管理命令 /usr/local/bin/ocibox
cat << 'CLI' > /usr/local/bin/ocibox
#!/bin/bash
# ================================================================
#  OCIBox - 个人专属云管理面板 系统快捷指令
# ================================================================

INSTALL_DIR="/root/ocibox"
[[ ! -d "${INSTALL_DIR}" && -d "/root/oci-personal" ]] && INSTALL_DIR="/root/oci-personal"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
PLAIN='\033[0m'

get_service_name() {
    if systemctl is-active --quiet oci-personal 2>/dev/null; then
        echo "oci-personal"
    elif systemctl is-active --quiet ocibox 2>/dev/null; then
        echo "ocibox"
    elif [[ -f "/etc/systemd/system/oci-personal.service" ]]; then
        echo "oci-personal"
    elif [[ -f "/etc/systemd/system/ocibox.service" ]]; then
        echo "ocibox"
    else
        echo "ocibox"
    fi
}

ocibox_status() {
    local svc=$(get_service_name)
    systemctl status "$svc"
}

ocibox_restart() {
    local svc=$(get_service_name)
    echo -e "${GREEN}正在重启 ${svc} 面板服务...${PLAIN}"
    systemctl restart "$svc"
    sleep 2
    systemctl status "$svc" --no-pager | head -n 12
}

ocibox_start() {
    local svc=$(get_service_name)
    echo -e "${GREEN}正在启动 ${svc} 面板服务...${PLAIN}"
    systemctl start "$svc"
    sleep 2
    systemctl status "$svc" --no-pager | head -n 12
}

ocibox_stop() {
    local svc=$(get_service_name)
    echo -e "${YELLOW}正在停止 ${svc} 面板服务...${PLAIN}"
    systemctl stop "$svc"
    echo -e "${GREEN}${svc} 服务已停止。${PLAIN}"
}

ocibox_logs() {
    local svc=$(get_service_name)
    echo -e "${CYAN}正在进入 ${svc} 实时日志输出 (按 Ctrl+C 退出)...${PLAIN}"
    journalctl -u "$svc" -f -n 50
}

ocibox_update() {
    echo -e "${CYAN}正在检查并更新 OCIBox 核心版本...${PLAIN}"
    if [[ -f "${INSTALL_DIR}/update.sh" ]]; then
        bash "${INSTALL_DIR}/update.sh"
    else
        bash <(curl -fsSL https://raw.githubusercontent.com/GeniusZeroTwo/ocibox/main/update.sh)
    fi
}

ocibox_info() {
    local conf="${INSTALL_DIR}/application.yml"
    [[ ! -f "$conf" && -f "/root/oci-personal/application.yml" ]] && conf="/root/oci-personal/application.yml"
    [[ ! -f "$conf" && -f "/root/ocibox/application.yml" ]] && conf="/root/ocibox/application.yml"
    if [[ ! -f "$conf" ]]; then
        echo -e "${RED}未找到配置文件: $conf${PLAIN}"
        return
    fi
    local base_dir=$(dirname "$conf")
    local user=$(grep -E '^[[:space:]]*admin-username:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*admin-username:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    local pwd=$(grep -E '^[[:space:]]*admin-password:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*admin-password:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    local bot=$(grep -E '^[[:space:]]*bot-token:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*bot-token:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    local admin_id=$(grep -E '^[[:space:]]*admin-id:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*admin-id:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    local port=$(grep -E '^[[:space:]]*port:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*port:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    [[ -z "$port" || "$port" =~ ^\$\{ ]] && port="5000"
    local ip=$(curl -s4 --max-time 2 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 2 https://ifconfig.me 2>/dev/null || curl -s4 --max-time 2 https://icanhazip.com 2>/dev/null || echo "127.0.0.1")
    local svc=$(get_service_name)
    
    local tg_status="未绑定"
    if [[ -n "$bot" && "$bot" != '""' && ! "$bot" =~ ^\$\{ && -n "$admin_id" && ! "$admin_id" =~ ^\$\{ ]]; then
        tg_status="已绑定 (Admin ID: ${admin_id}) [保留原配置]"
    fi

    echo -e "${GREEN}       🎉 OCI box 个人轻量管理面板 ${PLAIN}"
    echo -e "${GREEN}================================================================${PLAIN}"
    echo -e "🌐 ${CYAN}管理后台地址${PLAIN} : ${GREEN}http://${ip}:${port}${PLAIN}"
    if [[ -n "$user" && ! "$user" =~ ^\$\{ ]]; then
        echo -e "👤 ${CYAN}管理登录账号${PLAIN} : ${GREEN}${user}${PLAIN}"
    fi
    echo -e "🔑 ${CYAN}管理登录密码${PLAIN} : ${YELLOW}${pwd}${PLAIN}"
    echo -e "🤖 ${CYAN}Telegram 状态${PLAIN}: ${tg_status}"
    echo -e "📂 ${CYAN}数据持久目录${PLAIN} : ${base_dir}/data/"
    echo -e "🔑 ${CYAN}密钥保存目录${PLAIN} : ${base_dir}/keys/"
    echo -e "📄 ${CYAN}配置文件路径${PLAIN} : ${conf}"
    echo -e "----------------------------------------------------------------"
    echo -e "💡 ${CYAN}服务日常维护指令:${PLAIN}"
    echo -e "   - 查看服务状态 : systemctl status ${svc}"
    echo -e "   - 重启管理面板 : systemctl restart ${svc}"
    echo -e "   - 停止管理面板 : systemctl stop ${svc}"
    echo -e "   - 修改服务端口 : oci port [新端口]"
    echo -e "   - 查看实时日志 : journalctl -u ${svc} -f -n 50"
    echo -e "${GREEN}================================================================${PLAIN}"
}

open_port() {
    local target_port="$1"
    if [[ -z "${target_port}" ]]; then
        return 0
    fi

    echo -e "🛡️  正在自动放行系统防火墙端口: ${GREEN}${target_port}/tcp${PLAIN}..."

    # 1. 尝试 ufw (Ubuntu / Debian)
    if command -v ufw >/dev/null 2>&1; then
        if ufw status 2>/dev/null | grep -qi "active"; then
            ufw allow "${target_port}/tcp" >/dev/null 2>&1 || true
            ufw reload >/dev/null 2>&1 || true
        fi
    fi

    # 2. 尝试 firewalld (CentOS / RHEL / Fedora / AlmaLinux / RockyLinux / Oracle Linux)
    if command -v firewall-cmd >/dev/null 2>&1; then
        if systemctl is-active --quiet firewalld 2>/dev/null; then
            firewall-cmd --zone=public --add-port="${target_port}/tcp" --permanent >/dev/null 2>&1 || true
            firewall-cmd --reload >/dev/null 2>&1 || true
        fi
    fi

    # 3. 尝试 iptables (最通用底座，特别针对甲骨文云 OCI 自带 Linux 镜像)
    if command -v iptables >/dev/null 2>&1; then
        if ! iptables -C INPUT -p tcp --dport "${target_port}" -j ACCEPT 2>/dev/null; then
            iptables -I INPUT -p tcp --dport "${target_port}" -j ACCEPT 2>/dev/null || true
        fi

        # 规则持久化
        if command -v netfilter-persistent >/dev/null 2>&1; then
            netfilter-persistent save >/dev/null 2>&1 || true
        elif [[ -d "/etc/iptables" ]]; then
            iptables-save > /etc/iptables/rules.v4 2>/dev/null || true
        elif command -v service >/dev/null 2>&1 && service iptables status >/dev/null 2>&1; then
            service iptables save >/dev/null 2>&1 || true
        fi
    fi

    # 4. 尝试 ip6tables (IPv6 防火墙放行)
    if command -v ip6tables >/dev/null 2>&1; then
        if ! ip6tables -C INPUT -p tcp --dport "${target_port}" -j ACCEPT 2>/dev/null; then
            ip6tables -I INPUT -p tcp --dport "${target_port}" -j ACCEPT 2>/dev/null || true
        fi
        if [[ -d "/etc/iptables" ]]; then
            ip6tables-save > /etc/iptables/rules.v6 2>/dev/null || true
        fi
    fi

    echo -e "  ${GREEN}✅ 端口 ${target_port}/tcp 防火墙规则已成功放行！${PLAIN}"
}

ocibox_port() {
    local conf="${INSTALL_DIR}/application.yml"
    [[ ! -f "$conf" && -f "/root/oci-personal/application.yml" ]] && conf="/root/oci-personal/application.yml"
    [[ ! -f "$conf" && -f "/root/ocibox/application.yml" ]] && conf="/root/ocibox/application.yml"
    if [[ ! -f "$conf" ]]; then
        echo -e "${RED}未找到配置文件: $conf${PLAIN}"
        return 1
    fi

    local current_port=$(grep -E '^[[:space:]]*port:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*port:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    [[ -z "$current_port" || "$current_port" =~ ^\$\{ ]] && current_port="5000"

    local new_port="${1:-}"
    if [[ -z "$new_port" ]]; then
        echo -e "${CYAN}当前管理后台服务端口为: ${GREEN}${current_port}${PLAIN}"
        read -rp "👉 请输入新的服务端口 (1-65535): " new_port
    fi

    if [[ ! "$new_port" =~ ^[0-9]+$ ]] || [ "$new_port" -lt 1 ] || [ "$new_port" -gt 65535 ]; then
        echo -e "${RED}❌ 端口格式无效，请输入 1-65535 之间的有效端口号！${PLAIN}"
        return 1
    fi

    if [[ "$new_port" == "$current_port" ]]; then
        echo -e "${YELLOW}当前端口已为 ${new_port}，正在重新确保防火墙放行...${PLAIN}"
        open_port "$new_port"
        return 0
    fi

    echo -e "正在更新配置文件端口: ${current_port} -> ${new_port}..."
    sed -i.bak -E "s/^([[:space:]]*port:[[:space:]]*).*$/\1${new_port}/" "$conf" 2>/dev/null || sed -i -E "s/^([[:space:]]*port:[[:space:]]*).*$/\1${new_port}/" "$conf"
    rm -f "${conf}.bak" 2>/dev/null || true

    # 自动开放新端口
    open_port "$new_port"

    local svc=$(get_service_name)
    echo -e "${GREEN}正在重启 ${svc} 面板服务以使新端口生效...${PLAIN}"
    systemctl restart "$svc"
    sleep 2

    local ip=$(curl -s4 --max-time 2 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 2 https://ifconfig.me 2>/dev/null || curl -s4 --max-time 2 https://icanhazip.com 2>/dev/null || echo "127.0.0.1")
    echo -e "\n${GREEN}🎉 端口已成功修改为 ${new_port}，防火墙已自动放行！${PLAIN}"
    echo -e "🌐 ${CYAN}管理后台新地址${PLAIN} : ${GREEN}http://${ip}:${new_port}${PLAIN}\n"
}

case "${1:-}" in
    status) ocibox_status ;;
    restart) ocibox_restart ;;
    start) ocibox_start ;;
    stop) ocibox_stop ;;
    log|logs) ocibox_logs ;;
    update) ocibox_update ;;
    port) ocibox_port "${2:-}" ;;
    info) ocibox_info ;;
    help|--help|-h)
        echo "用法: oci [status|restart|start|stop|logs|update|port|info]"
        ;;
    *)
        ocibox_info
        if [[ -t 0 ]]; then
            echo -e "\n${CYAN}💡 快捷操作: [1] 重启面板  [2] 查看状态  [3] 实时日志  [4] 在线更新  [5] 修改端口  [0/回车] 退出${PLAIN}"
            read -rp "👉 请输入选项编号 [默认回车退出]: " opt
            case "$opt" in
                1) ocibox_restart ;;
                2) ocibox_status ;;
                3) ocibox_logs ;;
                4) ocibox_update ;;
                5) ocibox_port ;;
                *) exit 0 ;;
            esac
        fi
        ;;
esac
CLI
chmod +x /usr/local/bin/ocibox
ln -sf /usr/local/bin/ocibox /usr/local/bin/oci 2>/dev/null || true
ln -sf /usr/local/bin/ocibox /usr/bin/oci 2>/dev/null || true
ln -sf /usr/local/bin/ocibox /usr/bin/ocibox 2>/dev/null || true
ln -sf /usr/local/bin/ocibox /usr/local/bin/oci-personal 2>/dev/null || true

# 8. 获取公网 IP 与展示最终面板地址
SERVER_IP=$(curl -s4 --max-time 3 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 3 https://ifconfig.me 2>/dev/null || curl -s4 --max-time 3 https://icanhazip.com 2>/dev/null || echo "127.0.0.1")

echo -e "\n${GREEN}================================================================${PLAIN}"
echo -e "${GREEN}       🎉 OCI box 个人轻量管理面板 部署成功！${PLAIN}"
echo -e "${GREEN}================================================================${PLAIN}"
echo -e "🌐 ${CYAN}管理后台地址${PLAIN} : ${GREEN}http://${SERVER_IP}:${PORT}${PLAIN}"
echo -e "👤 ${CYAN}管理登录账号${PLAIN} : ${GREEN}${ADMIN_USER}${PLAIN}"
echo -e "🔑 ${CYAN}管理登录密码${PLAIN} : ${YELLOW}${ADMIN_PWD}${PLAIN}"
echo -e "🤖 ${CYAN}Telegram 状态${PLAIN}: ${TG_STATUS}"
echo -e "📂 ${CYAN}数据持久目录${PLAIN} : ${INSTALL_DIR}/data/"
echo -e "🔑 ${CYAN}密钥保存目录${PLAIN} : ${INSTALL_DIR}/keys/"
echo -e "📄 ${CYAN}配置文件路径${PLAIN} : ${INSTALL_DIR}/application.yml"
echo -e "----------------------------------------------------------------"
echo -e "💡 ${CYAN}服务日常维护指令 (终端直接输入 oci 即可呼出状态卡片与操作菜单):${PLAIN}"
echo -e "   - 快捷运维指令 : ${GREEN}oci${PLAIN} (或 ocibox)"
echo -e "   - 查看服务状态 : ${YELLOW}oci status${PLAIN} (或 systemctl status ocibox)"
echo -e "   - 重启管理面板 : ${YELLOW}oci restart${PLAIN} (或 systemctl restart ocibox)"
echo -e "   - 停止管理面板 : ${YELLOW}oci stop${PLAIN} (或 systemctl stop ocibox)"
echo -e "   - 修改服务端口 : ${YELLOW}oci port${PLAIN} (自动放行防火墙并重启)"
echo -e "   - 查看实时日志 : ${YELLOW}oci logs${PLAIN} (或 journalctl -u ocibox -f -n 50)"
echo -e "   - 在线版本更新 : ${YELLOW}oci update${PLAIN}"
echo -e "${GREEN}================================================================${PLAIN}\n"
