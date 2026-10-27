// 🚀 主应用入口 (Main Application Entry)
import { createApp, ref, onMounted, onUnmounted, nextTick } from './vendor/vue.esm-browser.prod.js';
import {
    isInitializing, tgBotRunning, isDarkTheme, sessionToken, currentTab, isEditing,
    globalInstances, trafficData, tasksData, bootVolumes, globalAccounts, accountDetails,
    accountSortMode, accountTagFilter, sortedAccountDetails, trafficSortMode, trafficFilterMode,
    loginUsername, loginCode, isSendingCode, isLoggingIn, isPasskeyLoggingIn, cooldown,
    networkAccount, manageAccountSelect, deleteAccountSelect,
    deleteAccountList, createAccountList, manageAccountList,
    quotaTemplate, isLoadingTemplate, isSavingTemplate, isSyncing, isSyncingAccounts, isTrafficLoading, trafficSyncing,
    isSavingLimit, isFetchingImages, isCreating, isFetchingBV, isFixingRoute,
    syncingOcids, enablingIpv6Ocids, disablingIpv6Ocids,
    loginMsg, sysMsg, createMsg, manageMsg, reinstallMsg, importMsg,
    createImages, reinstallImages, createForm, modals, isDragging,
    availableRegionsForSelectedAccount, isRegionDropdownOpen, kpiStats,
    sortedTrafficData, trafficStats, isUpgradedAccount, groupedInstances,
    serverSearchKeyword, isBootVolumeCollapsed, persistSessionToken,
    unreadNoticeCount, notificationDrawerOpen, notificationsList,
    notificationFilterCategory, notificationUnreadOnly, notificationTotalCount,
    notificationLoading, toastNotifications
} from './core/state.js';

import { apiCall } from './core/api.js';
import {
    esc, shortShape, getDisplayAccountName, genRandomPwd, getChangingStateText,
    getBootVolumeChangingStateText, is500mEligible, is500mActive, isTrafficError,
    getTrafficUsageText, getTrafficPercent, getTrafficColor, getTrafficCardStyle,
    getTrafficPercentText, getTrafficTimeText, availableTagIcons, getAccountTags,
    formatTagDisplay, showMessage, formatAccountDetails, isTodayNotified, copyToClipboard,
    bufferToBase64Url, base64UrlToBuffer
} from './utils/helpers.js';
import {
    startGlobalPolling, triggerFastPolling, ensureChangingInstanceWatcher,
    ensureBootVolumeWatcher, clearAllTimers, registerTimerCallbacks
} from './core/timer-manager.js';

const app = createApp({
    setup() {
        // ==========================================
        // 🔒 编辑锁定状态控制
        // ==========================================
        function setEditing(status) {
            isEditing.value = status;
        }

        function applyServerData(data) {
            tgBotRunning.value = !!data.tgBotRunning;
            if (data.unreadNoticeCount !== undefined) {
                unreadNoticeCount.value = data.unreadNoticeCount;
            }
            globalInstances.value = data.instances || {};
            globalAccounts.value = data.accounts || [];
            accountDetails.value = formatAccountDetails(data.accountDetails, globalInstances.value);

            if ((data.accountDetails || []).some(a => a.registerTime === '自动抓取中...' || a.billAmount === '自动计算中...' || a.isPayg === 'checking')) {
                triggerFastPolling();
            }

            if (!createForm.account || !globalAccounts.value.includes(createForm.account)) {
                const defaultAcc = (createAccountList.value && createAccountList.value.length > 0)
                    ? createAccountList.value[0]
                    : (globalAccounts.value.length > 0 ? globalAccounts.value[0] : '');
                if (defaultAcc) {
                    createForm.account = defaultAcc;
                    fetchImagesForCreate();
                } else {
                    createForm.account = '';
                }
            }
            ensureChangingInstanceWatcher();
        }

        async function checkSession() {
            try {
                if (sessionToken.value) {
                    const data = await apiCall('data');
                    if (data.success) {
                        applyServerData(data);
                        loadTraffic(false);
                        startGlobalPolling();
                    } else if (!data.networkError) {
                        // 🐞 仅在真正的鉴权失败时登出；网络抖动/瞬时错误不清除会话，避免误踢下线。
                        logout();
                    }
                }
            } catch (err) {
                console.error('恢复会话异常:', err);
            } finally {
                isInitializing.value = false;
            }
        }

        // ==========================================
        // 👤 登录、退出与验证码
        // ==========================================
        async function login() {
            if (!loginCode.value) return showMessage(loginMsg, '请输入管理员密码或动态码', false);
            isLoggingIn.value = true;
            const payload = {
                username: loginUsername.value ? loginUsername.value.trim() : '',
                password: loginCode.value,
                code: loginCode.value
            };
            const data = await apiCall('login', payload, loginMsg);
            if (data.success) {
                if (loginUsername.value) {
                    localStorage.setItem('oci_admin_username', loginUsername.value.trim());
                }
                sessionToken.value = data.token;
                persistSessionToken(data.token);
                await checkSession();
            }
            isLoggingIn.value = false;
        }

        async function logout() {
            try {
                await apiCall('logout', {}, null);
            } catch (ignored) {}
            persistSessionToken('');
            sessionToken.value = '';
            loginCode.value = '';
            clearAllTimers();
            showMessage(loginMsg, '✅ 已安全退出登录', true);
        }

        async function sendAdminCode() {
            isSendingCode.value = true;
            const data = await apiCall('send-code', {}, loginMsg);
            if (data.success) {
                showMessage(loginMsg, data.message ? `✅ ${data.message}` : '✅ 验证码已发送至管理员的 Telegram！', true);
                startCooldown(60);
            }
            isSendingCode.value = false;
        }

        function startCooldown(seconds) {
            cooldown.value = seconds;
            localStorage.setItem('oci_code_time', new Date().getTime());
            const timer = setInterval(() => {
                cooldown.value--;
                if (cooldown.value <= 0) {
                    clearInterval(timer);
                    localStorage.removeItem('oci_code_time');
                }
            }, 1000);
        }

        // ==========================================
        // 🔐 通行密钥 (Passkey / WebAuthn)
        // ==========================================
        async function loginWithPasskey() {
            if (!window.PublicKeyCredential) {
                return showMessage(loginMsg, '❌ 当前浏览器或网络环境不支持 WebAuthn 通行密钥，请使用 TG 验证码登录', false);
            }
            isPasskeyLoggingIn.value = true;
            try {
                const optRes = await apiCall('passkey/login/options', { rpId: window.location.hostname }, loginMsg);
                if (!optRes.success) {
                    isPasskeyLoggingIn.value = false;
                    return;
                }

                const challengeBuffer = base64UrlToBuffer(optRes.challenge);
                const publicKey = {
                    challenge: challengeBuffer,
                    timeout: optRes.timeout || 60000,
                    userVerification: optRes.userVerification || 'preferred'
                };

                if (optRes.rpId) {
                    publicKey.rpId = optRes.rpId;
                }

                if (optRes.allowCredentials && optRes.allowCredentials.length > 0) {
                    publicKey.allowCredentials = optRes.allowCredentials.map(c => ({
                        type: 'public-key',
                        id: base64UrlToBuffer(c.id)
                    }));
                }

                const assertion = await navigator.credentials.get({ publicKey });
                if (!assertion) {
                    showMessage(loginMsg, '❌ 未获取到通行密钥凭据', false);
                    isPasskeyLoggingIn.value = false;
                    return;
                }

                const verifyPayload = {
                    challengeId: optRes.challengeId,
                    credentialId: bufferToBase64Url(assertion.rawId),
                    clientDataJSON: bufferToBase64Url(assertion.response.clientDataJSON),
                    authenticatorData: bufferToBase64Url(assertion.response.authenticatorData),
                    signature: bufferToBase64Url(assertion.response.signature)
                };

                const verifyRes = await apiCall('passkey/login/verify', verifyPayload, loginMsg);
                if (verifyRes.success) {
                    sessionToken.value = verifyRes.token;
                    persistSessionToken(verifyRes.token);
                    showMessage(loginMsg, `✅ 通行密钥 [${verifyRes.name || '验证通过'}] 登录成功！`, true);
                    await checkSession();
                }
            } catch (err) {
                if (err.name === 'NotAllowedError') {
                    showMessage(loginMsg, 'ℹ️ 未完成通行密钥验证', false);
                } else {
                    showMessage(loginMsg, `❌ 通行密钥验证异常: ${err.message}`, false);
                }
            } finally {
                isPasskeyLoggingIn.value = false;
            }
        }

        async function openPasskeyModal() {
            if (!sessionToken.value) {
                showMessage(loginMsg, '安全提示：通行密钥管理仅在登录后台后可用', false);
                return;
            }
            modals.passkey.show = true;
            modals.passkey.newName = '';
            modals.passkey.stepUpSecret = '';
            modals.passkey.msg = { text: '', isSuccess: true };
            await fetchPasskeys();
        }

        function closePasskeyModal() {
            modals.passkey.show = false;
        }

        function goToTgLogin() {
            closePasskeyModal();
            nextTick(() => {
                const loginArea = document.getElementById('loginArea');
                if (loginArea) {
                    loginArea.scrollIntoView({ behavior: 'smooth' });
                }
                const codeInput = document.querySelector('.login-code-input');
                if (codeInput) {
                    codeInput.focus();
                }
                showMessage(loginMsg, '首次添加通行密钥需要在 TG 登录后台后方可添加，请先获取并输入 TG 验证码登录！', false);
            });
        }

        async function loginWithPasskeyFromModal() {
            closePasskeyModal();
            await loginWithPasskey();
        }

        async function fetchPasskeys() {
            modals.passkey.isLoading = true;
            const data = await apiCall('passkey/list', {}, modals.passkey.msg);
            if (data.success) {
                modals.passkey.list = data.list || [];
            }
            modals.passkey.isLoading = false;
        }

        async function registerPasskey() {
            const name = (modals.passkey.newName || '').trim();
            const stepUpSecret = (modals.passkey.stepUpSecret || '').trim();
            if (!stepUpSecret) {
                return showMessage(modals.passkey.msg, '绑定前请再次输入管理员密码或动态码', false);
            }
            if (!name) {
                return showMessage(modals.passkey.msg, '请输入通行密钥设备名称/备注', false);
            }
            if (!window.PublicKeyCredential) {
                return showMessage(modals.passkey.msg, '当前环境不支持 WebAuthn 通行密钥', false);
            }
            modals.passkey.isRegistering = true;
            try {
                const optRes = await apiCall('passkey/register/options', {
                    rpId: window.location.hostname,
                    username: loginUsername.value ? loginUsername.value.trim() : '',
                    stepUpSecret: stepUpSecret
                }, modals.passkey.msg);
                if (!optRes.success) {
                    modals.passkey.isRegistering = false;
                    return;
                }

                const challengeBuffer = base64UrlToBuffer(optRes.challenge);
                const userIdBuffer = base64UrlToBuffer(optRes.user.id);

                const publicKey = {
                    rp: optRes.rp,
                    user: {
                        ...optRes.user,
                        id: userIdBuffer
                    },
                    challenge: challengeBuffer,
                    pubKeyCredParams: optRes.pubKeyCredParams,
                    authenticatorSelection: optRes.authenticatorSelection,
                    timeout: optRes.timeout || 60000,
                    attestation: optRes.attestation || 'none'
                };

                if (optRes.excludeCredentials && optRes.excludeCredentials.length > 0) {
                    publicKey.excludeCredentials = optRes.excludeCredentials.map(c => ({
                        type: 'public-key',
                        id: base64UrlToBuffer(c.id)
                    }));
                }

                const credential = await navigator.credentials.create({ publicKey });
                if (!credential) {
                    showMessage(modals.passkey.msg, '未获取到新凭据', false);
                    modals.passkey.isRegistering = false;
                    return;
                }

                let publicKeySpki = '';
                let algorithm = -7;
                if (credential.response.getPublicKey) {
                    const spkiBuf = credential.response.getPublicKey();
                    if (spkiBuf) publicKeySpki = bufferToBase64Url(spkiBuf);
                }
                if (credential.response.getPublicKeyAlgorithm) {
                    algorithm = credential.response.getPublicKeyAlgorithm() || -7;
                }

                const verifyPayload = {
                    credentialId: bufferToBase64Url(credential.rawId),
                    clientDataJSON: bufferToBase64Url(credential.response.clientDataJSON),
                    attestationObject: bufferToBase64Url(credential.response.attestationObject),
                    publicKeySpki: publicKeySpki,
                    algorithm: algorithm,
                    name: name
                };

                const verifyRes = await apiCall('passkey/register/verify', verifyPayload, modals.passkey.msg);
                if (verifyRes.success) {
                    showMessage(modals.passkey.msg, `✅ ${verifyRes.message || '通行密钥添加成功！'}`, true);
                    modals.passkey.newName = '';
                    modals.passkey.stepUpSecret = '';
                    await fetchPasskeys();
                }
            } catch (err) {
                if (err.name === 'NotAllowedError') {
                    showMessage(modals.passkey.msg, 'ℹ️ 已取消通行密钥注册', false);
                } else {
                    showMessage(modals.passkey.msg, `❌ 注册异常: ${err.message}`, false);
                }
            } finally {
                modals.passkey.isRegistering = false;
            }
        }

        function deletePasskey(passkeyItem) {
            openConfirmModal(
                '移除通行密钥',
                `⚠️ 确定要解绑并删除通行密钥 <strong>[${esc(passkeyItem.name)}]</strong> 吗？<br>删除后将无法使用该设备的面容或指纹一键登录系统。`,
                '确认删除',
                true,
                async () => {
                    const data = await apiCall('passkey/delete', { id: passkeyItem.id }, modals.passkey.msg);
                    if (data.success) {
                        showMessage(modals.passkey.msg, `✅ ${data.message || '通行密钥已移除'}`, true);
                        await fetchPasskeys();
                    }
                }
            );
        }

        // ==========================================
        // 🔀 页面导航与主题
        // ==========================================
        function switchTab(tab) {
            currentTab.value = tab;
            if (tab === 'auth') {
                apiCall('data').then(d => { if (d.success) applyServerData(d); });
                ensureChangingInstanceWatcher();
            }
            if (tab === 'manage') {
                apiCall('data').then(d => { if (d.success) applyServerData(d); });
                loadQuotaTemplate();
                ensureBootVolumeWatcher();
            }
            if (tab === 'create') fetchTasks();
        }


        function toggleTheme() {
            isDarkTheme.value = !isDarkTheme.value;
            document.documentElement.setAttribute('data-theme', isDarkTheme.value ? 'dark' : 'light');
            localStorage.setItem('oci-theme', isDarkTheme.value ? 'dark' : 'light');
        }

        // ==========================================
        // 🔄 同步操作
        // ==========================================
        async function syncServers() {
            isSyncing.value = true;
            showMessage(sysMsg, '正在发送同步指令...', true);
            const data = await apiCall('sync', {}, sysMsg);
            if (data.success && data.instances) {
                globalInstances.value = data.instances;
                showMessage(sysMsg, data.msg || '同步成功！', true);
                triggerFastPolling(600000); // 🌟 后台拉取最新 IP，开启轮询实现自动刷新显示
            }
            isSyncing.value = false;
        }

        async function syncAccountsDetails() {
            if (!sessionToken.value) return;
            isSyncingAccounts.value = true;
            showMessage(manageMsg, '正在发送同步指令...', true);
            const data = await apiCall('sync-accounts', {}, manageMsg);
            if (data.success) {
                showMessage(manageMsg, data.message || "✅ 同步指令已下发！后台正在实时同步，页面将自动更新数据...", true);
                triggerFastPolling(600000);
            }
            isSyncingAccounts.value = false;
        }



        async function syncSingleServerStatus(ocid, account, silent = false) {
            if (!account && globalInstances.value[ocid]) {
                account = globalInstances.value[ocid].account;
            }
            if (!silent) syncingOcids.value[ocid] = true;
            try {
                const data = await apiCall('instance-sync', { ocid: ocid, account: account }, silent ? null : sysMsg);
                if (data && data.success) {
                    if (data.terminated || (data.instance && data.instance.state === 'TERMINATED')) {
                        const updated = { ...globalInstances.value };
                        delete updated[ocid];
                        globalInstances.value = updated;
                        showMessage(sysMsg, data.message || 'ℹ️ 该实例在甲骨文云端已不存在或已被终止，已从列表中移除', true);
                    } else if (data.instance) {
                        globalInstances.value = { ...globalInstances.value, [ocid]: data.instance };
                        if (!silent) showMessage(sysMsg, '✅ 机器信息已全面同步', true);
                    }
                    return data;
                }
            } finally {
                if (!silent) syncingOcids.value[ocid] = false;
            }
            return null;
        }

        // ==========================================
        // 🖥️ 实例操作 (开关机/销毁/重命名/重装/网络特性)
        // ==========================================
        async function doInstanceAction(ocid, action) {
            const inst = globalInstances.value[ocid];
            const instName = inst ? (inst.cleanName || inst.name) : '实例';
            const actionText = action === 'START' ? '开机' : '关机';
            showMessage(sysMsg, `⏳ 正在发送${actionText}指令...`, true);

            // 🚀 立即置为过渡状态以实现零等待视觉反馈
            if (globalInstances.value[ocid]) {
                globalInstances.value[ocid].state = action === 'START' ? 'STARTING' : 'STOPPING';
            }
            ensureChangingInstanceWatcher();

            const data = await apiCall('instance-action', { ocid: ocid, action: action }, sysMsg);
            if (data && data.success) {
                showMessage(sysMsg, `✅ ${actionText}指令下发成功！系统正自动追踪状态变更...`, true);
                ensureChangingInstanceWatcher();
            } else {
                syncSingleServerStatus(ocid, inst ? inst.account : null, true);
            }
        }

        function confirmInstanceAction(ocid, action, name) {
            const actionText = action === 'START' ? '开机' : '关机';
            openConfirmModal(
                `实例${actionText}`,
                `确定要对实例 <strong>[${esc(name)}]</strong> 执行<strong>${actionText}</strong>操作吗？`,
                `确认${actionText}`,
                action === 'SOFTSTOP',
                () => doInstanceAction(ocid, action)
            );
        }

        async function confirmDeleteInstance(ocid, name) {
            openConfirmModal("销毁云服务器实例", `⚠️ 警告: 确定要永久销毁实例 <strong>[${esc(name)}]</strong> 吗？<br>该机器的所有数据将被抹除且不可找回！`, "确认销毁", true, async () => {
                showMessage(sysMsg, '⏳ 正在向甲骨文发送销毁指令...', true);
                if (globalInstances.value[ocid]) {
                    globalInstances.value[ocid].state = 'TERMINATING';
                }
                ensureChangingInstanceWatcher();
                const data = await apiCall('instance-delete', { ocid: ocid }, sysMsg);
                if (data && data.success) {
                    showMessage(sysMsg, '✅ 删除指令已下发，正在等待云端彻底释放...', true);
                    ensureChangingInstanceWatcher();
                } else {
                    syncSingleServerStatus(ocid, null, true);
                }
            });
        }

        function openRenameModal(ocid, currentName) {
            setEditing(true);
            modals.rename.ocid = ocid; modals.rename.oldName = currentName; modals.rename.newName = currentName; modals.rename.show = true;
        }

        async function confirmRenameInstance() {
            if (!modals.rename.newName) return showMessage(sysMsg, '❌ 新名称不能为空', false);
            if (modals.rename.newName === modals.rename.oldName) {
                modals.rename.show = false; setEditing(false); return;
            }
            const targetOcid = modals.rename.ocid;
            modals.rename.show = false; setEditing(false);
            showMessage(sysMsg, '⏳ 正在重命名...', true);
            const data = await apiCall('instance-rename', { ocid: targetOcid, newName: modals.rename.newName }, sysMsg);
            if (data.success) {
                showMessage(sysMsg, '🎉 重命名成功！正在同步最新信息...', true);
                syncSingleServerStatus(targetOcid);
            }
        }

        async function fetchImagesForReinstall() {
            const inst = globalInstances.value[modals.reinstall.ocid];
            if (!inst || !inst.account || !inst.shape) return;
            const data = await apiCall('creation/images', { account: inst.account, shape: inst.shape, region: inst.region });
            if (data.success) reinstallImages.value = data.images;
        }

        function openReinstallModal(ocid, name) {
            setEditing(true);
            modals.reinstall.ocid = ocid; modals.reinstall.targetName = name; modals.reinstall.newInstanceName = '';
            modals.reinstall.rootLogin = true; modals.reinstall.rootPwd = genRandomPwd();
            modals.reinstall.confirmStage = false; reinstallImages.value = []; modals.reinstall.show = true;
            fetchImagesForReinstall();
        }

        async function confirmReinstall() {
            const m = modals.reinstall;
            const auth = m.authType || 'password';
            const hasPwd = auth === 'password' || auth === 'both';
            const hasKey = auth === 'key' || auth === 'both';

            if (auth === 'password' && !m.rootPwd.trim()) return showMessage(reinstallMsg, '❌ 请输入或随机生成 Root 密码', false);
            if (auth === 'key' && !m.sshKey.trim()) return showMessage(reinstallMsg, '❌ 请输入或生成 SSH 公钥', false);
            if (auth === 'both' && (!m.rootPwd.trim() || !m.sshKey.trim())) return showMessage(reinstallMsg, '❌ 双重模式需要同时提供 Root 密码和 SSH 公钥', false);
            if (m.type === 'auto' && !m.os) return showMessage(reinstallMsg, '❌ 请选择官方镜像', false);
            if (m.type === 'custom' && !m.customOcid) return showMessage(reinstallMsg, '❌ 请输入自定义OCID', false);

            if (!m.confirmStage) {
                m.confirmStage = true; showMessage(reinstallMsg, '⚠️ 警告：重装会清空原系统数据！再次点击确认。', false);
                setTimeout(() => { m.confirmStage = false; }, 5000); return;
            }

            m.confirmStage = false; m.show = false; setEditing(false);
            showMessage(sysMsg, '⏳ 重装指令下发中...', true);

            const payload = { 
                ocid: m.ocid, 
                authType: auth,
                rootLogin: hasPwd, 
                rootPwd: hasPwd ? m.rootPwd.trim() : '', 
                sshKey: hasKey ? m.sshKey.trim() : '', 
                instanceName: m.newInstanceName 
            };
            if (m.type === 'auto') {
                const pts = m.os.split('|'); payload.osName = pts[0]; payload.osVersion = pts[1];
            } else {
                payload.imageOcid = m.customOcid;
            }
            const data = await apiCall('instance-reinstall', payload, sysMsg);
            if (data && data.success) {
                showMessage(sysMsg, '✅ 重装开始，系统正自动跟进状态！', true);
                if (globalInstances.value[m.ocid]) {
                    globalInstances.value[m.ocid].state = 'PROVISIONING';
                }
                ensureChangingInstanceWatcher();
            }
        }

        function openAllPorts() {
            if (!networkAccount.value) return showMessage(sysMsg, '请选择账号', false);
            openConfirmModal("放行所有端口", `⚠️ 确认放行账号 <strong>[${esc(networkAccount.value)}]</strong> 下的所有实例端口吗？<br>这会修改安全列表以允许所有入站和出站流量。`, "确认放行", false, async () => {
                showMessage(sysMsg, '⏳ 执行中...', true);
                const data = await apiCall('network/open-ports', { account: networkAccount.value }, sysMsg);
                if (data.success) showMessage(sysMsg, '✅ 端口放行成功！', true);
            });
        }

        function enable500Mbps(ocid, name) {
            openConfirmModal(
                "开启下行 500Mbps",
                `确实要为实例 <strong>[${esc(name)}]</strong> 一键开启下行 500Mbps 吗？<br><br>此操作将在后台为您分配一台网络负载均衡器和 NAT 网关，以此绕过网络限速，过程可能耗时数分钟，请勿重复操作。`,
                "确认开启",
                false,
                async () => {
                    showMessage(sysMsg, '⏳ 正在后台开启下行 500Mbps，这可能需要数分钟，请耐心等待...', true);
                    const data = await apiCall('instance-enable-500mbps', { ocid: ocid }, sysMsg);
                    if (data.success) {
                        showMessage(sysMsg, `✅ 实例【${name}】已成功开启下行 500Mbps！公网IP已变更为: ${data.publicIp}`, true);
                        syncSingleServerStatus(ocid);
                    }
                }
            );
        }

        function disable500Mbps(ocid, name) {
            openConfirmModal(
                "删除下行 500Mbps",
                `确实要为实例 <strong>[${esc(name)}]</strong> 删除 500Mbps 配置并恢复原生网络吗？<br><br>此操作将删除关联的网络负载均衡器(NLB)，并将网卡路由还原为直连互联网网关(IGW)，释放 NLB 免费配额。`,
                "确认删除",
                true,
                async () => {
                    showMessage(sysMsg, '⏳ 正在后台删除 500Mbps 配置并恢复原生网络，请稍候...', true);
                    const data = await apiCall('instance-disable-500mbps', { ocid: ocid }, sysMsg);
                    if (data.success) {
                        showMessage(sysMsg, `✅ 实例【${name}】已成功删除 500Mbps 配置！公网IP已恢复为: ${data.publicIp}`, true);
                        syncSingleServerStatus(ocid);
                    }
                }
            );
        }

        const enableAmd500Mbps = enable500Mbps;

        function enableIpv6(ocid, name) {
            openConfirmModal(
                "开启 IPv6",
                `确实要为实例 <strong>[${esc(name)}]</strong> 一键分配 IPv6 地址吗？<br><br>系统将自动检查并配置 VCN / 子网 IPv6 块、路由表出海网关及防火墙安全规则，并向该机器分配公网 IPv6 地址。过程可能耗时数秒至十几秒，请稍候。`,
                "确认开启",
                false,
                async () => {
                    enablingIpv6Ocids.value[ocid] = true;
                    showMessage(sysMsg, `⏳ 正在后台为【${name}】开启 IPv6 并配置网络规则，请稍候...`, true);
                    try {
                        const data = await apiCall('instance-enable-ipv6', { ocid: ocid }, sysMsg);
                        if (data && data.success) {
                            showMessage(sysMsg, `✅ 实例【${name}】已成功开启 IPv6！分配地址: ${data.ipv6} (若系统内未生效，可在机器内执行 dhclient -6)`, true);
                            syncSingleServerStatus(ocid);
                        }
                    } finally {
                        enablingIpv6Ocids.value[ocid] = false;
                    }
                }
            );
        }

        function disableIpv6(ocid, name) {
            openConfirmModal(
                "删除 IPv6",
                `确实要为实例 <strong>[${esc(name)}]</strong> 解绑并释放 IPv6 地址吗？<br><br>此操作将从实例的虚拟网卡上删除已分配的 IPv6 地址。`,
                "确认删除",
                true,
                async () => {
                    disablingIpv6Ocids.value[ocid] = true;
                    showMessage(sysMsg, `⏳ 正在后台为【${name}】删除 IPv6，请稍候...`, true);
                    try {
                        const data = await apiCall('instance-disable-ipv6', { ocid: ocid }, sysMsg);
                        if (data && data.success) {
                            showMessage(sysMsg, `✅ 实例【${name}】已成功删除 IPv6！`, true);
                            syncSingleServerStatus(ocid);
                        }
                    } finally {
                        disablingIpv6Ocids.value[ocid] = false;
                    }
                }
            );
        }

        // ==========================================
        // 🌐 流量统计与管理
        // ==========================================
        async function loadTraffic(force = false) {
            isTrafficLoading.value = force;
            const data = await apiCall('traffic', { force: force });
            if (data.success) {
                for (const k in data.traffic) data.traffic[k].showEdit = false;
                trafficData.value = data.traffic;
                if (Object.values(data.traffic).some((i) => i.usage_gb < 0 && !isTrafficError(i))) {
                    setTimeout(() => loadTraffic(false), 3000);
                }
            }
            isTrafficLoading.value = false;
        }

        async function syncSingleTraffic(acc) {
            trafficSyncing[acc] = true;
            await apiCall('traffic', { force: true, targetAccount: acc });
            setTimeout(() => loadTraffic(false), 3000);
            setTimeout(() => { trafficSyncing[acc] = false; }, 4000);
        }

        async function saveLimit(acc, val) {
            const limitValue = parseInt(val, 10) || 0;
            const data = await apiCall('set-traffic-limit', { account: acc, limit_gb: limitValue });
            if (data.success) loadTraffic(false);
        }

        // ==========================================
        // 🚀 抢机与批量实例创建
        // ==========================================
        async function fetchImagesForCreate() {
            if (!createForm.account || !createForm.shape) return;
            if (createForm.region && !availableRegionsForSelectedAccount.value.includes(createForm.region)) {
                createForm.region = '';
            }
            isFetchingImages.value = true;
            const data = await apiCall('creation/images', { account: createForm.account, shape: createForm.shape, region: createForm.region });
            createImages.value = data.success ? data.images : [];
            isFetchingImages.value = false;
        }

        function toggleRegionDropdown() {
            if (!createForm.account) {
                showMessage(createMsg, '请先在上方选择运行抢占的云账号', false);
                return;
            }
            isRegionDropdownOpen.value = !isRegionDropdownOpen.value;
        }

        function closeRegionDropdown() {
            isRegionDropdownOpen.value = false;
        }

        async function selectRegion(reg) {
            isRegionDropdownOpen.value = false;
            createForm.region = reg || '';
            await fetchImagesForCreate();
        }

        async function onRegionSelectChange() {
            if (createForm.region === '__SUBSCRIBE_NEW_REGION__') {
                createForm.region = '';
                openSubscribeRegionModal(createForm.account);
                return;
            }
            await fetchImagesForCreate();
        }

        async function openSubscribeRegionModal(accountName) {
            isRegionDropdownOpen.value = false;
            const targetAccount = accountName || createForm.account;
            if (!targetAccount) {
                showMessage(createMsg, '请先选择需要扩充区域的云账号', false);
                return;
            }

            modals.subscribeRegion.account = targetAccount;
            modals.subscribeRegion.selectedRegion = '';
            modals.subscribeRegion.customRegion = '';
            modals.subscribeRegion.isCustom = false;
            modals.subscribeRegion.filterText = '';
            modals.subscribeRegion.homeRegion = '';
            modals.subscribeRegion.tenancyId = '';
            modals.subscribeRegion.subscribedRegions = [];
            modals.subscribeRegion.allRegions = [];
            modals.subscribeRegion.isLoading = true;
            modals.subscribeRegion.isSubmitting = false;
            modals.subscribeRegion.msg = { text: '', isSuccess: true };
            modals.subscribeRegion.show = true;

            const res = await apiCall('account/regions', { account: targetAccount });
            if (res.success) {
                modals.subscribeRegion.homeRegion = res.homeRegion || '';
                modals.subscribeRegion.tenancyId = res.tenancyId || '';
                modals.subscribeRegion.subscribedRegions = res.subscribedRegions || [];
                modals.subscribeRegion.allRegions = res.allRegions || [];
            } else {
                showMessage(modals.subscribeRegion.msg, res.error || '获取该账号区域列表失败', false);
            }
            modals.subscribeRegion.isLoading = false;
        }

        async function confirmSubscribeRegion() {
            const target = modals.subscribeRegion.isCustom
                ? (modals.subscribeRegion.customRegion || '').trim()
                : modals.subscribeRegion.selectedRegion;

            if (!target) {
                showMessage(modals.subscribeRegion.msg, '请选择或输入要订阅的新区域', false);
                return;
            }

            modals.subscribeRegion.isSubmitting = true;
            showMessage(modals.subscribeRegion.msg, '⏳ 正在向甲骨文官方提交区域订阅请求，请稍候...', true);

            const res = await apiCall('account/subscribe-region', {
                account: modals.subscribeRegion.account,
                region: target,
                homeRegion: modals.subscribeRegion.homeRegion
            });

            if (res.success) {
                const newRegionName = res.regionName || target;
                showMessage(createMsg, res.message || `✅ 区域 [${newRegionName}] 订阅成功！`, true);

                const acc = accountDetails.value.find(a => a.name === modals.subscribeRegion.account);
                if (acc) {
                    const currentSubs = acc.subscribedRegions ? acc.subscribedRegions.split(',').map(r => r.trim()).filter(Boolean) : [];
                    if (!currentSubs.includes(newRegionName)) {
                        currentSubs.push(newRegionName);
                        acc.subscribedRegions = currentSubs.join(',');
                    }
                }

                if (createForm.account === modals.subscribeRegion.account) {
                    createForm.region = newRegionName;
                    await fetchImagesForCreate();
                }

                modals.subscribeRegion.show = false;
            } else {
                showMessage(modals.subscribeRegion.msg, res.error || '区域订阅失败', false);
            }
            modals.subscribeRegion.isSubmitting = false;
        }

        async function onShapeChange() {
            createForm.os = ''; // 🚨 切换 CPU 架构时，强制清空旧的 OS 镜像
            if (createForm.shape === 'VM.Standard.E2.1.Micro') {
                createForm.cpu = 1;
                createForm.ram = 1;
                createForm.disk = 50;
            } else if (createForm.shape === 'VM.Standard.A1.Flex') {
                createForm.cpu = 2;
                createForm.ram = 12;
                createForm.disk = 50;
            }
            await fetchImagesForCreate();
            if (createImages.value && createImages.value.length > 0) {
                createForm.os = createImages.value[0].value;
            }
        }

        async function generateSshKey(target) {
            const msg = target === 'create' ? createMsg : reinstallMsg;
            showMessage(msg, '⏳ 正在生成...', true);
            const data = await apiCall('creation/generate-key', {}, msg);
            if (data.success) {
                if (target === 'create') createForm.sshKey = data.publicKey.trim();
                else modals.reinstall.sshKey = data.publicKey.trim();

                const blob = new Blob([data.privateKey], { type: 'text/plain' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `oci_${(data.keyType || 'ed25519').toLowerCase().replace(/[^a-z0-9]/g, '_')}_${Date.now()}.pem`;
                a.click();
                URL.revokeObjectURL(url);
                showMessage(msg, `✅ 成功！${data.keyType || 'Ed25519'} 私钥已下载。`, true);
            }
        }

        async function submitCreationTask() {
            if (!createForm.os) return showMessage(createMsg, '❌ 请选择镜像', false);

            const auth = createForm.authType || 'password';
            const hasPwd = auth === 'password' || auth === 'both';
            const hasKey = auth === 'key' || auth === 'both';

            if (auth === 'password' && !createForm.rootPwd.trim()) {
                return showMessage(createMsg, '❌ 请输入或随机生成 Root 密码', false);
            }
            if (auth === 'key' && !createForm.sshKey.trim()) {
                return showMessage(createMsg, '❌ 请输入或生成 SSH 公钥', false);
            }
            if (auth === 'both' && (!createForm.rootPwd.trim() || !createForm.sshKey.trim())) {
                return showMessage(createMsg, '❌ 双重模式需要同时提供 Root 密码和 SSH 公钥', false);
            }

            isCreating.value = true;
            const pts = createForm.os.split('|');
            const p = { 
                ...createForm, 
                authType: auth,
                rootLogin: hasPwd,
                rootPwd: hasPwd ? createForm.rootPwd.trim() : '', 
                sshKey: hasKey ? createForm.sshKey.trim() : '',
                osName: pts[0], 
                osVersion: pts[1] 
            };
            const data = await apiCall('creation/start', p, createMsg);
            if (data.success) { showMessage(createMsg, '✅ 任务已下发！', true); fetchTasks(); }
            isCreating.value = false;
        }

        async function fetchTasks() {
            if (!sessionToken.value || currentTab.value !== 'create') return;
            const data = await apiCall('creation/list');
            if (data.success) {
                if (Object.keys(tasksData.value).length === 0) {
                    tasksData.value = data.tasks;
                } else {
                    for (const k in data.tasks) {
                        if (tasksData.value[k]) {
                            tasksData.value[k].successCount = data.tasks[k].successCount;
                            tasksData.value[k].status = data.tasks[k].status;
                            tasksData.value[k].attemptCount = data.tasks[k].attemptCount;
                            tasksData.value[k].log = data.tasks[k].log;
                            tasksData.value[k].running = data.tasks[k].running;
                        } else {
                            tasksData.value[k] = data.tasks[k];
                        }
                    }
                    for (const k in tasksData.value) {
                        if (!data.tasks[k]) delete tasksData.value[k];
                    }
                }
                nextTick(() => {
                    Object.keys(tasksData.value).forEach((id) => {
                        const el = document.getElementById(`log_${id}`);
                        if (el && tasksData.value[id].running) el.scrollTop = el.scrollHeight;
                    });
                });
            }
        }

        function confirmStopTask(id) {
            openConfirmModal("停止抢机任务", "⚠️ 确定要停止此抢机任务吗？", "确认停止", true, async () => {
                await apiCall('creation/stop', { taskId: id }); fetchTasks();
            });
        }

        function confirmClearTask(id) {
            openConfirmModal("删除任务记录", "🗑️ 确定要彻底删除该任务的日志记录吗？", "确认删除", true, async () => {
                await apiCall('creation/clear-log', { taskId: id }); fetchTasks();
            });
        }

        // ==========================================
        // 💾 引导卷管理
        // ==========================================
        async function loadBootVolumes(silent = false) {
            if (!manageAccountSelect.value) return;
            if (!silent) {
                isFetchingBV.value = true;
                showMessage(manageMsg, '拉取引导卷中...', true);
            }
            const data = await apiCall('boot-volumes/list', { account: manageAccountSelect.value }, silent ? null : manageMsg);
            if (data && data.success) {
                bootVolumes.value = data.data || [];
                if (!silent) showMessage(manageMsg, '✅ 成功', true);
            } else if (!silent) {
                bootVolumes.value = [];
            }
            if (!silent) isFetchingBV.value = false;
        }

        function detachBootVolume(bv) {
            openConfirmModal("强制分离引导卷", `⚠️ 确认强制分离引导卷 <strong>[${esc(bv.name)}]</strong> 吗？<br>这会导致关联的实例无法启动。`, "确认分离", true, async () => {
                showMessage(manageMsg, '⏳ 分离中...', true);
                if (bv) bv.state = 'DETACHING';
                ensureBootVolumeWatcher();
                const d = await apiCall('boot-volumes/detach', { attachmentId: bv.attachmentId, account: manageAccountSelect.value }, manageMsg);
                if (d && d.success) {
                    showMessage(manageMsg, '✅ 分离指令已下发，正在自动跟踪状态...', true);
                    ensureBootVolumeWatcher();
                } else {
                    loadBootVolumes(true);
                }
            });
        }

        function deleteBootVolume(bv) {
            openConfirmModal("彻底删除引导卷", `🚨 确定要彻底删除引导卷 <strong>[${esc(bv.name)}]</strong> 吗？<br>此操作将释放云盘空间，但内部数据将彻底灰飞烟灭。`, "释放空间", true, async () => {
                showMessage(manageMsg, '⏳ 销毁中...', true);
                if (bv) bv.state = 'TERMINATING';
                ensureBootVolumeWatcher();
                const d = await apiCall('boot-volumes/delete', { bootVolumeId: bv.id, account: manageAccountSelect.value }, manageMsg);
                if (d && d.success) {
                    showMessage(manageMsg, '✅ 销毁指令已下发，正在自动跟踪状态...', true);
                    ensureBootVolumeWatcher();
                } else {
                    loadBootVolumes(true);
                }
            });
        }

        function openExpandVolumeModal(bv) {
            modals.expandVolume.bv = bv;
            modals.expandVolume.newSize = '';
            modals.expandVolume.show = true;
        }

        async function confirmExpandVolume() {
            const bv = modals.expandVolume.bv;
            const newSizeStr = modals.expandVolume.newSize;
            const newSize = parseInt(newSizeStr, 10);
            if (!newSize || isNaN(newSize) || newSize <= parseInt(bv.size, 10)) {
                showMessage(manageMsg, '❌ 新容量必须大于当前容量', false);
                return;
            }
            modals.expandVolume.show = false;
            if (bv) bv.state = 'PROVISIONING';
            ensureBootVolumeWatcher();
            showMessage(manageMsg, '⏳ 正在提交扩容请求...', true);
            const d = await apiCall('boot-volumes/expand', { bootVolumeId: bv.id, account: manageAccountSelect.value, size: newSize.toString() }, manageMsg);
            if (d && d.success) {
                showMessage(manageMsg, '✅ 扩容请求已提交，正在自动跟踪状态...', true);
                ensureBootVolumeWatcher();
            } else {
                loadBootVolumes(true);
            }
        }

        // ==========================================
        // 🛡️ 配额锁定与网络修复
        // ==========================================
        function injectQuota() {
            if (!manageAccountSelect.value) return showMessage(manageMsg, '❌ 请先选择要操作的云账号', false);
            openConfirmModal("注入防扣费配额", `🛡️ <strong>安全警报！</strong><br>确定要为 [${esc(getDisplayAccountName(manageAccountSelect.value))}] 注入防扣费配额策略吗？<br>该操作将自动识别所在区域，封锁所有非免费的高计费云资源！`, "确认注入", false, async () => {
                showMessage(manageMsg, '⏳ 正在向 Oracle API 注入配额策略...', true);
                const data = await apiCall('quota/inject', { account: manageAccountSelect.value }, manageMsg);
                if (data.success) showMessage(manageMsg, '✅ 防扣费配额注入成功！该账户高收费资源已被彻底锁定。', true);
            });
        }

        function deleteQuota() {
            if (!manageAccountSelect.value) return showMessage(manageMsg, '❌ 请先选择要操作的云账号', false);
            openConfirmModal("解除配额锁定", `🔓 <strong>危险操作！</strong><br>确定要解除 [${esc(getDisplayAccountName(manageAccountSelect.value))}] 的防扣费配额吗？<br>解除锁定后，您将有可能产生高昂的云资源扣费！`, "确认解除", true, async () => {
                showMessage(manageMsg, '⏳ 正在删除配额策略...', true);
                const data = await apiCall('quota/delete', { account: manageAccountSelect.value }, manageMsg);
                if (data.success) showMessage(manageMsg, '✅ 防扣费配额已成功解除，资源限制已放开！', true);
            });
        }

        function fixRouteTable() {
            if (!manageAccountSelect.value) return showMessage(manageMsg, '❌ 请先选择要操作的云账号', false);
            openConfirmModal("一键修复路由表", `🚀 <strong>网络修复！</strong><br>确定要为 [${esc(getDisplayAccountName(manageAccountSelect.value))}] 的所有 VCN 执行一键修复吗？<br>该操作将自动检测并补齐缺失的 Internet Gateway、0.0.0.0/0 默认路由规则以及安全列表放行规则，以解决实例无法联网、被黑洞的问题。`, "确认修复", false, async () => {
                isFixingRoute.value = true;
                showMessage(manageMsg, '⏳ 正在执行路由网络修复，请稍候...', true);
                const data = await apiCall('route/fix', { account: manageAccountSelect.value }, manageMsg);
                if (data.success) showMessage(manageMsg, '✅ 一键修复完成！该账户的 VCN 路由及安全列表已全部打通。', true);
                isFixingRoute.value = false;
            });
        }

        async function loadQuotaTemplate() {
            isLoadingTemplate.value = true;
            const data = await apiCall('quota/template/get', {}, manageMsg);
            if (data.success) quotaTemplate.value = data.template;
            isLoadingTemplate.value = false;
        }

        async function saveQuotaTemplate() {
            if (!quotaTemplate.value) return showMessage(manageMsg, '❌ 模板内容不能为空', false);
            isSavingTemplate.value = true;
            const data = await apiCall('quota/template/save', { template: quotaTemplate.value }, manageMsg);
            if (data.success) showMessage(manageMsg, '✅ 自定义配额模板保存成功！', true);
            isSavingTemplate.value = false;
        }

        // ==========================================
        // 📥 文件上传与账号导入
        // ==========================================
        function triggerFileInput() {
            const el = document.getElementById('importKeyFile');
            if (el) el.click();
        }

        function processFile(file) {
            modals.import.fileName = file.name;
            const reader = new FileReader();
            reader.onload = (e) => {
                modals.import.fileText = e.target.result;
            };
            reader.readAsText(file);
        }

        function handleFileChange(e) {
            const file = e.target.files[0];
            if (file) processFile(file);
        }

        function handleFileDrop(e) {
            isDragging.value = false;
            const file = e.dataTransfer.files[0];
            if (file) processFile(file);
        }

        async function submitImport() {
            const m = modals.import;
            if (!m.accountName.trim()) return showMessage(importMsg, '❌ 请填写账号别名', false);
            if (!m.configText.trim()) return showMessage(importMsg, '❌ 请填写配置内容', false);
            if (!m.fileText || !m.fileText.trim()) return showMessage(importMsg, '❌ 请上传/拖拽私钥文件或粘贴私钥文本', false);

            m.isSubmitting = true;
            showMessage(importMsg, '⏳ 正在提交并后台预热...', true);
            const data = await apiCall('import-account', {
                accountName: m.accountName.trim(),
                configText: m.configText,
                privateKeyText: m.fileText.trim()
            }, importMsg);

            if (data.success) {
                showMessage(importMsg, '✅ ' + (data.msg || '导入成功！'), true);
                setTimeout(() => {
                    m.show = false;
                    m.accountName = ''; m.configText = ''; m.fileName = ''; m.fileText = ''; m.keyMode = 'file';
                    checkSession();
                }, 2000);
            }
            m.isSubmitting = false;
        }

        // ==========================================
        // 🗑️ 用户与账号销毁
        // ==========================================
        
        function deleteAccount(acc) {
            if (!acc) return;
            openDangerModal(
                "永久抹除云账号",
                `您正在执行高危操作：从系统中彻底移除账号 <strong>${esc(acc)}</strong>。<br>其私钥文件也会被销毁，<strong style="color:var(--danger)">不可恢复！</strong>`,
                "确认抹除",
                async () => {
                    showMessage(manageMsg, '⏳ 正在清理缓存和物理文件...', true);
                    const data = await apiCall('account-delete', { account: acc }, manageMsg);
                    if (data.success) {
                        showMessage(manageMsg, '✅ 账号已被彻底抹除！', true);
                        deleteAccountSelect.value = '';
                        if (manageAccountSelect.value === acc) {
                            manageAccountSelect.value = '';
                        }
                        checkSession();
                    }
                }
            );
        }

        // ==========================================
        // 🚨 通用危险/确认弹窗
        // ==========================================
        const copyState = ref('default'); // 'default' | 'success' | 'error'
        const dangerCopied = ref(false);
        let copyStateTimer = null;

        function openDangerModal(title, message, expectedKeyword, callback) {
            setEditing(true);
            copyState.value = 'default';
            dangerCopied.value = false;
            if (copyStateTimer) clearTimeout(copyStateTimer);
            modals.danger.title = title; modals.danger.message = message; modals.danger.expectedKeyword = expectedKeyword;
            modals.danger.inputKeyword = ''; modals.danger.actionCallback = callback; modals.danger.show = true;
        }

        function closeDangerModal() {
            modals.danger.show = false; setEditing(false); copyState.value = 'default'; dangerCopied.value = false;
            if (copyStateTimer) clearTimeout(copyStateTimer);
        }

        async function copyDangerKeyword() {
            const keyword = modals.danger.expectedKeyword;
            if (!keyword) return;
            modals.danger.inputKeyword = keyword;
            const ok = await copyToClipboard(keyword);
            copyState.value = ok ? 'success' : 'error';
            dangerCopied.value = ok;
            if (copyStateTimer) clearTimeout(copyStateTimer);
            copyStateTimer = setTimeout(() => {
                copyState.value = 'default';
                dangerCopied.value = false;
            }, 2000);
        }

        function executeDangerAction() {
            if (modals.danger.inputKeyword === modals.danger.expectedKeyword && modals.danger.actionCallback) {
                modals.danger.show = false; setEditing(false); modals.danger.actionCallback();
            }
        }

        function openConfirmModal(title, message, confirmText, isDanger, callback) {
            setEditing(true);
            modals.confirm.title = title; modals.confirm.message = message; modals.confirm.confirmText = confirmText || '确认';
            modals.confirm.isDanger = !!isDanger; modals.confirm.actionCallback = callback; modals.confirm.show = true;
        }

        function closeConfirmModal() {
            modals.confirm.show = false; setEditing(false);
        }

        function executeConfirmAction() {
            if (modals.confirm.actionCallback) {
                modals.confirm.show = false; setEditing(false); modals.confirm.actionCallback();
            }
        }

        // ==========================================
        // 🏷️ 账号标签与重命名
        // ==========================================
        const renameAccountPrompt = (oldAccount) => {
            const currentName = getDisplayAccountName(oldAccount);
            modals.renameAccount.oldAccount = oldAccount;
            modals.renameAccount.currentName = currentName;
            modals.renameAccount.newName = currentName;
            modals.renameAccount.show = true;
            setEditing(true);
        };

        const closeRenameAccountModal = () => {
            modals.renameAccount.show = false;
            setEditing(false);
        };

        const executeRenameAccount = async () => {
            const { oldAccount, currentName, newName } = modals.renameAccount;
            const finalName = newName.trim();
            if (!finalName || finalName === currentName) {
                closeRenameAccountModal();
                return;
            }

            try {
                const res = await apiCall('account-rename', { oldAccount, newAccount: finalName });
                if (res.success) {
                    showMessage(manageMsg, '账户名修改成功！', true);
                    const data = await apiCall('data');
                    if (data.success) {
                        applyServerData(data);
                    }
                } else {
                    showMessage(manageMsg, res.error || '修改失败', false);
                }
            } catch (e) {
                showMessage(manageMsg, '网络错误', false);
            }

            closeRenameAccountModal();
        };

        const tagAccountPrompt = (account) => {
            if (!account) return;
            const accObj = accountDetails.value.find(a => a.name === account);
            const rawTag = accObj ? (accObj.tag || '') : '';

            modals.tagAccount.account = account;
            modals.tagAccount.currentName = getDisplayAccountName(account);
            modals.tagAccount.tags = getAccountTags(rawTag);
            modals.tagAccount.icon = '🏷️';
            modals.tagAccount.text = '';
            modals.tagAccount.show = true;
            setEditing(true);
        };

        const closeTagAccountModal = () => {
            modals.tagAccount.show = false;
            setEditing(false);
        };

        const addTagToModal = (tagStr) => {
            if (!tagStr) return;
            const trimmed = tagStr.trim();
            if (trimmed && !modals.tagAccount.tags.includes(trimmed)) {
                modals.tagAccount.tags.push(trimmed);
            }
        };

        const removeTagFromModal = (index) => {
            if (index >= 0 && index < modals.tagAccount.tags.length) {
                modals.tagAccount.tags.splice(index, 1);
            }
        };

        const clearAllTagsFromModal = () => {
            modals.tagAccount.tags = [];
        };

        const addCustomTagFromModal = () => {
            const { icon, text } = modals.tagAccount;
            const cleanIcon = (icon || '').trim();
            const cleanText = (text || '').trim();
            let newTag = '';
            if (cleanIcon && cleanText) {
                newTag = cleanIcon + ' ' + cleanText;
            } else if (cleanText) {
                newTag = cleanText;
            } else if (cleanIcon) {
                newTag = cleanIcon;
            }
            if (newTag) {
                addTagToModal(newTag);
                modals.tagAccount.text = '';
            }
        };

        const setPresetTag = (icon, text) => {
            const tagStr = icon ? (text ? icon + ' ' + text : icon) : text;
            if (tagStr) {
                addTagToModal(tagStr);
            }
        };

        const executeTagAccount = async () => {
            if (modals.tagAccount.text && modals.tagAccount.text.trim()) {
                addCustomTagFromModal();
            }
            const { account, tags } = modals.tagAccount;
            const finalTag = (tags || []).join(', ');

            try {
                const res = await apiCall('account-tag', { account, tag: finalTag });
                if (res.success) {
                    const accObj = accountDetails.value.find(a => a.name === account);
                    if (accObj) {
                        accObj.tag = finalTag;
                    }
                    showMessage(manageMsg, '账户标签更新成功！', true);
                } else {
                    showMessage(manageMsg, res.error || '保存标签失败', false);
                }
            } catch (e) {
                showMessage(manageMsg, '保存标签异常: ' + e.message, false);
            } finally {
                closeTagAccountModal();
            }
        };





        // ==========================================
        // 🔔 站内消息与通知中心业务逻辑
        // ==========================================
        async function fetchNotifications(page = 0) {
            notificationLoading.value = true;
            try {
                const res = await apiCall('notifications/list', {
                    page: page,
                    size: 40,
                    category: notificationFilterCategory.value,
                    unreadOnly: notificationUnreadOnly.value
                });
                if (res && res.success) {
                    notificationsList.value = res.list || [];
                    unreadNoticeCount.value = res.unreadCount || 0;
                    notificationTotalCount.value = res.totalCount || 0;
                }
            } catch (e) {
            } finally {
                notificationLoading.value = false;
            }
        }

        function toggleNotificationDrawer() {
            notificationDrawerOpen.value = !notificationDrawerOpen.value;
            if (notificationDrawerOpen.value) {
                fetchNotifications(0);
            }
        }

        function closeNotificationDrawer() {
            notificationDrawerOpen.value = false;
        }

        async function markNoticeRead(notice) {
            if (!notice || notice.isRead) return;
            const res = await apiCall('notifications/read', { id: notice.id });
            if (res && res.success) {
                notice.isRead = true;
                if (res.unreadCount !== undefined) {
                    unreadNoticeCount.value = res.unreadCount;
                } else if (unreadNoticeCount.value > 0) {
                    unreadNoticeCount.value--;
                }
            }
        }

        async function markAllNoticesRead() {
            const res = await apiCall('notifications/read', { id: null });
            if (res && res.success) {
                (notificationsList.value || []).forEach(n => n.isRead = true);
                unreadNoticeCount.value = 0;
            }
        }

        async function clearNotices(type = 'read') {
            const res = await apiCall('notifications/clear', { type });
            if (res && res.success) {
                fetchNotifications(0);
            }
        }

        function setNotificationCategory(cat) {
            notificationFilterCategory.value = cat;
            fetchNotifications(0);
        }

        function toggleNotificationUnreadOnly() {
            notificationUnreadOnly.value = !notificationUnreadOnly.value;
            fetchNotifications(0);
        }

        function closeToastNotification(id) {
            toastNotifications.value = toastNotifications.value.filter(t => t.id !== id);
        }

        function formatNotificationContent(content) {
            if (!content) return '';
            let safe = content
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');
            safe = safe
                .replace(/&lt;code&gt;(.*?)&lt;\/code&gt;/gi, '<code class="notice-inline-code">$1</code>')
                .replace(/&lt;b&gt;(.*?)&lt;\/b&gt;/gi, '<b>$1</b>')
                .replace(/&lt;tg-spoiler&gt;(.*?)&lt;\/tg-spoiler&gt;/gi, '<span class="notice-spoiler">$1</span>')
                .replace(/\*\*(.*?)\*\*/g, '<b>$1</b>')
                .replace(/`([^`]+)`/g, '<code class="notice-inline-code">$1</code>')
                .replace(/\n/g, '<br>');
            return safe;
        }

        // ==========================================
        // 🚀 生命周期钩子与定时器注册
        // ==========================================

        let handleGlobalClick = null;

        onMounted(() => {
            document.getElementById('app-loader')?.remove();
            registerTimerCallbacks({ fetchTasks, loadBootVolumes });
            checkSession();
            const savedTheme = localStorage.getItem('oci-theme');
            if (savedTheme !== 'light') {
                isDarkTheme.value = true;
                document.documentElement.setAttribute('data-theme', 'dark');
            }
            handleGlobalClick = (e) => {
                if (isRegionDropdownOpen.value && !e.target.closest('.region-select-container')) {
                    isRegionDropdownOpen.value = false;
                }
            };
            document.addEventListener('click', handleGlobalClick);
        });

        onUnmounted(() => {
            clearAllTimers();
            if (handleGlobalClick) {
                document.removeEventListener('click', handleGlobalClick);
            }
        });

        // ==========================================
        // 📦 模板全量属性契约暴露 (161 项)
        // ==========================================
        return {
            esc, kpiStats,
            isInitializing, tgBotRunning, isDarkTheme, sessionToken, currentTab, isEditing,
            globalInstances, trafficData, tasksData, bootVolumes, globalAccounts, accountDetails,
            accountSortMode, accountTagFilter, sortedAccountDetails, trafficSortMode, trafficFilterMode, sortedTrafficData, trafficStats, isUpgradedAccount,
            loginUsername, loginCode, isSendingCode, isLoggingIn, isPasskeyLoggingIn, cooldown,
            networkAccount, manageAccountSelect, deleteAccountSelect, deleteAccountList, createAccountList, manageAccountList,
            quotaTemplate, isLoadingTemplate, isSavingTemplate, isSyncing, isSyncingAccounts, isTrafficLoading, trafficSyncing,
            isSavingLimit, isFetchingImages, isCreating, isFetchingBV, isFixingRoute,
            syncingOcids, enablingIpv6Ocids, disablingIpv6Ocids, loginMsg, sysMsg, createMsg, manageMsg, reinstallMsg, importMsg,
            createImages, reinstallImages, createForm, modals, isDragging, availableRegionsForSelectedAccount, isRegionDropdownOpen,
            groupedInstances, shortShape, getChangingStateText, getBootVolumeChangingStateText,
            serverSearchKeyword, isBootVolumeCollapsed,
            login, logout, sendAdminCode, switchTab, toggleTheme,
            loginWithPasskey, loginWithPasskeyFromModal, openPasskeyModal, closePasskeyModal, fetchPasskeys, registerPasskey, deletePasskey, goToTgLogin,
            syncServers, syncAccountsDetails, syncSingleServerStatus, confirmInstanceAction, confirmDeleteInstance,
            is500mEligible, is500mActive, enable500Mbps, disable500Mbps, enableAmd500Mbps, enableIpv6, disableIpv6,
            openRenameModal, confirmRenameInstance, openReinstallModal, confirmReinstall,
            openAllPorts, fixRouteTable, getDisplayAccountName, isTrafficError, getTrafficUsageText, getTrafficPercent,
            getTrafficColor, getTrafficCardStyle, getTrafficPercentText, getTrafficTimeText,
            loadTraffic, syncSingleTraffic, saveLimit, fetchImagesForCreate, genRandomPwd, generateSshKey,
            toggleRegionDropdown, closeRegionDropdown, selectRegion, onRegionSelectChange, openSubscribeRegionModal, confirmSubscribeRegion,
            submitCreationTask, confirmStopTask, confirmClearTask, loadBootVolumes, detachBootVolume, deleteBootVolume, openExpandVolumeModal, confirmExpandVolume,
            injectQuota, deleteQuota, loadQuotaTemplate, saveQuotaTemplate, setEditing,
            openDangerModal, closeDangerModal, executeDangerAction, copyDangerKeyword, copyState, dangerCopied, openConfirmModal, closeConfirmModal, executeConfirmAction,
            handleFileChange, handleFileDrop, triggerFileInput, submitImport, deleteAccount,
            onShapeChange, renameAccountPrompt, closeRenameAccountModal, executeRenameAccount,
            tagAccountPrompt, closeTagAccountModal, setPresetTag, executeTagAccount, availableTagIcons,
            getAccountTags, formatTagDisplay, addTagToModal, removeTagFromModal, clearAllTagsFromModal, addCustomTagFromModal,
            unreadNoticeCount, notificationDrawerOpen, notificationsList,
            notificationFilterCategory, notificationUnreadOnly, notificationTotalCount,
            notificationLoading, toastNotifications,
            toggleNotificationDrawer, closeNotificationDrawer, fetchNotifications,
            markNoticeRead, markAllNoticesRead, clearNotices, setNotificationCategory,
            toggleNotificationUnreadOnly, closeToastNotification, formatNotificationContent
        };
    }
});

app.mount('#app');
