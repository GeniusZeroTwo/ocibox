# ☁️ OCI Personal - 甲骨文云个人自用轻量管理面板

> **完全面向客户自用自建 · 零商业租户与配额依赖 · 纯轻量化 SQLite 架构 (免 Redis) · 一键脚本自动部署**

---

## 🌟 核心特性与设计理念

针对个人玩家、站长与极客用户深度重构，彻底剔除原商业租户、机器上架售卖、支付宝/易支付接口及多用户换 IP 额度扣减等繁琐流程，回归纯粹、高效、自由的甲骨文个人云管理控制台：

- 🚀 **极简零依赖架构**：彻底告别 Redis 服务！采用轻量嵌入式 **SQLite3 (WAL 模式)** 搭配内置高并发内存缓存，整机内存开销降低 70%，哪怕在 1核 1G 的极低配小鸡上也能丝滑运行。
- 👤 **个人独占管理**：去除了客户 Telegram ID 授权面板与换 IP 额度扣减限制，作为管理员拥有全量实例的无限次公共 IP 更换、开关机、重启与系统重装权限。
- 🛠️ **一键脚本智能部署**：提供全自动化 `install.sh` 脚本，交互式选择运行端口、设置管理员密码，并**自主选择是否绑定 Telegram 运维机器人**；安装完成后自动检测公网 IP 并输出直达管理后台地址与凭证。
- 🛡️ **双重安全鉴权体系**：
  - 支持 **管理员固定密码 / 动态临时码** 登录。
  - 原生支持 **WebAuthn / Passkey 通行密钥** 免密登录（指纹 Touch ID / 人脸 Face ID / 物理安全密钥）。
- 📊 **当月出站流量全景监控**：全量甲骨文账号出站流量实时探测，支持按用量高低/百分比排序，独家提供 **「只显示升级号」** 过滤视图与 98% 阈值智能熔断告警。
- ⚡ **实例深度运维控制**：
  - 🔄 **公网 IPv4 一键极速更换**（全自动释放、申请与重新绑定，零额度拦截）。
  - 🌐 **IPv6 / 500Mbps NAT 网络优化 / 路由表一键修复**。
  - 🔓 **开放全端口**：自动配置安全列表规则并放通系统防火墙。
  - 💿 **系统重装**：在线拉取机型专属官方镜像（Ubuntu / Debian / CentOS / Oracle Linux 等）一键无损替换引导卷。
  - 💾 **引导卷清理**：自动探测闲置分离引导卷并提供一键安全扩容与删除。
- 🎯 **全自动批量抢机任务**：支持多账号、多区域、多配置（ARM 4C24G / AMD 微型实例）后台静默抢占与捡漏。
- 🤖 **个人专属 Telegram 运维 Bot**（可选）：
  - 专属私有化鉴权：非管理员访问直接拒止，安全不泄露。
  - 远程指令：支持 `/list`、`/traffic`、`/sync`、`/backup`、`/help` 以及内联交互按钮随时随地手机控机。

---

## 🚀 一键极速部署（推荐）

登录您的 VPS（推荐系统：Ubuntu 20.04/22.04/24.04、Debian 11/12、CentOS 8+/Rocky Linux），以 `root` 用户执行：

```bash
curl -sS -O https://raw.githubusercontent.com/GeniusZeroTwo/OCI-Personal/main/install.sh && chmod +x install.sh && ./install.sh
```

> **脚本交互过程示例：**
> 1. `请输入管理后台服务端口 [默认: 5000]:` （直接回车使用 5000，或输入自定义端口如 8080）
> 2. `请设置管理员登录密码 [回车自动生成随机强密码]:` （输入您想使用的后台登录密码）
> 3. `是否需要绑定您的 Telegram 运维机器人？(y/N, 默认 N):`
>    - 若输入 `y`：进一步输入从 `@BotFather` 获取的 Bot Token 与个人 Telegram ID。
>    - 若输入 `N`：直接跳过 Telegram 绑定，纯网页后台模式运行。

安装脚本执行完成后，控制台将输出如下精美汇总卡片：

```text
================================================================
       🎉 OCI Personal 个人轻量管理面板 部署成功！
================================================================
🌐 管理后台地址 : http://123.45.67.89:5000
🔑 管理登录密码 : YourAdminPassword123
🤖 Telegram 状态: 已绑定 (Admin ID: 123456789)
📂 数据持久目录 : /root/oci-personal/data/
🔑 密钥保存目录 : /root/oci-personal/keys/
📄 配置文件路径 : /root/oci-personal/application.yml
----------------------------------------------------------------
💡 服务日常维护指令:
   - 查看服务状态 : systemctl status oci-personal
   - 重启管理面板 : systemctl restart oci-personal
   - 停止管理面板 : systemctl stop oci-personal
   - 查看实时日志 : journalctl -u oci-personal -f -n 50
================================================================
```

---

## 🛠️ 手动部署与配置文件说明

如果您希望完全手动管理服务或自定义配置，可参考以下步骤：

### 1. 环境依赖安装（仅需 Java 21 与 SQLite3，免装 Redis）

```bash
# Ubuntu / Debian:
apt update && apt install -y openjdk-21-jre-headless sqlite3 curl jq

# CentOS / RHEL / Rocky Linux:
dnf install -y java-21-openjdk-headless sqlite3 curl jq
```

### 2. 目录创建与配置文件准备

```bash
mkdir -p /root/oci-personal/data /root/oci-personal/keys /root/oci-personal/logs
chmod 700 /root/oci-personal/keys /root/oci-personal/data
```

在 `/root/oci-personal/application.yml` 中写入：

```yaml
server:
  port: 5000
  address: 0.0.0.0

spring:
  datasource:
    url: jdbc:sqlite:/root/oci-personal/data/oci_personal.db?journal_mode=WAL&busy_timeout=60000
    driver-class-name: org.sqlite.JDBC
  threads:
    virtual:
      enabled: true

app:
  admin-password: "设置您的管理员密码"
  bot-token: "可选：您的TG_BOT_TOKEN"
  admin-id: "可选：您的TG_CHAT_ID"
```

### 3. 配置 Systemd 开机自启

创建 `/etc/systemd/system/oci-personal.service`：

```ini
[Unit]
Description=OCI Personal Cloud Management Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/root/oci-personal
ExecStart=/usr/bin/java -Xms64m -Xmx384m -XX:+UseG1GC -jar /root/oci-personal/app.jar
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

启动并托管服务：

```bash
systemctl daemon-reload
systemctl enable --now oci-personal
```

---

## 🔄 快速安全更新

当项目有新版本时，执行以下更新脚本，自动保留现有 SQLite 数据库、各账号 API 私钥及自定义配置：

```bash
curl -sS -O https://raw.githubusercontent.com/GeniusZeroTwo/OCI-Personal/main/update.sh && chmod +x update.sh && ./update.sh
```

---

## 📱 个人 Telegram Bot 运维指令

如在部署时启用了 Telegram 机器人，您可直接向 Bot 发送以下指令进行掌上运维：

| 指令 | 作用说明 |
| :--- | :--- |
| `/start` 或 `/menu` | 呼出个人主控台大盘菜单，含账号数、实例数与累计换 IP 次数 |
| `/list` | 分页列出名下所有甲骨文云服务器，点击可一键换 IP、重装系统、开关机 |
| `/traffic` | 实时汇总各账号当月出站流量消耗及限额预警进度条 |
| `/sync` | 立即触发全账号状态、新分配 IP 与实例列表深度同步 |
| `/backup` | 触发 SQLite 数据库与密钥安全打包加密备份 |
| `/help` | 查看详细操作指南 |

> **安全提示**：Bot 仅响应部署时配置的管理员 Chat ID，外部其他任何用户发送指令均会被安全拦截并提示无权访问。

---

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源，仅供个人学习与自用云资源运维。
