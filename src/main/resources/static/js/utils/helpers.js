// 🛠️ 通用无状态工具函数库 (Utils & Helpers)

// 🔒 【XSS 修复】HTML 转义助手：用于所有拼进 v-html 的动态值（实例名/账号名/云盘名/接口返回文本等）。
// 这些数据可由 OCI 侧内容操控，未转义地拼进 v-html 会导致存储型 XSS 窃取会话 token。
export const esc = (v) => {
    if (v === null || v === undefined) return '';
    return String(v)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
};

// 🌟 机型简洁显示：VM.Standard.A1.Flex -> A1.Flex
export function shortShape(shape) {
    return String(shape || '').replace(/^VM\.Standard\./i, '').replace(/^BM\.Standard\./i, 'BM ');
}

// 提取展示用账号名 (去包裹方括号)
export function getDisplayAccountName(acc) {
    if (!acc) return '';
    return String(acc).replace(/^\[(.*)\]$/, '$1');
}

// 随机生成高强度密码
export function genRandomPwd() {
    const a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+";
    return Array.from({ length: 32 }, () => a[Math.floor(Math.random() * a.length)]).join('');
}

// 实例状态变更中文提示
export function getChangingStateText(state) {
    if (!state) return '状态变更中...';
    const s = String(state).toUpperCase();
    if (s === 'STARTING') return '开机中...';
    if (s === 'STOPPING') return '关机中...';
    if (s === 'PROVISIONING') return '配置创建中...';
    if (s === 'TERMINATING') return '销毁终止中...';
    return `状态变更中 (${state})...`;
}

// 引导卷状态变更中文提示
export function getBootVolumeChangingStateText(state) {
    if (!state) return '状态变更中...';
    const s = String(state).toUpperCase();
    if (s === 'PROVISIONING') return '扩容/创建中...';
    if (s === 'RESTORING') return '恢复中...';
    if (s === 'TERMINATING') return '删除中...';
    if (s === 'DETACHING') return '分离中...';
    return `状态变更中 (${state})...`;
}

// 500Mbps 资格与激活判断
export function is500mEligible(inst) {
    if (!inst || !inst.shape) return false;
    return inst.shape.includes('E2.1.Micro') || inst.shape.includes('A1.Flex') || inst.shape.includes('A1');
}

export function is500mActive(inst) {
    if (!inst) return false;
    if (inst.is500m === true || inst.is500m === 'true') return true;
    if (inst.ip && (inst.ip.includes('(NAT)') || inst.ip.includes('500M') || inst.ip.includes('(原)'))) return true;
    return false;
}

// 流量监控相关辅助函数
export function isTrafficError(info) {
    if (!info) return false;
    const t = info.update_time || '';
    return t.includes('失败') || /error|exception|401|404|not authorized|not found/i.test(t);
}

export function getTrafficUsageText(info) {
    if (!info) return '0.00 GB';
    if (info.usage_gb < 0 && !isTrafficError(info)) return '同步中...';
    if (isTrafficError(info)) return '接口调用失败';
    return (info.usage_gb || 0).toFixed(2) + ' GB';
}

export function getTrafficPercent(info) {
    if (!info) return 0;
    if (isTrafficError(info)) return 100;
    if (info.limit_gb > 0 && info.usage_gb >= 0) return Math.min((info.usage_gb / info.limit_gb) * 100, 100);
    return 0;
}

export function getTrafficColor(info) {
    if (!info) return 'var(--primary)';
    if (isTrafficError(info)) return 'var(--danger)';
    if (info.usage_gb < 0) return 'var(--warning)';
    if (info.limit_gb <= 0) return 'var(--primary)';
    const p = (info.usage_gb / info.limit_gb) * 100;
    if (p > 90) return 'var(--danger)';
    if (p > 70) return 'var(--warning)';
    return 'var(--primary)';
}

export function getTrafficCardStyle(info) {
    if (!info) return 'border: 1px solid var(--border); background: var(--surface);';
    if (isTrafficError(info)) return 'border: 1px solid rgba(239, 68, 68, 0.4); background: linear-gradient(to right, var(--surface), rgba(239, 68, 68, 0.05));';
    return 'border: 1px solid var(--border); background: var(--surface);';
}

export function getTrafficPercentText(info) {
    if (!info) return '';
    if (isTrafficError(info)) return `<span style="color:var(--danger); font-weight:600;">⚠️ 账户大概率已挂</span>`;
    if (info.limit_gb > 0) return info.usage_gb < 0 ? '请稍候...' : `占比：${((info.usage_gb / info.limit_gb) * 100).toFixed(1)}%`;
    return '未设置限制';
}

export function formatToShanghaiTime(t) {
    if (!t) return '';
    const trimmed = t.trim();
    const m = trimmed.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\s*(UTC|Z))?$/i);
    if (m) {
        let year = parseInt(m[1], 10);
        let month = parseInt(m[2], 10);
        let day = parseInt(m[3], 10);
        let hour = parseInt(m[4], 10);
        let min = parseInt(m[5], 10);
        let sec = parseInt(m[6], 10);
        const isUtc = !!m[7];

        if (isUtc) {
            const utcMs = Date.UTC(year, month - 1, day, hour, min, sec);
            const shDate = new Date(utcMs + 8 * 3600 * 1000);
            const pad = n => String(n).padStart(2, '0');
            year = shDate.getUTCFullYear();
            month = pad(shDate.getUTCMonth() + 1);
            day = pad(shDate.getUTCDate());
            hour = pad(shDate.getUTCHours());
            min = pad(shDate.getUTCMinutes());
            sec = pad(shDate.getUTCSeconds());
        } else {
            const pad = n => String(n).padStart(2, '0');
            month = pad(month);
            day = pad(day);
            hour = pad(hour);
            min = pad(min);
            sec = pad(sec);
        }

        const timeStr = `${hour}:${min}:${sec}`;
        const fullStr = `${year}-${month}-${day} ${timeStr}`;
        return `<span title="刷新时间 (上海时间): ${esc(fullStr)}">${esc(timeStr)}</span>`;
    }

    const parsed = new Date(trimmed.endsWith(' UTC') ? trimmed.replace(' UTC', 'Z') : trimmed);
    if (!isNaN(parsed.getTime())) {
        try {
            const parts = new Intl.DateTimeFormat('zh-CN', {
                timeZone: 'Asia/Shanghai',
                year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit',
                hour12: false
            }).formatToParts(parsed);
            const p = {};
            parts.forEach(pt => p[pt.type] = pt.value);
            const timeStr = `${p.hour}:${p.minute}:${p.second}`;
            const fullStr = `${p.year}-${p.month}-${p.day} ${timeStr}`;
            return `<span title="刷新时间 (上海时间): ${esc(fullStr)}">${esc(timeStr)}</span>`;
        } catch (e) {
            const shMs = parsed.getTime() + 8 * 3600 * 1000;
            const shDate = new Date(shMs);
            const pad = n => String(n).padStart(2, '0');
            const timeStr = `${pad(shDate.getUTCHours())}:${pad(shDate.getUTCMinutes())}:${pad(shDate.getUTCSeconds())}`;
            const fullStr = `${shDate.getUTCFullYear()}-${pad(shDate.getUTCMonth() + 1)}-${pad(shDate.getUTCDate())} ${timeStr}`;
            return `<span title="刷新时间 (上海时间): ${esc(fullStr)}">${esc(timeStr)}</span>`;
        }
    }

    return esc(trimmed.split(' ')[1] || trimmed);
}

export function getTrafficTimeText(info) {
    if (!info) return '';
    const t = info.update_time || '';
    if (!t) return '';
    if (t.includes('同步中')) return '正在同步';
    if (isTrafficError(info)) return `<span style="color:var(--danger);" title="${esc(t)}">[查看报错]</span>`;
    if (t === '从未同步') return '从未同步';
    return formatToShanghaiTime(t);
}

// 标签相关辅助
export const availableTagIcons = ['🏠', '💰', '📦', '✅', '⚡', '🛡️', '🚀', '💎', '🔑', '🌟', '🎯', '🔒', '🛒', '🏷️', '📌', '🎁', '🔴', '🟢', '🔵', '🟣'];

export const getAccountTags = (rawTag) => {
    if (!rawTag || typeof rawTag !== 'string') return [];
    return rawTag.split(/[,，]/).map(t => t.trim()).filter(Boolean);
};

export const formatTagDisplay = (t) => {
    if (!t) return '';
    const trimmed = t.trim();
    const firstChar = Array.from(trimmed)[0];
    const code = firstChar ? firstChar.codePointAt(0) : 0;
    const hasEmoji = (code >= 0x1F300 && code <= 0x1F9FF) || (code >= 0x2600 && code <= 0x27BF) || code > 0x1F000;
    if (hasEmoji) {
        return trimmed;
    }
    return '🏷️ ' + trimmed;
};

// 消息提示对象辅助
export const createMsgObject = () => ({ text: '', isSuccess: true });

export function showMessage(msgObj, text, isSuccess = true, time = 8000) {
    msgObj.text = text;
    msgObj.isSuccess = isSuccess;
    setTimeout(() => { msgObj.text = ''; }, time);
}

// 账号详情列表格式化
export function formatAccountDetails(rawList, insts = {}) {
    if (!rawList || !Array.isArray(rawList)) return [];
    const instancesObj = insts || {};
    return rawList.map((acc, idx) => {
        let totalCpu = 0;
        let totalRam = 0;
        let totalDisk = 0;
        if (instancesObj) {
            for (const ocid in instancesObj) {
                const inst = instancesObj[ocid];
                if (inst && (inst.account === acc.name || (inst.account && acc.name && getDisplayAccountName(inst.account) === getDisplayAccountName(acc.name)))) {
                    let c = parseFloat(inst.cpu);
                    if (isNaN(c)) c = inst.shape && inst.shape.includes('A1.Flex') ? 4 : 1;
                    let r = parseFloat(inst.memory);
                    if (isNaN(r)) r = inst.shape && inst.shape.includes('A1.Flex') ? 24 : 1;
                    let d = parseFloat(inst.disk);
                    if (isNaN(d)) d = 50;
                    totalCpu += c;
                    totalRam += r;
                    totalDisk += d;
                }
            }
        }
        const quotaUsage = `${totalCpu}核 / ${totalRam}GB / ${totalDisk}GB`;

        return {
            ...acc,
            _orderIndex: (typeof acc.orderIndex === 'number') ? acc.orderIndex : idx,
            quotaUsage: quotaUsage,
            billAmount: acc.billAmount || '¥ 0.00'
        };
    });
}

// 检查是否今日已推送提醒
export function isTodayNotified(dateStr) {
    if (!dateStr) return false;
    const today = new Date().toISOString().slice(0, 10);
    return String(dateStr).startsWith(today);
}

// 📋 剪贴板复制工具 (兼容 HTTPS、HTTP 与非安全上下文)
export async function copyToClipboard(text) {
    if (!text && text !== 0) return false;
    const str = String(text);
    if (navigator.clipboard && window.isSecureContext) {
        try {
            await navigator.clipboard.writeText(str);
            return true;
        } catch (e) {
            console.warn('navigator.clipboard write failed, trying fallback:', e);
        }
    }
    try {
        const textArea = document.createElement("textarea");
        textArea.value = str;
        textArea.style.position = "fixed";
        textArea.style.top = "-9999px";
        textArea.style.left = "-9999px";
        textArea.style.opacity = "0";
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();
        const successful = document.execCommand('copy');
        document.body.removeChild(textArea);
        return successful;
    } catch (err) {
        console.error('Fallback clipboard copy failed:', err);
        return false;
    }
}

// 🔐 WebAuthn / Passkey 专用 Base64URL 与 ArrayBuffer 互转工具
export function bufferToBase64Url(buffer) {
    if (!buffer) return '';
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let i = 0; i < bytes.byteLength; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

export function base64UrlToBuffer(base64Url) {
    if (!base64Url) return new ArrayBuffer(0);
    const base64 = String(base64Url).replace(/-/g, '+').replace(/_/g, '/');
    const padLen = (4 - (base64.length % 4)) % 4;
    const padded = base64 + '='.repeat(padLen);
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
}
