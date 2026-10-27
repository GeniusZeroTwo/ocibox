#!/bin/bash
# ================================================================
#  OCIBox - 甲骨文云专属管理面板 一键部署脚本
#  专为个人自用打造，极简纯净 SQLite 架构，轻量高效
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
EXISTING_DOMAIN=""

if [[ -f "${INSTALL_DIR}/domain.conf" ]]; then
    EXISTING_DOMAIN=$(grep -E '^[[:space:]]*DOMAIN=' "${INSTALL_DIR}/domain.conf" 2>/dev/null | head -n 1 | cut -d '=' -f 2 | tr -d ' "\r')
elif [[ -f "/root/oci-personal/domain.conf" ]]; then
    EXISTING_DOMAIN=$(grep -E '^[[:space:]]*DOMAIN=' "/root/oci-personal/domain.conf" 2>/dev/null | head -n 1 | cut -d '=' -f 2 | tr -d ' "\r')
fi

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

# 域名与 SSL 证书配置
echo -e "\n${CYAN}------------------- [ 域名与 SSL 证书配置 ] -------------------${PLAIN}"
BIND_DOMAIN=false
DOMAIN=""
DOMAIN_STATUS="未绑定 (直接 IP 访问)"

# 预先探测本机公网 IP 辅助校验
SERVER_IP=$(curl -s4 --max-time 3 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 3 https://ifconfig.me 2>/dev/null || curl -s4 --max-time 3 https://icanhazip.com 2>/dev/null || echo "127.0.0.1")

if [[ -n "${EXISTING_DOMAIN}" ]]; then
    echo -e "${GREEN}🔔 【检测到您此前已成功绑定过域名】: ${YELLOW}${EXISTING_DOMAIN}${PLAIN}"
    read -rp "👉 是否直接沿用此域名并保持 SSL 反向代理？(Y/n, 默认 Y 沿用): " KEEP_DOMAIN
    KEEP_DOMAIN="${KEEP_DOMAIN:-Y}"
    if [[ "${KEEP_DOMAIN}" =~ ^[Yy]$ ]]; then
        BIND_DOMAIN=true
        DOMAIN="${EXISTING_DOMAIN}"
        DOMAIN_STATUS="已绑定 (https://${DOMAIN})"
        echo -e "  ${GREEN}✅ 已自动沿用此前绑定的域名: ${DOMAIN}${PLAIN}"
    else
        read -rp "👉 是否需要绑定新的域名？(y/N, 默认 N 解除域名绑定): " RE_BIND_DOMAIN
        if [[ "${RE_BIND_DOMAIN}" =~ ^[Yy]$ ]]; then
            while true; do
                read -rp "  请输入新的解析域名 (例如: oci.example.com): " INPUT_DOMAIN
                INPUT_DOMAIN=$(echo "${INPUT_DOMAIN}" | sed -E 's|^https?://||' | sed -E 's|/.*$||' | tr -d '[:space:]')
                if [[ -n "${INPUT_DOMAIN}" && "${INPUT_DOMAIN}" =~ ^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$ ]]; then
                    DOMAIN="${INPUT_DOMAIN}"
                    BIND_DOMAIN=true
                    DOMAIN_STATUS="已绑定 (https://${DOMAIN})"
                    break
                else
                    echo -e "  ${RED}❌ 域名格式无效，请重新输入（例如: oci.example.com，请勿带 http/https）！${PLAIN}"
                fi
            done
        else
            BIND_DOMAIN=false
            DOMAIN=""
            DOMAIN_STATUS="未绑定"
            echo -e "  ${YELLOW}ℹ️ 已解除域名绑定，面板将恢复通过 IP+端口 直接访问。${PLAIN}"
        fi
    fi
else
    echo -e "说明: 绑定域名可自动配置 Nginx 反向代理与免费 Let's Encrypt SSL 证书 (HTTPS 安全加密访问)"
    read -rp "👉 是否需要为管理后台绑定自定义域名并配置 SSL 证书？(y/N, 默认 N 继续直接访问): " ASK_BIND_DOMAIN
    if [[ "${ASK_BIND_DOMAIN}" =~ ^[Yy]$ ]]; then
        while true; do
            read -rp "  👉 请输入已解析到本机 IP (${SERVER_IP}) 的域名 (例如: oci.example.com): " INPUT_DOMAIN
            INPUT_DOMAIN=$(echo "${INPUT_DOMAIN}" | sed -E 's|^https?://||' | sed -E 's|/.*$||' | tr -d '[:space:]')
            if [[ -n "${INPUT_DOMAIN}" && "${INPUT_DOMAIN}" =~ ^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$ ]]; then
                DOMAIN="${INPUT_DOMAIN}"
                BIND_DOMAIN=true
                DOMAIN_STATUS="已绑定 (https://${DOMAIN})"
                break
            else
                echo -e "  ${RED}❌ 域名格式无效，请重新输入（例如: oci.example.com，请勿带 http/https）！${PLAIN}"
            fi
        done

        # DNS 解析检查与提示
        echo -e "  🔍 正在检测 ${CYAN}${DOMAIN}${PLAIN} 的 DNS 解析状态..."
        RESOLVED_IP=$(ping -c 1 -W 2 "${DOMAIN}" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -n 1 || getent ahosts "${DOMAIN}" 2>/dev/null | awk '{print $1}' | head -n 1 || true)
        if [[ -n "${RESOLVED_IP}" ]]; then
            if [[ "${RESOLVED_IP}" == "${SERVER_IP}" ]]; then
                echo -e "  ${GREEN}✅ DNS 解析校验通过！${DOMAIN} 已成功指向当前服务器 (${SERVER_IP})。${PLAIN}"
            else
                echo -e "  ${YELLOW}⚠️ 提示: ${DOMAIN} 当前解析为 [${RESOLVED_IP}]，与本机公网 IP [${SERVER_IP}] 不一致。${PLAIN}"
                echo -e "  ${YELLOW}   (若为 CDN/Cloudflare 代理或新解析生效延迟，若官方证书签发受阻，将自动启用自签名证书保底)${PLAIN}"
            fi
        else
            echo -e "  ${YELLOW}⚠️ 提示: 暂未检测到 ${DOMAIN} 的 DNS 解析记录，可能需要几分钟在全球 DNS 生效。${PLAIN}"
            echo -e "  ${YELLOW}   (安装过程中若官方证书申请超时，将自动生成高强度自签名证书保底，确保顺利安装)${PLAIN}"
        fi
    else
        BIND_DOMAIN=false
        DOMAIN=""
        DOMAIN_STATUS="未绑定"
        echo -e "  ${CYAN}ℹ️ 您选择了不绑定域名，将跳过证书申请，通过 http://${SERVER_IP}:${PORT} 直接访问。${PLAIN}"
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

# 若用户选择绑定域名，安装 Nginx 与 Certbot 并放通 80 / 443 端口
if [[ "${BIND_DOMAIN}" == "true" ]]; then
    echo -e "📦 正在安装 Nginx, Certbot 与 SSL 证书管理工具..."
    if command -v apt-get >/dev/null 2>&1; then
        apt-get install -y nginx certbot python3-certbot-nginx || apt-get install -y nginx certbot || true
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y nginx certbot python3-certbot-nginx || dnf install -y epel-release nginx certbot || true
    elif command -v yum >/dev/null 2>&1; then
        yum install -y epel-release nginx certbot python3-certbot-nginx || yum install -y nginx certbot || true
    fi
    open_port 80
    open_port 443
fi

# 验证 Java 环境
if ! command -v java >/dev/null 2>&1; then
    echo -e "${RED}❌ Java 环境安装失败，请手动安装 OpenJDK 21 或 17 后重试。${PLAIN}"
    exit 1
fi
echo -e "${GREEN}✅ Java 运行环境就绪: $(java -version 2>&1 | head -n 1)${PLAIN}"

# 4. 生成 application.yml (保留已有 OCI 账号与高阶配置)
echo -e "\n${CYAN}------------------- [ 配置文件生成 ] -------------------${PLAIN}"

# 根据是否绑定域名确定监听地址与反代信任配置
if [[ "${BIND_DOMAIN}" == "true" ]]; then
    SERVER_ADDRESS="127.0.0.1"
    TRUST_FORWARDED="true"
else
    SERVER_ADDRESS="0.0.0.0"
    TRUST_FORWARDED="false"
fi

# 保存域名绑定状态至 domain.conf 供全局快捷指令及后续维护
cat << DOMAIN_CONF > "${INSTALL_DIR}/domain.conf"
BIND_DOMAIN=${BIND_DOMAIN}
DOMAIN="${DOMAIN}"
DOMAIN_CONF
chmod 600 "${INSTALL_DIR}/domain.conf" 2>/dev/null || true

# 提取并保留已存在的 oci 账号配置段（防止重新拉取代码安装时抹除已添加的 OCI 账号）
OCI_SECTION=$'oci:\n  accounts: {}'
if [[ -f "${EXISTING_CONFIG}" ]] && grep -q "^oci:" "${EXISTING_CONFIG}"; then
    RAW_OCI=$(sed -n '/^oci:/,$p' "${EXISTING_CONFIG}")
    if echo "${RAW_OCI}" | grep -qE "user:|fingerprint:"; then
        # 确保已有账号节点时清除 accounts: 后缀的 {}，防止 YAML 语法非法
        OCI_SECTION=$(echo "${RAW_OCI}" | sed -E 's/accounts:[[:space:]]*\{\}/accounts:/')
        echo -e "  ${GREEN}✅ 已自动保留原有 OCI 云账号配置与本地密钥映射！${PLAIN}"
    else
        OCI_SECTION=$'oci:\n  accounts: {}'
    fi
    cp -f "${EXISTING_CONFIG}" "${EXISTING_CONFIG}.bak.$(date +%Y%m%d_%H%M%S)" 2>/dev/null || true
fi

cat << YAML > "${INSTALL_DIR}/application.yml"
server:
  port: ${PORT}
  address: ${SERVER_ADDRESS}
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
  trust-forwarded-for: ${TRUST_FORWARDED}

${OCI_SECTION}
YAML

chmod 600 "${INSTALL_DIR}/application.yml"
chmod 700 "${INSTALL_DIR}/keys" "${INSTALL_DIR}/data"
echo -e "${GREEN}✅ 配置文件已安全生成至 ${INSTALL_DIR}/application.yml${PLAIN}"

# 5. 安装核心程序 JAR 包
# 如果当前执行目录下存在编译好的 target/*.jar，优先复制
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${SCRIPT_DIR}/target/ocibox.jar" ]]; then
    cp -f "${SCRIPT_DIR}/target/ocibox.jar" "${INSTALL_DIR}/app.jar"
elif [[ -f "${SCRIPT_DIR}/target/oci-personal.jar" ]]; then
    cp -f "${SCRIPT_DIR}/target/oci-personal.jar" "${INSTALL_DIR}/app.jar"
elif [[ -f "${SCRIPT_DIR}/ocibox.jar" ]]; then
    cp -f "${SCRIPT_DIR}/ocibox.jar" "${INSTALL_DIR}/app.jar"
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

# 7. 配置 Nginx 反向代理与申请 SSL 证书 (若选择了绑定域名)
if [[ "${BIND_DOMAIN}" == "true" && -n "${DOMAIN}" ]]; then
    echo -e "\n${CYAN}------------------- [ Nginx 反向代理与 SSL 证书配置 ] -------------------${PLAIN}"
    mkdir -p "${INSTALL_DIR}/ssl" /var/www/html /etc/nginx/conf.d 2>/dev/null || true

    # 1. 自动生成自签名临时保底证书，确保即使 DNS 延迟或 Let's Encrypt 频限，Nginx 也能立即平稳拉起 HTTPS
    SELF_KEY="${INSTALL_DIR}/ssl/${DOMAIN}.key"
    SELF_CRT="${INSTALL_DIR}/ssl/${DOMAIN}.crt"
    if [[ ! -f "${SELF_KEY}" || ! -f "${SELF_CRT}" ]]; then
        echo -e "🛡️ 正在生成自签名 SSL 临时安全证书以做兜底保障..."
        openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
            -keyout "${SELF_KEY}" \
            -out "${SELF_CRT}" \
            -subj "/C=CN/ST=Cloud/L=OCIBox/O=OCIBox/CN=${DOMAIN}" >/dev/null 2>&1 || true
    fi

    # 2. 检查系统是否已有现成的 Let's Encrypt 证书
    CERT_FILE="${SELF_CRT}"
    KEY_FILE="${SELF_KEY}"
    if [[ -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" && -f "/etc/letsencrypt/live/${DOMAIN}/privkey.pem" ]]; then
        CERT_FILE="/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"
        KEY_FILE="/etc/letsencrypt/live/${DOMAIN}/privkey.pem"
        echo -e "  ${GREEN}🔍 检测到已有 Let's Encrypt 官方证书，将优先挂载！${PLAIN}"
    fi

    # 3. 写入 Nginx 站点配置
    cat << NGINX_CONF > /etc/nginx/conf.d/ocibox.conf
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};

    location /.well-known/acme-challenge/ {
        root /var/www/html;
        try_files \$uri =404;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name ${DOMAIN};

    ssl_certificate ${CERT_FILE};
    ssl_certificate_key ${KEY_FILE};

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    client_max_body_size 50M;

    location / {
        proxy_pass http://127.0.0.1:${PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        # WebSocket 代理支持
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
NGINX_CONF

    # 兼容 Ubuntu/Debian 的 sites-enabled 软链结构
    if [[ -d "/etc/nginx/sites-enabled" ]]; then
        ln -sf /etc/nginx/conf.d/ocibox.conf /etc/nginx/sites-enabled/ocibox.conf 2>/dev/null || true
    fi

    # 预先启动或重启 Nginx
    systemctl restart nginx >/dev/null 2>&1 || systemctl start nginx >/dev/null 2>&1 || true

    # 4. 若尚未获取官方证书，且系统安装了 certbot，则发起自动化 Let's Encrypt 申请
    if [[ "${CERT_FILE}" == "${SELF_CRT}" ]] && command -v certbot >/dev/null 2>&1; then
        echo -e "🔒 正在连接 Let's Encrypt CA 为 ${CYAN}${DOMAIN}${PLAIN} 申请免费正式 SSL 证书..."
        CERT_APPLIED=false
        # 优先尝试 webroot
        if certbot certonly --webroot -w /var/www/html -d "${DOMAIN}" --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring >/dev/null 2>&1; then
            CERT_APPLIED=true
        # 备选尝试 standalone
        elif certbot certonly --standalone -d "${DOMAIN}" --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring --pre-hook "systemctl stop nginx" --post-hook "systemctl start nginx" >/dev/null 2>&1; then
            CERT_APPLIED=true
        fi

        if [[ "${CERT_APPLIED}" == "true" && -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" ]]; then
            sed -i.bak -E "s|ssl_certificate .*;|ssl_certificate /etc/letsencrypt/live/${DOMAIN}/fullchain.pem;|g" /etc/nginx/conf.d/ocibox.conf
            sed -i.bak -E "s|ssl_certificate_key .*;|ssl_certificate_key /etc/letsencrypt/live/${DOMAIN}/privkey.pem;|g" /etc/nginx/conf.d/ocibox.conf
            rm -f /etc/nginx/conf.d/ocibox.conf.bak 2>/dev/null || true
            nginx -s reload >/dev/null 2>&1 || systemctl reload nginx >/dev/null 2>&1 || systemctl restart nginx >/dev/null 2>&1 || true
            echo -e "  ${GREEN}✅ Let's Encrypt 官方 SSL 证书已成功签发并自动激活！${PLAIN}"
        else
            echo -e "  ${YELLOW}⚠️ Let's Encrypt 官方证书暂未签发成功（通常由于域名 DNS 解析在全球尚未完全同步或 80 端口验证受阻）。${PLAIN}"
            echo -e "  ${GREEN}🛡️ 已自动为您启用自签名 SSL 证书兜底，管理面板 HTTPS 现已可正常加密访问！${PLAIN}"
            echo -e "  ${CYAN}💡 待您的 DNS 解析全球生效后，随时可在终端输入 'oci cert' 重新一键申请官方证书。${PLAIN}"
        fi
    fi

    systemctl enable nginx >/dev/null 2>&1 || true
    echo -e "  ${GREEN}✅ Nginx 反向代理配置与 HTTPS 服务已就绪！${PLAIN}"
fi

# 8. 安装全局管理命令 /usr/local/bin/ocibox
cat << 'CLI' > /usr/local/bin/ocibox
#!/bin/bash
# ================================================================
#  OCIBox - 个人专属云管理面板 系统快捷指令
# ================================================================

# 自动处理普通用户/无 root 权限问题 (如 instabox、ubuntu、debian 默认免密 sudo 用户)
if [ "$(id -u)" -ne 0 ]; then
    exec sudo bash "$0" "$@"
fi

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

    local domain_conf="${INSTALL_DIR}/domain.conf"
    local domain=""
    local bind_domain="false"
    if [[ -f "$domain_conf" ]]; then
        domain=$(grep -E '^[[:space:]]*DOMAIN=' "$domain_conf" 2>/dev/null | head -n 1 | cut -d '=' -f 2 | tr -d ' "\r')
        bind_domain=$(grep -E '^[[:space:]]*BIND_DOMAIN=' "$domain_conf" 2>/dev/null | head -n 1 | cut -d '=' -f 2 | tr -d ' "\r')
    fi
    
    local tg_status="未绑定"
    if [[ -n "$bot" && "$bot" != '""' && ! "$bot" =~ ^\$\{ && -n "$admin_id" && ! "$admin_id" =~ ^\$\{ ]]; then
        tg_status="已绑定 (Admin ID: ${admin_id}) [保留原配置]"
    fi

    echo -e "${GREEN}       🎉 OCIBox 个人专属管理面板 ${PLAIN}"
    echo -e "${GREEN}================================================================${PLAIN}"
    if [[ "$bind_domain" == "true" && -n "$domain" ]]; then
        echo -e "🌐 ${CYAN}管理后台地址${PLAIN} : ${GREEN}https://${domain}${PLAIN}"
        echo -e "🔗 ${CYAN}本地直连备用${PLAIN} : ${CYAN}http://${ip}:${port}${PLAIN}"
    else
        echo -e "🌐 ${CYAN}管理后台地址${PLAIN} : ${GREEN}http://${ip}:${port}${PLAIN}"
    fi
    if [[ -n "$user" && ! "$user" =~ ^\$\{ ]]; then
        echo -e "👤 ${CYAN}管理登录账号${PLAIN} : ${GREEN}${user}${PLAIN}"
    fi
    echo -e "🔑 ${CYAN}管理登录密码${PLAIN} : ${YELLOW}${pwd}${PLAIN}"
    echo -e "🤖 ${CYAN}Telegram 状态${PLAIN}: ${tg_status}"
    if [[ "$bind_domain" == "true" && -n "$domain" ]]; then
        echo -e "🔒 ${CYAN}域名与证书${PLAIN}  : ${GREEN}已绑定 (${domain})${PLAIN}"
    else
        echo -e "🔒 ${CYAN}域名与证书${PLAIN}  : 未绑定 (随时可运行 'oci cert' 绑定)"
    fi
    echo -e "📂 ${CYAN}数据持久目录${PLAIN} : ${base_dir}/data/"
    echo -e "🔑 ${CYAN}密钥保存目录${PLAIN} : ${base_dir}/keys/"
    echo -e "📄 ${CYAN}配置文件路径${PLAIN} : ${conf}"
    echo -e "----------------------------------------------------------------"
    echo -e "💡 ${CYAN}服务日常维护指令:${PLAIN}"
    echo -e "   - 查看服务状态 : systemctl status ${svc}"
    echo -e "   - 重启管理面板 : systemctl restart ${svc}"
    echo -e "   - 停止管理面板 : systemctl stop ${svc}"
    echo -e "   - 修改服务端口 : oci port [新端口]"
    echo -e "   - 域名证书管理 : oci cert [申请/更换/解除绑定]"
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

    # 如果配置了 Nginx 反向代理，同步更新 Nginx 配置
    if [[ -f "/etc/nginx/conf.d/ocibox.conf" ]]; then
        sed -i.bak -E "s|proxy_pass http://127.0.0.1:[0-9]+;|proxy_pass http://127.0.0.1:${new_port};|g" /etc/nginx/conf.d/ocibox.conf
        rm -f /etc/nginx/conf.d/ocibox.conf.bak 2>/dev/null || true
        nginx -s reload 2>/dev/null || systemctl reload nginx 2>/dev/null || true
    fi

    local ip=$(curl -s4 --max-time 2 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 2 https://ifconfig.me 2>/dev/null || curl -s4 --max-time 2 https://icanhazip.com 2>/dev/null || echo "127.0.0.1")
    echo -e "\n${GREEN}🎉 端口已成功修改为 ${new_port}，防火墙已自动放行！${PLAIN}"
    echo -e "🌐 ${CYAN}管理后台新地址${PLAIN} : ${GREEN}http://${ip}:${new_port}${PLAIN}\n"
}

ocibox_cert() {
    local domain_conf="${INSTALL_DIR}/domain.conf"
    local conf="${INSTALL_DIR}/application.yml"
    [[ ! -f "$conf" && -f "/root/oci-personal/application.yml" ]] && conf="/root/oci-personal/application.yml"
    [[ ! -f "$conf" && -f "/root/ocibox/application.yml" ]] && conf="/root/ocibox/application.yml"
    local current_domain=""
    if [[ -f "$domain_conf" ]]; then
        current_domain=$(grep -E '^[[:space:]]*DOMAIN=' "$domain_conf" 2>/dev/null | head -n 1 | cut -d '=' -f 2 | tr -d ' "\r')
    fi
    local port=$(grep -E '^[[:space:]]*port:' "$conf" 2>/dev/null | head -n 1 | sed -E 's/^[[:space:]]*port:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    [[ -z "$port" || "$port" =~ ^\$\{ ]] && port="5000"
    local server_ip=$(curl -s4 --max-time 2 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 2 https://ifconfig.me 2>/dev/null || curl -s4 --max-time 2 https://icanhazip.com 2>/dev/null || echo "127.0.0.1")

    local target_domain="${1:-}"
    if [[ -z "$target_domain" ]]; then
        if [[ -n "$current_domain" ]]; then
            echo -e "${CYAN}当前绑定的域名为: ${GREEN}${current_domain}${PLAIN}"
            read -rp "👉 请选择: [1] 重新为 ${current_domain} 申请证书  [2] 绑定新域名  [3] 解除域名绑定  [0] 取消: " choice
            case "$choice" in
                1) target_domain="${current_domain}" ;;
                2)
                    read -rp "👉 请输入新域名 (例如: oci.example.com): " target_domain
                    ;;
                3)
                    echo -e "${YELLOW}正在解除域名绑定并恢复 IP:端口 直连模式...${PLAIN}"
                    rm -f /etc/nginx/conf.d/ocibox.conf /etc/nginx/sites-enabled/ocibox.conf 2>/dev/null || true
                    nginx -s reload 2>/dev/null || systemctl reload nginx 2>/dev/null || true
                    sed -i.bak -E 's/^[[:space:]]*address:[[:space:]].*$/  address: 0.0.0.0/' "$conf" 2>/dev/null || true
                    sed -i.bak -E 's/^[[:space:]]*trust-forwarded-for:[[:space:]].*$/  trust-forwarded-for: false/' "$conf" 2>/dev/null || true
                    rm -f "${conf}.bak" 2>/dev/null || true
                    echo 'BIND_DOMAIN=false' > "$domain_conf"
                    echo 'DOMAIN=""' >> "$domain_conf"
                    local svc=$(get_service_name)
                    systemctl restart "$svc" 2>/dev/null || true
                    echo -e "${GREEN}✅ 已成功解除域名绑定！管理后台已恢复直接访问: http://${server_ip}:${port}${PLAIN}"
                    return 0
                    ;;
                *) return 0 ;;
            esac
        else
            echo -e "${CYAN}当前管理面板尚未绑定域名。${PLAIN}"
            read -rp "👉 请输入要绑定的域名 (例如: oci.example.com): " target_domain
        fi
    fi

    target_domain=$(echo "${target_domain}" | sed -E 's|^https?://||' | sed -E 's|/.*$||' | tr -d '[:space:]')
    if [[ ! "$target_domain" =~ ^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$ ]]; then
        echo -e "${RED}❌ 域名格式无效，请检查后重试！${PLAIN}"
        return 1
    fi

    echo -e "🚀 正在为 ${CYAN}${target_domain}${PLAIN} 配置 Nginx 反向代理与申请 SSL 证书..."

    # 依赖与端口
    if ! command -v nginx >/dev/null 2>&1 || ! command -v certbot >/dev/null 2>&1; then
        echo -e "📦 正在安装 Nginx 与 Certbot..."
        if command -v apt-get >/dev/null 2>&1; then
            apt-get update -y >/dev/null 2>&1 && apt-get install -y nginx certbot python3-certbot-nginx >/dev/null 2>&1 || true
        elif command -v dnf >/dev/null 2>&1; then
            dnf install -y nginx certbot python3-certbot-nginx >/dev/null 2>&1 || true
        elif command -v yum >/dev/null 2>&1; then
            yum install -y epel-release nginx certbot >/dev/null 2>&1 || true
        fi
    fi
    open_port 80
    open_port 443

    mkdir -p "${INSTALL_DIR}/ssl" /var/www/html /etc/nginx/conf.d 2>/dev/null || true
    local self_key="${INSTALL_DIR}/ssl/${target_domain}.key"
    local self_crt="${INSTALL_DIR}/ssl/${target_domain}.crt"
    if [[ ! -f "$self_key" || ! -f "$self_crt" ]]; then
        openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
            -keyout "$self_key" -out "$self_crt" \
            -subj "/C=CN/ST=Cloud/L=OCIBox/O=OCIBox/CN=${target_domain}" >/dev/null 2>&1 || true
    fi

    local cert_file="$self_crt"
    local key_file="$self_key"
    if [[ -f "/etc/letsencrypt/live/${target_domain}/fullchain.pem" && -f "/etc/letsencrypt/live/${target_domain}/privkey.pem" ]]; then
        cert_file="/etc/letsencrypt/live/${target_domain}/fullchain.pem"
        key_file="/etc/letsencrypt/live/${target_domain}/privkey.pem"
    fi

    cat << NGINX_CONF > /etc/nginx/conf.d/ocibox.conf
server {
    listen 80;
    listen [::]:80;
    server_name ${target_domain};

    location /.well-known/acme-challenge/ {
        root /var/www/html;
        try_files \$uri =404;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name ${target_domain};

    ssl_certificate ${cert_file};
    ssl_certificate_key ${key_file};

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;

    client_max_body_size 50M;

    location / {
        proxy_pass http://127.0.0.1:${port};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
NGINX_CONF

    if [[ -d "/etc/nginx/sites-enabled" ]]; then
        ln -sf /etc/nginx/conf.d/ocibox.conf /etc/nginx/sites-enabled/ocibox.conf 2>/dev/null || true
    fi

    systemctl restart nginx >/dev/null 2>&1 || systemctl start nginx >/dev/null 2>&1 || true

    if command -v certbot >/dev/null 2>&1; then
        echo -e "🔒 正在为 ${CYAN}${target_domain}${PLAIN} 向 Let's Encrypt 申请官方免费 SSL 证书..."
        local cert_ok=false
        if certbot certonly --webroot -w /var/www/html -d "${target_domain}" --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring >/dev/null 2>&1; then
            cert_ok=true
        elif certbot certonly --standalone -d "${target_domain}" --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring --pre-hook "systemctl stop nginx" --post-hook "systemctl start nginx" >/dev/null 2>&1; then
            cert_ok=true
        fi

        if [[ "$cert_ok" == "true" && -f "/etc/letsencrypt/live/${target_domain}/fullchain.pem" ]]; then
            sed -i.bak -E "s|ssl_certificate .*;|ssl_certificate /etc/letsencrypt/live/${target_domain}/fullchain.pem;|g" /etc/nginx/conf.d/ocibox.conf
            sed -i.bak -E "s|ssl_certificate_key .*;|ssl_certificate_key /etc/letsencrypt/live/${target_domain}/privkey.pem;|g" /etc/nginx/conf.d/ocibox.conf
            rm -f /etc/nginx/conf.d/ocibox.conf.bak 2>/dev/null || true
            nginx -s reload >/dev/null 2>&1 || systemctl reload nginx >/dev/null 2>&1 || systemctl restart nginx >/dev/null 2>&1 || true
            echo -e "  ${GREEN}✅ Let's Encrypt 官方证书申请并部署成功！${PLAIN}"
        else
            echo -e "  ${YELLOW}⚠️ Let's Encrypt 证书签发暂未完成，已自动挂载自签名 SSL 证书保障 HTTPS 运行。${PLAIN}"
        fi
    fi

    # 调整 application.yml
    sed -i.bak -E 's/^[[:space:]]*address:[[:space:]].*$/  address: 127.0.0.1/' "$conf" 2>/dev/null || true
    sed -i.bak -E 's/^[[:space:]]*trust-forwarded-for:[[:space:]].*$/  trust-forwarded-for: true/' "$conf" 2>/dev/null || true
    rm -f "${conf}.bak" 2>/dev/null || true

    echo "BIND_DOMAIN=true" > "$domain_conf"
    echo "DOMAIN=\"${target_domain}\"" >> "$domain_conf"
    chmod 600 "$domain_conf" 2>/dev/null || true

    local svc=$(get_service_name)
    systemctl restart "$svc" >/dev/null 2>&1 || true
    systemctl enable nginx >/dev/null 2>&1 || true

    echo -e "\n${GREEN}🎉 域名与 SSL 证书配置已完成！${PLAIN}"
    echo -e "🌐 ${CYAN}管理后台新地址${PLAIN} : ${GREEN}https://${target_domain}${PLAIN}"
    echo -e "🔗 ${CYAN}本地备用地址${PLAIN}   : ${CYAN}http://${server_ip}:${port}${PLAIN}\n"
}

case "${1:-}" in
    status) ocibox_status ;;
    restart) ocibox_restart ;;
    start) ocibox_start ;;
    stop) ocibox_stop ;;
    log|logs) ocibox_logs ;;
    update) ocibox_update ;;
    port) ocibox_port "${2:-}" ;;
    cert|ssl|domain) ocibox_cert "${2:-}" ;;
    info) ocibox_info ;;
    help|--help|-h)
        echo "用法: oci [status|restart|start|stop|logs|update|port|cert|info]"
        ;;
    *)
        ocibox_info
        if [[ -t 0 ]]; then
            echo -e "\n${CYAN}💡 快捷操作: [1] 重启面板  [2] 查看状态  [3] 实时日志  [4] 在线更新  [5] 修改端口  [6] 域名证书  [0/回车] 退出${PLAIN}"
            read -rp "👉 请输入选项编号 [默认回车退出]: " opt
            case "$opt" in
                1) ocibox_restart ;;
                2) ocibox_status ;;
                3) ocibox_logs ;;
                4) ocibox_update ;;
                5) ocibox_port ;;
                6) ocibox_cert ;;
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

# 9. 获取公网 IP 与展示最终面板地址
SERVER_IP=$(curl -s4 --max-time 3 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 3 https://ifconfig.me 2>/dev/null || curl -s4 --max-time 3 https://icanhazip.com 2>/dev/null || echo "127.0.0.1")

echo -e "\n${GREEN}================================================================${PLAIN}"
echo -e "${GREEN}       🎉 OCIBox 个人专属管理面板 部署成功！${PLAIN}"
echo -e "${GREEN}================================================================${PLAIN}"
if [[ "${BIND_DOMAIN}" == "true" && -n "${DOMAIN}" ]]; then
    echo -e "🌐 ${CYAN}管理后台地址${PLAIN} : ${GREEN}https://${DOMAIN}${PLAIN}"
    echo -e "🔗 ${CYAN}本地直连备用${PLAIN} : ${CYAN}http://${SERVER_IP}:${PORT}${PLAIN}"
else
    echo -e "🌐 ${CYAN}管理后台地址${PLAIN} : ${GREEN}http://${SERVER_IP}:${PORT}${PLAIN}"
fi
echo -e "👤 ${CYAN}管理登录账号${PLAIN} : ${GREEN}${ADMIN_USER}${PLAIN}"
echo -e "🔑 ${CYAN}管理登录密码${PLAIN} : ${YELLOW}${ADMIN_PWD}${PLAIN}"
echo -e "🤖 ${CYAN}Telegram 状态${PLAIN}: ${TG_STATUS}"
echo -e "🔒 ${CYAN}域名与证书${PLAIN}  : ${DOMAIN_STATUS}"
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
echo -e "   - 域名证书管理 : ${YELLOW}oci cert${PLAIN} (申请/重新绑定/维护证书)"
echo -e "   - 查看实时日志 : ${YELLOW}oci logs${PLAIN} (或 journalctl -u ocibox -f -n 50)"
echo -e "   - 在线版本更新 : ${YELLOW}oci update${PLAIN}"
echo -e "${GREEN}================================================================${PLAIN}\n"
