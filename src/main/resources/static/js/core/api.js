// 🌐 统一网络请求与接口异常拦截层 (API Layer)
import { sessionToken } from './state.js';
import { showMessage } from '../utils/helpers.js';

let _stepUpHandler = null;

export function registerStepUpHandler(fn) {
    _stepUpHandler = fn;
}

export async function apiCall(endpoint, payload = {}, msgTarget = null) {
    try {
        const token = sessionToken.value || (typeof sessionStorage !== 'undefined' ? (sessionStorage.getItem('oci_session_token') || '') : '') || '';
        if (!sessionToken.value && token) {
            sessionToken.value = token;
        }

        const safePayload = (payload && typeof payload === 'object') ? payload : {};
        const headers = { 'Content-Type': 'application/json' };
        if (token) {
            headers['X-Admin-Token'] = token;
        }

        const res = await fetch(`/api/admin/${endpoint}`, {
            method: 'POST',
            headers,
            body: JSON.stringify(safePayload)
        });
        let data = await res.json();

        // 🔒 高危接口按需无感触发 Step-Up 二次认证并自动重放原请求
        // 注意：/step-up 或 /passkey/step-up 已在服务端标记会话级 5 分钟 Step-Up 窗口，
        // 重放原请求时严禁再次附带一次性动态码 stepUpSecret，避免已核销动态码二次校验失败！
        if (data && !data.success && data.stepUpRequired
                && endpoint !== 'step-up'
                && endpoint !== 'passkey/step-up'
                && endpoint !== 'passkey/register/options'
                && !safePayload.stepUpSecret
                && typeof _stepUpHandler === 'function') {
            const stepUpOk = await _stepUpHandler();
            if (!stepUpOk) {
                if (msgTarget) {
                    showMessage(msgTarget, '已取消安全二次验证', false);
                }
                return { success: false, cancelled: true, error: '已取消安全二次验证' };
            }
            const retryRes = await fetch(`/api/admin/${endpoint}`, {
                method: 'POST',
                headers,
                body: JSON.stringify(safePayload)
            });
            data = await retryRes.json();
        }

        if (!data.success && msgTarget) {
            showMessage(msgTarget, data.error || '请求失败', false);
        }
        return data;
    } catch (e) {
        if (msgTarget) {
            showMessage(msgTarget, '网络请求异常', false);
        }
        // 🐞 标记网络异常，便于上层区分“鉴权失败”与“网络抖动”，避免掉线即登出
        return { success: false, error: e.message, networkError: true };
    }
}
