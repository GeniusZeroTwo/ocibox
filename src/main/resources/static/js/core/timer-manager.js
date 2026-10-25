// ⏱️ 定时器与自适应轮询集中管理中心 (Timer Manager)
import {
    sessionToken, isEditing, currentTab, globalInstances, localPermState,
    accountDetails, globalAccounts, trafficData, isSyncingAccounts,
    syncingOcids, sysMsg, bootVolumes, manageAccountSelect
} from './state.js';
import { apiCall } from './api.js';
import { formatAccountDetails, showMessage } from '../utils/helpers.js';

let globalPollInterval = null;
let fastPollTimer = null;
let changingInstanceWatcherTimer = null;
let isCheckingChangingInstances = false;
let bootVolumeWatcherTimer = null;
let isCheckingBootVolumes = false;

// 依赖注入外部回调
let _fetchTasksFn = null;
let _loadBootVolumesFn = null;

export function registerTimerCallbacks({ fetchTasks, loadBootVolumes }) {
    if (fetchTasks) _fetchTasksFn = fetchTasks;
    if (loadBootVolumes) _loadBootVolumesFn = loadBootVolumes;
}

// 1. 快速轮询（抓取账单/注册时间时加速拉取）
export function triggerFastPolling(durationMs = 600000) {
    if (fastPollTimer) clearInterval(fastPollTimer);
    let elapsed = 0;
    const interval = 60000; // 🌟 轮询间隔 1 分钟 (60000ms)
    fastPollTimer = setInterval(async () => {
        elapsed += interval;
        if (!sessionToken.value || isEditing.value || elapsed > durationMs) {
            clearInterval(fastPollTimer);
            fastPollTimer = null;
            return;
        }
        try {
            const data = await apiCall('poll');
            if (data && data.success) {
                if (data.instances) {
                    for (const [ocid, newInfo] of Object.entries(data.instances)) {
                        if (globalInstances.value[ocid]) {
                            Object.assign(globalInstances.value[ocid], newInfo);
                        } else {
                            globalInstances.value[ocid] = newInfo;
                        }
                    }
                }
                if (data.accountDetails) {
                    accountDetails.value = formatAccountDetails(data.accountDetails, globalInstances.value);
                }
                if (data.accounts) {
                    globalAccounts.value = data.accounts;
                }
                const stillFetching = (data.accountDetails || []).some(a =>
                    a.registerTime === '自动抓取中...' || a.billAmount === '自动计算中...' || a.isPayg === 'checking'
                );
                if (!stillFetching && !isSyncingAccounts.value) {
                    clearInterval(fastPollTimer);
                    fastPollTimer = null;
                }
            }
        } catch (e) { }
    }, interval);
}

// 2. 实例过渡状态追踪器 (3秒心跳)
export async function checkAndAutoSyncChangingInstances() {
    if (isCheckingChangingInstances || !sessionToken.value || isEditing.value) return;

    const changingList = [];
    if (globalInstances.value) {
        for (const [ocid, inst] of Object.entries(globalInstances.value)) {
            if (inst && inst.state && !['RUNNING', 'STOPPED', 'TERMINATED'].includes(inst.state)) {
                changingList.push({ ocid, account: inst.account, name: inst.cleanName || inst.name, state: inst.state });
            }
        }
    }

    if (changingList.length === 0) {
        if (changingInstanceWatcherTimer) {
            clearInterval(changingInstanceWatcherTimer);
            changingInstanceWatcherTimer = null;
        }
        return;
    }

    isCheckingChangingInstances = true;
    try {
        for (const item of changingList) {
            if (syncingOcids.value[item.ocid]) continue;
            try {
                const data = await apiCall('instance-sync', { ocid: item.ocid, account: item.account });
                if (data && data.success) {
                    if (data.terminated || (data.instance && data.instance.state === 'TERMINATED')) {
                        const updated = { ...globalInstances.value };
                        delete updated[item.ocid];
                        globalInstances.value = updated;
                        if (localPermState.value && localPermState.value[item.ocid]) {
                            delete localPermState.value[item.ocid];
                        }
                        showMessage(sysMsg, `ℹ️ 实例【${item.name}】已终止并移出列表`, true);
                    } else if (data.instance) {
                        const oldState = globalInstances.value[item.ocid] ? globalInstances.value[item.ocid].state : '';
                        globalInstances.value = {
                            ...globalInstances.value,
                            [item.ocid]: { ...(globalInstances.value[item.ocid] || {}), ...data.instance }
                        };
                        const newState = data.instance.state;
                        if (['RUNNING', 'STOPPED'].includes(newState) && oldState !== newState) {
                            const stateText = newState === 'RUNNING' ? '运行中 🟢' : '已关机 🛑';
                            showMessage(sysMsg, `🎉 实例【${item.name}】状态已变更完成：${stateText}`, true);
                        }
                    }
                }
            } catch (e) { }
        }
    } finally {
        isCheckingChangingInstances = false;
    }
}

export function ensureChangingInstanceWatcher() {
    if (!changingInstanceWatcherTimer) {
        changingInstanceWatcherTimer = setInterval(checkAndAutoSyncChangingInstances, 3000);
    }
    checkAndAutoSyncChangingInstances();
}

// 3. 引导卷过渡状态追踪器 (3.5秒心跳)
export async function checkAndAutoSyncBootVolumes() {
    if (isCheckingBootVolumes || !sessionToken.value || currentTab.value !== 'manage' || !manageAccountSelect.value) return;
    const changingBvs = (bootVolumes.value || []).filter(bv => bv && bv.state && bv.state !== 'AVAILABLE');
    if (changingBvs.length === 0) {
        if (bootVolumeWatcherTimer) {
            clearInterval(bootVolumeWatcherTimer);
            bootVolumeWatcherTimer = null;
        }
        return;
    }
    isCheckingBootVolumes = true;
    try {
        if (typeof _loadBootVolumesFn === 'function') {
            await _loadBootVolumesFn(true /* silent */);
        }
    } finally {
        isCheckingBootVolumes = false;
    }
}

export function ensureBootVolumeWatcher() {
    if (!bootVolumeWatcherTimer) {
        bootVolumeWatcherTimer = setInterval(checkAndAutoSyncBootVolumes, 3500);
    }
    checkAndAutoSyncBootVolumes();
}

// 4. 全局心跳轮询 (5秒心跳)
export function startGlobalPolling() {
    if (globalPollInterval) clearInterval(globalPollInterval);
    let polling = false; // 🐞 轮询重入守卫：慢响应时避免请求堆叠、状态竞争
    globalPollInterval = setInterval(async () => {
        if (!sessionToken.value || isEditing.value) return;
        if (polling) return;
        polling = true;
        try {
            if (currentTab.value === 'auth' || currentTab.value === 'manage') {
                const data = await apiCall('poll');
                if (data && data.success) {
                    if (data.instances) {
                        for (const [ocid, newInfo] of Object.entries(data.instances)) {
                            if (globalInstances.value[ocid]) {
                                Object.assign(globalInstances.value[ocid], newInfo);
                            } else {
                                globalInstances.value[ocid] = newInfo;
                            }
                            if (!localPermState.value[ocid]) localPermState.value[ocid] = { selected: false, dateVal: '' };
                        }
                    }
                    if (data.accountDetails) {
                        accountDetails.value = formatAccountDetails(data.accountDetails, globalInstances.value);
                        // 🌟 如果检测到后台正在抓取/计算账单、注册时间或检测升级状态，自动开启快速轮询实现无感极速更新
                        if ((data.accountDetails || []).some(a => a.registerTime === '自动抓取中...' || a.billAmount === '自动计算中...' || a.isPayg === 'checking')) {
                            if (!fastPollTimer) triggerFastPolling();
                        }
                    }
                    if (data.accounts) {
                        globalAccounts.value = data.accounts;
                    }
                    if (data.traffic && currentTab.value === 'auth') {
                        for (const k in data.traffic) {
                            if (trafficData.value[k]) {
                                trafficData.value[k].usage_gb = data.traffic[k].usage_gb;
                                trafficData.value[k].update_time = data.traffic[k].update_time;
                                if (data.traffic[k].isPayg !== undefined) {
                                    trafficData.value[k].isPayg = data.traffic[k].isPayg;
                                }
                            } else {
                                data.traffic[k].showEdit = false;
                                trafficData.value[k] = data.traffic[k];
                            }
                        }
                    }
                    // 🌟 检查是否有处于状态变更中的实例或引导卷，自动启动针对性快速追踪
                    ensureChangingInstanceWatcher();
                    if (currentTab.value === 'manage') {
                        ensureBootVolumeWatcher();
                    }
                }
            } else if (currentTab.value === 'create') {
                if (typeof _fetchTasksFn === 'function') {
                    _fetchTasksFn();
                }
            }
        } catch (e) {
        } finally {
            polling = false;
        }
    }, 5000);
}

// 5. 集中清理所有定时器（防内存泄漏）
export function clearAllTimers() {
    if (globalPollInterval) {
        clearInterval(globalPollInterval);
        globalPollInterval = null;
    }
    if (fastPollTimer) {
        clearInterval(fastPollTimer);
        fastPollTimer = null;
    }
    if (changingInstanceWatcherTimer) {
        clearInterval(changingInstanceWatcherTimer);
        changingInstanceWatcherTimer = null;
    }
    if (bootVolumeWatcherTimer) {
        clearInterval(bootVolumeWatcherTimer);
        bootVolumeWatcherTimer = null;
    }
}
