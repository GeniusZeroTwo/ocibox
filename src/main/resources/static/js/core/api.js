// 🌐 统一网络请求与接口异常拦截层 (API Layer)
import { sessionToken } from './state.js';
import { showMessage } from '../utils/helpers.js';

export async function apiCall(endpoint, payload = {}, msgTarget = null) {
    try {
        const token = sessionToken.value || (typeof sessionStorage !== 'undefined' ? (sessionStorage.getItem('oci_session_token') || '') : '') || '';
        if (!sessionToken.value && token) {
            sessionToken.value = token;
        }

        const res = await fetch(`/api/admin/${endpoint}`, {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/json',
                'X-Admin-Token': token
            },
            body: JSON.stringify({ token: token, ...payload })
        });
        const data = await res.json();
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
