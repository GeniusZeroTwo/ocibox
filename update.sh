#!/bin/bash
# ================================================================
#  OCIBox - 个人自用管理面板 快速更新脚本
#  自动保留 SQLite 数据库、账号私钥与 application.yml 配置
# ================================================================

set -uo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
PLAIN='\033[0m'

echo -e "${GREEN}>>> 正在执行 OCIBox 面板安全更新...${PLAIN}"

INSTALL_DIR="/root/ocibox"
# 自动检测并平滑迁移旧版目录 /root/oci-personal
if [[ ! -d "${INSTALL_DIR}" && -d "/root/oci-personal" ]]; then
    echo -e "${GREEN}📦 检测到历史数据目录 /root/oci-personal，正在自动平滑迁移至 ${INSTALL_DIR}...${PLAIN}"
    mv /root/oci-personal "${INSTALL_DIR}"
fi

if [[ ! -d "${INSTALL_DIR}" ]]; then
    echo -e "${RED}❌ 未检测到 ${INSTALL_DIR} 目录，请先执行 install.sh 进行全新安装。${PLAIN}"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. 停止当前运行服务
echo -e "⏹️ 正在暂停现有服务以确保 SQLite 数据安全..."
systemctl stop ocibox >/dev/null 2>&1 || true
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
    DOWNLOAD_URL="https://github.com/GeniusZeroTwo/ocibox/releases/download/latest/ocibox.jar"
    ALT_URL="https://github.com/GeniusZeroTwo/ocibox/releases/download/latest/oci-personal.jar"
    if ! curl -fL --retry 3 --connect-timeout 10 "${DOWNLOAD_URL}" -o "${INSTALL_DIR}/app.jar" 2>/dev/null && \
       ! curl -fL --retry 3 --connect-timeout 10 "${ALT_URL}" -o "${INSTALL_DIR}/app.jar"; then
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

# 4. 配置并对齐 ocibox.service 服务
if systemctl list-unit-files 2>/dev/null | grep -q "oci-personal.service" || [[ -f "/etc/systemd/system/oci-personal.service" ]]; then
    echo -e "🧹 正在迁移旧版服务至 ocibox.service..."
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

# 5. 更新系统快捷指令 /usr/local/bin/ocibox
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

show_menu() {
    clear
    echo -e "${CYAN}"
    cat << "BANNER"
  ____   ____ ___ ____              
 / __ \ / ___|_ _| __ )  _____  __ 
| |  | | |    | ||  _ \ / _ \ \/ / 
| |__| | |___ | || |_) | (_) >  <  
 \____/ \____|___|____/ \___/_/\_\ 
BANNER
    echo -e "${PLAIN}"
    echo -e "${GREEN}=== OCIBox 个人面板管理系统 ===${PLAIN}"
    echo -e " 1. 查看面板状态 (status)"
    echo -e " 2. 重启管理面板 (restart)"
    echo -e " 3. 启动管理面板 (start)"
    echo -e " 4. 停止管理面板 (stop)"
    echo -e " 5. 查看实时日志 (logs)"
    echo -e " 6. 一键在线更新 (update)"
    echo -e " 7. 查看访问信息 (info)"
    echo -e " 0. 退出向导"
    echo -e "----------------------------------"
    read -rp "👉 请输入选项 [0-7]: " num
    case "$num" in
        1) ocibox_status ;;
        2) ocibox_restart ;;
        3) ocibox_start ;;
        4) ocibox_stop ;;
        5) ocibox_logs ;;
        6) ocibox_update ;;
        7) ocibox_info ;;
        0) exit 0 ;;
        *) echo -e "${RED}输入错误，请输入 0-7 之间的数字！${PLAIN}" ;;
    esac
}

ocibox_status() {
    systemctl status ocibox
}

ocibox_restart() {
    echo -e "${GREEN}正在重启 OCIBox 面板服务...${PLAIN}"
    systemctl restart ocibox
    sleep 2
    systemctl status ocibox --no-pager | head -n 12
}

ocibox_start() {
    echo -e "${GREEN}正在启动 OCIBox 面板服务...${PLAIN}"
    systemctl start ocibox
    sleep 2
    systemctl status ocibox --no-pager | head -n 12
}

ocibox_stop() {
    echo -e "${YELLOW}正在停止 OCIBox 面板服务...${PLAIN}"
    systemctl stop ocibox
    echo -e "${GREEN}OCIBox 服务已停止。${PLAIN}"
}

ocibox_logs() {
    echo -e "${CYAN}正在进入 OCIBox 实时日志输出 (按 Ctrl+C 退出)...${PLAIN}"
    journalctl -u ocibox -f -n 50
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
    if [[ ! -f "$conf" ]]; then
        echo -e "${RED}未找到配置文件: $conf${PLAIN}"
        return
    fi
    local port=$(grep -E '^[[:space:]]*port:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*port:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    local pwd=$(grep -E '^[[:space:]]*admin-password:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*admin-password:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    local bot=$(grep -E '^[[:space:]]*bot-token:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*bot-token:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    local admin_id=$(grep -E '^[[:space:]]*admin-id:' "$conf" | head -n 1 | sed -E 's/^[[:space:]]*admin-id:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
    local ip=$(curl -s4 --max-time 3 https://api.ipify.org 2>/dev/null || curl -s4 --max-time 3 https://ifconfig.me 2>/dev/null || echo "127.0.0.1")

    echo -e "\n${GREEN}================== [ OCIBox 面板信息 ] ==================${PLAIN}"
    echo -e "🌐 面板后台地址 : ${GREEN}http://${ip}:${port}${PLAIN}"
    echo -e "🔑 管理员密码   : ${YELLOW}${pwd}${PLAIN}"
    if [[ -n "$bot" && "$bot" != '""' && -n "$admin_id" ]]; then
        echo -e "🤖 Telegram推送 : ${GREEN}已配置 (Admin ID: ${admin_id})${PLAIN}"
    else
        echo -e "🤖 Telegram推送 : ${YELLOW}未配置${PLAIN}"
    fi
    echo -e "📂 数据持久目录 : ${INSTALL_DIR}/data/"
    echo -e "🔑 密钥存储目录 : ${INSTALL_DIR}/keys/"
    echo -e "📄 配置文件路径 : ${conf}"
    echo -e "========================================================\n"
}

case "${1:-}" in
    status) ocibox_status ;;
    restart) ocibox_restart ;;
    start) ocibox_start ;;
    stop) ocibox_stop ;;
    log|logs) ocibox_logs ;;
    update) ocibox_update ;;
    info) ocibox_info ;;
    help|--help|-h)
        echo "用法: ocibox [status|restart|start|stop|logs|update|info]"
        ;;
    *)
        show_menu
        ;;
esac
CLI
chmod +x /usr/local/bin/ocibox
ln -sf /usr/local/bin/ocibox /usr/bin/ocibox 2>/dev/null || true
ln -sf /usr/local/bin/ocibox /usr/local/bin/oci-personal 2>/dev/null || true

# 6. 重启服务
echo -e "🚀 正在启动更新后的 OCIBox 服务..."
systemctl daemon-reload
systemctl enable ocibox >/dev/null 2>&1
systemctl restart ocibox

sleep 2
if systemctl is-active --quiet ocibox; then
    echo -e "\n${GREEN}🎉 OCIBox 面板更新成功并已恢复运行！${PLAIN}"
    
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
    echo -e "💡 提示: 您现在可随时在终端输入 ${GREEN}ocibox${PLAIN} 呼出管理菜单！"
    systemctl status ocibox --no-pager | head -n 10
else
    echo -e "${YELLOW}⚠️ 服务启动中或存在异常，请使用 journalctl -u ocibox -n 50 查看详细启动日志。${PLAIN}"
fi
