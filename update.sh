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

# 智能识别现有安装目录（兼容 /root/ocibox 与 /root/oci-personal）
if [[ -d "/root/ocibox" ]]; then
    INSTALL_DIR="/root/ocibox"
elif [[ -d "/root/oci-personal" ]]; then
    INSTALL_DIR="/root/oci-personal"
else
    INSTALL_DIR="/root/ocibox"
fi

if [[ ! -d "${INSTALL_DIR}" ]]; then
    echo -e "${RED}❌ 未检测到 ${INSTALL_DIR} 目录，请先执行 install.sh 进行全新安装。${PLAIN}"
    exit 1
fi

# 检测现有活跃服务名（兼容 oci-personal 与 ocibox）
if systemctl list-unit-files 2>/dev/null | grep -q "oci-personal.service" || [[ -f "/etc/systemd/system/oci-personal.service" ]]; then
    SERVICE_NAME="oci-personal"
elif systemctl list-unit-files 2>/dev/null | grep -q "ocibox.service" || [[ -f "/etc/systemd/system/ocibox.service" ]]; then
    SERVICE_NAME="ocibox"
else
    SERVICE_NAME="oci-personal"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. 停止当前运行服务
echo -e "⏹️ 正在暂停现有服务 (${SERVICE_NAME}) 以确保 SQLite 数据安全..."
systemctl stop "$SERVICE_NAME" >/dev/null 2>&1 || true
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

# 4. 配置并更新 Systemd 服务
cat << SERVICE > /etc/systemd/system/${SERVICE_NAME}.service
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

# 建立服务软链接别名，确保 ocibox 和 oci-personal 均可无缝维护
if [[ "$SERVICE_NAME" == "oci-personal" ]]; then
    ln -sf /etc/systemd/system/oci-personal.service /etc/systemd/system/ocibox.service 2>/dev/null || true
elif [[ "$SERVICE_NAME" == "ocibox" ]]; then
    ln -sf /etc/systemd/system/ocibox.service /etc/systemd/system/oci-personal.service 2>/dev/null || true
fi

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
    echo -e "   - 查看实时日志 : journalctl -u ${svc} -f -n 50"
    echo -e "${GREEN}================================================================${PLAIN}"
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
        echo "用法: oci [status|restart|start|stop|logs|update|info]"
        ;;
    *)
        ocibox_info
        if [[ -t 0 ]]; then
            echo -e "\n${CYAN}💡 快捷操作: [1] 重启面板  [2] 查看状态  [3] 实时日志  [4] 在线更新  [0/回车] 退出${PLAIN}"
            read -rp "👉 请输入选项编号 [默认回车退出]: " opt
            case "$opt" in
                1) ocibox_restart ;;
                2) ocibox_status ;;
                3) ocibox_logs ;;
                4) ocibox_update ;;
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

# 6. 重启服务
echo -e "🚀 正在启动更新后的服务 (${SERVICE_NAME})..."
systemctl daemon-reload
systemctl enable "${SERVICE_NAME}" >/dev/null 2>&1
systemctl restart "${SERVICE_NAME}"

sleep 2
if systemctl is-active --quiet "${SERVICE_NAME}"; then
    echo -e "\n${GREEN}🎉 面板核心更新成功并已恢复运行！${PLAIN}"
    /usr/local/bin/ocibox info
    echo -e "💡 提示: 您现在可直接在终端输入 ${GREEN}oci${PLAIN} 呼出此管理卡片与运维选项！\n"
else
    echo -e "${YELLOW}⚠️ 服务启动中或存在异常，请使用 journalctl -u ${SERVICE_NAME} -n 50 查看详细启动日志。${PLAIN}"
fi
