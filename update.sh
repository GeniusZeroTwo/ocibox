#!/bin/bash
# ================================================================
#  OCI Personal - 个人自用管理面板 快速更新脚本
#  自动保留 SQLite 数据库、账号私钥与 application.yml 配置
# ================================================================

set -uo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
PLAIN='\033[0m'

echo -e "${GREEN}>>> 正在执行 OCI Personal 面板安全更新...${PLAIN}"

INSTALL_DIR="/root/oci-personal"

if [[ ! -d "${INSTALL_DIR}" ]]; then
    echo -e "${RED}❌ 未检测到 ${INSTALL_DIR} 目录，请先执行 install.sh 进行全新安装。${PLAIN}"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. 停止当前运行服务
echo -e "⏹️ 正在暂停现有服务以确保 SQLite 数据安全..."
systemctl stop oci-personal >/dev/null 2>&1 || true

# 2. 更新核心 JAR
if [[ -f "${SCRIPT_DIR}/target/oci-personal.jar" ]]; then
    cp -f "${SCRIPT_DIR}/target/oci-personal.jar" "${INSTALL_DIR}/app.jar"
    echo -e "✅ 已更新核心程序包！"
elif [[ -f "${SCRIPT_DIR}/app.jar" ]]; then
    cp -f "${SCRIPT_DIR}/app.jar" "${INSTALL_DIR}/app.jar"
    echo -e "✅ 已更新核心程序包！"
else
    echo -e "⬇️ 正在从 GitHub 下载最新版本核心程序包..."
    DOWNLOAD_URL="https://github.com/GeniusZeroTwo/ocibox/releases/download/latest/oci-personal.jar"
    if ! curl -fL --retry 3 --connect-timeout 10 "${DOWNLOAD_URL}" -o "${INSTALL_DIR}/app.jar"; then
        echo -e "${RED}❌ 下载最新核心程序包失败！${PLAIN}"
        exit 1
    fi
    echo -e "✅ 已成功下载并更新核心程序包！"
fi

if [[ ! -s "${INSTALL_DIR}/app.jar" ]]; then
    echo -e "${RED}❌ 未检测到有效的核心程序包，无法启动！${PLAIN}"
    exit 1
fi

# 3. 确保存储目录权限安全
chmod 700 "${INSTALL_DIR}/keys" "${INSTALL_DIR}/data" 2>/dev/null || true
chmod 600 "${INSTALL_DIR}/keys/"*.pem 2>/dev/null || true
chmod 600 "${INSTALL_DIR}/application.yml" 2>/dev/null || true

# 4. 重启服务
echo -e "🚀 正在启动更新后的 OCI Personal 服务..."
systemctl daemon-reload
systemctl restart oci-personal

sleep 2
if systemctl is-active --quiet oci-personal; then
    echo -e "\n${GREEN}🎉 OCI Personal 面板更新成功并已恢复运行！${PLAIN}"
    
    # 自动读取并提示当前 Telegram 绑定状态
    if [[ -f "${INSTALL_DIR}/application.yml" ]]; then
        CURR_BOT=$(grep -E '^[[:space:]]*bot-token:' "${INSTALL_DIR}/application.yml" | head -n 1 | sed -E 's/^[[:space:]]*bot-token:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
        CURR_ADMIN=$(grep -E '^[[:space:]]*admin-id:' "${INSTALL_DIR}/application.yml" | head -n 1 | sed -E 's/^[[:space:]]*admin-id:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
        if [[ -n "${CURR_BOT}" && ! "${CURR_BOT}" =~ ^\$\{ && -n "${CURR_ADMIN}" && ! "${CURR_ADMIN}" =~ ^\$\{ ]]; then
            echo -e "🤖 ${CYAN}Telegram 机器人状态${PLAIN}: ${GREEN}已绑定 (Admin ID: ${CURR_ADMIN})${PLAIN} [配置已完好保留]"
        else
            echo -e "🤖 ${CYAN}Telegram 机器人状态${PLAIN}: ${YELLOW}未绑定${PLAIN}"
        fi
    fi
    echo -e "----------------------------------------------------------------"
    systemctl status oci-personal --no-pager | head -n 10
else
    echo -e "${YELLOW}⚠️ 服务启动中或存在异常，请使用 journalctl -u oci-personal -n 50 查看详细启动日志。${PLAIN}"
fi
