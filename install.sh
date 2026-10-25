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
  ____   ____ ___   ____                                   _ 
 / __ \ / ___|_ _| |  _ \ ___ _ __ ___  ___  _ __   __ _  | |
| |  | | |    | |  | |_) / _ \ '__/ __|/ _ \| '_ \ / _` | | |
| |__| | |___ | |  |  __/  __/ |  \__ \ (_) | | | | (_| | | |
 \____/ \____|___| |_|   \___|_|  |___/\___/|_| |_|\__,_| |_|
BANNER
echo -e "${PLAIN}"
echo -e "${GREEN}>>> 正在启动 OCI Personal 个人专属管理面板一键部署向导...${PLAIN}\n"

# 1. 检查 root 权限
if [[ $EUID -ne 0 ]]; then
    echo -e "${RED}❌ 错误: 本安装脚本必须以 root 用户权限运行！请使用 sudo -i 或 su root 切换后重试。${PLAIN}"
    exit 1
fi

INSTALL_DIR="/root/oci-personal"
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

EXISTING_CONFIG="${INSTALL_DIR}/application.yml"
EXISTING_PORT=""
EXISTING_ADMIN_PWD=""
EXISTING_BOT_TOKEN=""
EXISTING_ADMIN_ID=""
EXISTING_GH_TOKEN=""
EXISTING_GH_REPO=""
EXISTING_BACKUP_PWD=""

if [[ -f "${EXISTING_CONFIG}" ]]; then
    EXISTING_PORT=$(get_yaml_val "port" "${EXISTING_CONFIG}")
    EXISTING_ADMIN_PWD=$(get_yaml_val "admin-password" "${EXISTING_CONFIG}")
    EXISTING_BOT_TOKEN=$(get_yaml_val "bot-token" "${EXISTING_CONFIG}")
    EXISTING_ADMIN_ID=$(get_yaml_val "admin-id" "${EXISTING_CONFIG}")
    EXISTING_GH_TOKEN=$(get_yaml_val "github-token" "${EXISTING_CONFIG}")
    EXISTING_GH_REPO=$(get_yaml_val "github-repo" "${EXISTING_CONFIG}")
    EXISTING_BACKUP_PWD=$(get_yaml_val "backup-password" "${EXISTING_CONFIG}")
    echo -e "${GREEN}🔍 检测到本地已存在历史配置 (${EXISTING_CONFIG})，支持一键无损沿用！${PLAIN}\n"
fi

# 端口配置
if is_configured "${EXISTING_PORT}"; then
    read -rp "👉 请输入管理后台服务端口 [检测到原配置: ${EXISTING_PORT}, 回车直接沿用]: " INPUT_PORT
    PORT="${INPUT_PORT:-${EXISTING_PORT}}"
else
    read -rp "👉 请输入管理后台服务端口 [默认: 5000]: " INPUT_PORT
    PORT="${INPUT_PORT:-5000}"
fi

# 管理员密码配置
if is_configured "${EXISTING_ADMIN_PWD}"; then
    read -rp "👉 请设置管理员登录密码 [检测到已有密码, 回车保留原密码不修改]: " INPUT_PWD
    ADMIN_PWD="${INPUT_PWD:-${EXISTING_ADMIN_PWD}}"
else
    DEFAULT_PWD=$(openssl rand -base64 12 | tr -dc 'a-zA-Z0-9' | head -c 16 2>/dev/null || echo "OciPersonal998")
    read -rp "👉 请设置管理员登录密码 [回车自动生成: ${DEFAULT_PWD}]: " INPUT_PWD
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
    OCI_SECTION=$(sed -n '/^oci:/,$p' "${EXISTING_CONFIG}")
    cp -f "${EXISTING_CONFIG}" "${EXISTING_CONFIG}.bak.$(date +%Y%m%d_%H%M%S)" 2>/dev/null || true
    echo -e "  ${GREEN}✅ 已自动保留原有 OCI 云账号配置与本地密钥映射！${PLAIN}"
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
  admin-password: "${ADMIN_PWD}"
  bot-token: "${BOT_TOKEN}"
  admin-id: "${ADMIN_ID}"
  github-token: "${EXISTING_GH_TOKEN:-}"
  github-repo: "${EXISTING_GH_REPO:-}"
  backup-password: "${EXISTING_BACKUP_PWD:-}"

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
    DOWNLOAD_URL="https://github.com/GeniusZeroTwo/ocibox/releases/download/latest/oci-personal.jar"
    if ! curl -fL --retry 3 --connect-timeout 10 "${DOWNLOAD_URL}" -o "${INSTALL_DIR}/app.jar"; then
        echo -e "${RED}❌ 下载核心程序包失败！请确认网络畅通或 GitHub Release 是否已生成。${PLAIN}"
        exit 1
    fi
fi

if [[ ! -s "${INSTALL_DIR}/app.jar" ]]; then
    echo -e "${RED}❌ 未检测到有效的核心程序包 (${INSTALL_DIR}/app.jar 不存在或为空)，无法启动！${PLAIN}"
    exit 1
fi

# 6. 配置 Systemd 服务
cat << SERVICE > /etc/systemd/system/oci-personal.service
[Unit]
Description=OCI Personal Cloud Management Service
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
systemctl enable oci-personal >/dev/null 2>&1
systemctl restart oci-personal >/dev/null 2>&1

# 7. 获取公网 IP 与展示最终面板地址
SERVER_IP=$(curl -s4 --max-time 3 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 3 https://ifconfig.me 2>/dev/null || curl -s4 --max-time 3 https://icanhazip.com 2>/dev/null || echo "127.0.0.1")

echo -e "\n${GREEN}================================================================${PLAIN}"
echo -e "${GREEN}       🎉 OCI Personal 个人轻量管理面板 部署成功！${PLAIN}"
echo -e "${GREEN}================================================================${PLAIN}"
echo -e "🌐 ${CYAN}管理后台地址${PLAIN} : ${GREEN}http://${SERVER_IP}:${PORT}${PLAIN}"
echo -e "🔑 ${CYAN}管理登录密码${PLAIN} : ${YELLOW}${ADMIN_PWD}${PLAIN}"
echo -e "🤖 ${CYAN}Telegram 状态${PLAIN}: ${TG_STATUS}"
echo -e "📂 ${CYAN}数据持久目录${PLAIN} : ${INSTALL_DIR}/data/"
echo -e "🔑 ${CYAN}密钥保存目录${PLAIN} : ${INSTALL_DIR}/keys/"
echo -e "📄 ${CYAN}配置文件路径${PLAIN} : ${INSTALL_DIR}/application.yml"
echo -e "----------------------------------------------------------------"
echo -e "💡 ${CYAN}服务日常维护指令:${PLAIN}"
echo -e "   - 查看服务状态 : ${YELLOW}systemctl status oci-personal${PLAIN}"
echo -e "   - 重启管理面板 : ${YELLOW}systemctl restart oci-personal${PLAIN}"
echo -e "   - 停止管理面板 : ${YELLOW}systemctl stop oci-personal${PLAIN}"
echo -e "   - 查看实时日志 : ${YELLOW}journalctl -u oci-personal -f -n 50${PLAIN}"
echo -e "${GREEN}================================================================${PLAIN}\n"
