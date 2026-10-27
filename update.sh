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

# 1. 停止当前运行服务
echo -e "⏹️ 正在暂停现有服务 (${SERVICE_NAME}) 以确保 SQLite 数据安全..."
systemctl stop "$SERVICE_NAME" >/dev/null 2>&1 || true
systemctl stop ocibox >/dev/null 2>&1 || true
systemctl stop oci-personal >/dev/null 2>&1 || true

# 2. 更新核心 JAR
if [[ -f "${SCRIPT_DIR}/target/ocibox.jar" ]]; then
    cp -f "${SCRIPT_DIR}/target/ocibox.jar" "${INSTALL_DIR}/app.jar"
    echo -e "✅ 已更新核心程序包！"
elif [[ -f "${SCRIPT_DIR}/target/oci-personal.jar" ]]; then
    cp -f "${SCRIPT_DIR}/target/oci-personal.jar" "${INSTALL_DIR}/app.jar"
    echo -e "✅ 已更新核心程序包！"
elif [[ -f "${SCRIPT_DIR}/ocibox.jar" ]]; then
    cp -f "${SCRIPT_DIR}/ocibox.jar" "${INSTALL_DIR}/app.jar"
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

# 6. 重启服务
echo -e "🚀 正在启动更新后的服务 (${SERVICE_NAME})..."
systemctl daemon-reload
systemctl enable "${SERVICE_NAME}" >/dev/null 2>&1

# 自动确保当前配置端口放通防火墙
CONF_PORT=$(grep -E '^[[:space:]]*port:' "${INSTALL_DIR}/application.yml" 2>/dev/null | head -n 1 | sed -E 's/^[[:space:]]*port:[[:space:]]*"?(.*)"?[[:space:]]*$/\1/' | sed 's/"//g')
[[ -z "${CONF_PORT}" || "${CONF_PORT}" =~ ^\$\{ ]] && CONF_PORT="5000"
open_port "${CONF_PORT}"

systemctl restart "${SERVICE_NAME}"

sleep 2
if systemctl is-active --quiet "${SERVICE_NAME}"; then
    echo -e "\n${GREEN}🎉 面板核心更新成功并已恢复运行！${PLAIN}"
    /usr/local/bin/ocibox info
    echo -e "💡 提示: 您现在可直接在终端输入 ${GREEN}oci${PLAIN} 呼出此管理卡片与运维选项！\n"
else
    echo -e "${YELLOW}⚠️ 服务启动中或存在异常，请使用 journalctl -u ${SERVICE_NAME} -n 50 查看详细启动日志。${PLAIN}"
fi
