// 📦 全局响应式状态与数据流中心 (Single Source of Truth)
import { ref, reactive, computed, watch } from '../vendor/vue.esm-browser.prod.js';
import { genRandomPwd, getDisplayAccountName } from '../utils/helpers.js';

// 认证与系统状态
export const isInitializing = ref(true);
export const tgBotRunning = ref(false);
export const isDarkTheme = ref(false);
const SESSION_KEY = 'oci_session_token';

function readStoredSessionToken() {
    let token = '';
    try {
        token = sessionStorage.getItem(SESSION_KEY) || '';
        const legacy = localStorage.getItem(SESSION_KEY) || '';
        if (!token && legacy) {
            sessionStorage.setItem(SESSION_KEY, legacy);
            token = legacy;
        }
        if (legacy) localStorage.removeItem(SESSION_KEY);
    } catch (e) {}
    return token;
}

export function persistSessionToken(token) {
    try {
        if (token) sessionStorage.setItem(SESSION_KEY, token);
        else sessionStorage.removeItem(SESSION_KEY);
        localStorage.removeItem(SESSION_KEY);
    } catch (e) {}
}

try {
    const params = new URLSearchParams(window.location.search);
    if (params.has('token')) {
        params.delete('token');
        const next = params.toString();
        const path = window.location.pathname + (next ? '?' + next : '') + window.location.hash;
        history.replaceState(null, '', path);
    }
} catch (e) {}
export const sessionToken = ref(readStoredSessionToken());
export const currentTab = ref('auth');
export const isEditing = ref(false);

// 云资源数据核心集合
export const globalInstances = ref({});
export const trafficData = ref({});
export const tasksData = ref({});
export const bootVolumes = ref([]);
export const globalAccounts = ref([]);
export const accountDetails = ref([]);

// 排序与筛选持久化
const validAccountSorts = ['add_time_asc', 'add_time_desc', 'reg_time_desc', 'reg_time_asc', 'type', 'tag'];
const savedAccountSort = localStorage.getItem('oci_account_sort_mode');
export const accountSortMode = ref(validAccountSorts.includes(savedAccountSort) ? savedAccountSort : 'add_time_asc');
export const accountTagFilter = ref('all');

const validTrafficSorts = ['default', 'usage_desc', 'usage_asc', 'percent_desc', 'type'];
const savedTrafficSort = localStorage.getItem('oci_traffic_sort_mode');
export const trafficSortMode = ref(validTrafficSorts.includes(savedTrafficSort) ? savedTrafficSort : 'default');

const validTrafficFilters = ['all', 'payg', 'free'];
const savedTrafficFilter = localStorage.getItem('oci_traffic_filter_mode');
export const trafficFilterMode = ref(validTrafficFilters.includes(savedTrafficFilter) ? savedTrafficFilter : 'all');

watch(accountSortMode, (newVal) => {
    if (newVal) localStorage.setItem('oci_account_sort_mode', newVal);
});

watch(trafficSortMode, (newVal) => {
    if (newVal) localStorage.setItem('oci_traffic_sort_mode', newVal);
});

watch(trafficFilterMode, (newVal) => {
    if (newVal) localStorage.setItem('oci_traffic_filter_mode', newVal);
});

// 登录与验证码
export const loginUsername = ref(localStorage.getItem('oci_admin_username') || 'admin');
export const loginCode = ref('');
export const isSendingCode = ref(false);
export const isLoggingIn = ref(false);
export const isPasskeyLoggingIn = ref(false);
export const cooldown = ref(0);

// 选择器绑定
export const networkAccount = ref('');
export const manageAccountSelect = ref('');
export const deleteAccountSelect = ref('');

// 搜索与折叠状态
export const serverSearchKeyword = ref('');
export const isBootVolumeCollapsed = ref(false);

// 配额模板与状态
export const quotaTemplate = ref('');
export const isLoadingTemplate = ref(false);
export const isSavingTemplate = ref(false);

// 各种网络请求/同步状态
export const isSyncing = ref(false);
export const isSyncingAccounts = ref(false);
export const isTrafficLoading = ref(false);
export const trafficSyncing = reactive({});
export const isSavingLimit = ref(false);
export const isFetchingImages = ref(false);
export const isCreating = ref(false);
export const isFetchingBV = ref(false);
export const isFixingRoute = ref(false);

// 细粒度操作标记
export const syncingOcids = ref({});
export const enablingIpv6Ocids = ref({});
export const disablingIpv6Ocids = ref({});

// 消息反馈响应式对象
const createMsgObject = () => reactive({ text: '', isSuccess: true });
export const loginMsg = createMsgObject();
export const sysMsg = createMsgObject();
export const createMsg = createMsgObject();
export const manageMsg = createMsgObject();
export const reinstallMsg = createMsgObject();
export const importMsg = createMsgObject();

// 镜像列表
export const createImages = ref([]);
export const reinstallImages = ref([]);

// 实例创建表单
export const createForm = reactive({
    account: '', region: '', shape: 'VM.Standard.A1.Flex', os: '', instanceName: '',
    cpu: 2, ram: 12, disk: 50, count: 1, interval: 90,
    ipv4: true, ipv6: false, authType: 'password', rootLogin: true, rootPwd: genRandomPwd(), sshKey: ''
});

// 拖拽导入状态
export const isDragging = ref(false);

// 模态弹窗统一状态树
export const modals = reactive({
    tagAccount: { show: false, account: '', currentName: '', tags: [], icon: '🏷️', text: '' },
    renameAccount: { show: false, oldAccount: '', currentName: '', newName: '' },
    rename: { show: false, ocid: '', oldName: '', newName: '' },
    expandVolume: { show: false, bv: null, newSize: '' },
    reinstall: { show: false, ocid: '', targetName: '', type: 'auto', os: '', customOcid: '', newInstanceName: '', authType: 'password', rootLogin: true, rootPwd: genRandomPwd(), sshKey: '', confirmStage: false },
    import: { show: false, accountName: '', configText: '', fileText: '', fileName: '', isSubmitting: false, keyMode: 'file' },
    danger: { show: false, title: '', message: '', expectedKeyword: '', inputKeyword: '', actionCallback: null },
    confirm: { show: false, title: '', message: '', confirmText: '确认', isDanger: false, actionCallback: null },
    passkey: { show: false, list: [], isLoading: false, isRegistering: false, newName: '', stepUpSecret: '', msg: { text: '', isSuccess: true } },
    subscribeRegion: {
        show: false,
        account: '',
        homeRegion: '',
        tenancyId: '',
        subscribedRegions: [],
        allRegions: [],
        filterText: '',
        selectedRegion: '',
        customRegion: '',
        isCustom: false,
        isLoading: false,
        isSubmitting: false,
        msg: { text: '', isSuccess: true }
    }
});

// 下拉区域菜单展开状态
export const isRegionDropdownOpen = ref(false);

// ==========================================
// 💡 全局计算属性 (Computed Properties)
// ==========================================

export const availableRegionsForSelectedAccount = computed(() => {
    if (!createForm.account) return [];
    const accDetail = accountDetails.value.find(a => a.name === createForm.account);
    if (accDetail && accDetail.subscribedRegions) {
        const list = accDetail.subscribedRegions.split(',').map(r => r.trim()).filter(Boolean);
        if (list.length > 0) return list;
    }
    if (accDetail && accDetail.region && accDetail.region !== '未知') {
        return [accDetail.region];
    }
    return [];
});


export const kpiStats = computed(() => {
    const insts = Object.values(globalInstances.value || {});
    const running = insts.filter(i => i && i.state === 'RUNNING').length;
    let traffic = 0;
    for (const info of Object.values(trafficData.value || {})) {
        if (info && info.usage_gb > 0) traffic += info.usage_gb;
    }
    let bill = 0, banned = 0, payg = 0;
    let billCalculating = false;
    for (const acc of (accountDetails.value || [])) {
        if (acc.billAmount === '自动计算中...') {
            billCalculating = true;
        }
        const m = String(acc.billAmount || '').match(/([0-9]+(?:\.[0-9]+)?)/);
        if (m) bill += parseFloat(m[1]);
        if (String(acc.isBanned) === 'true') banned++;
        if (String(acc.isPayg) === 'true') payg++;
    }
    return {
        total: insts.length, running,
        traffic: traffic.toFixed(1),
        bill: bill.toFixed(2),
        billCalculating,
        banned, payg,
        accounts: (accountDetails.value || []).length
    };
});

export const groupedInstances = computed(() => {
    const groups = {};
    const kw = (serverSearchKeyword.value || '').trim().toLowerCase();

    for (const [ocid, info] of Object.entries(globalInstances.value)) {
        const acc = info.account || '未知归属账号';

        let cleanName = info.name || '';
        if (cleanName.startsWith(`[${acc}] `)) cleanName = cleanName.replace(`[${acc}] `, '');
        else if (cleanName.startsWith(`${acc} `)) cleanName = cleanName.replace(`${acc} `, '');

        const fmtSpec = v => { const n = parseFloat(v); return isNaN(n) ? String(v) : String(n % 1 === 0 ? Math.round(n) : n); };
        let specs = [];
        if (info.cpu) specs.push(fmtSpec(info.cpu) + 'C');
        if (info.memory) specs.push(fmtSpec(info.memory) + 'G');
        if (info.disk) specs.push(fmtSpec(info.disk) + 'GB');
        const shapeInfo = specs.length > 0 ? specs.join(' / ') : (info.shape || '');
        const shapeFull = (info.shape || '') + (specs.length > 0 ? ` (${specs.join(' / ')})` : '');

        // 🔍 关键词搜索过滤 (匹配名称、账号、IP、IPv6、区域、地域、规格、状态、OCID等)
        if (kw) {
            const matchName = cleanName.toLowerCase().includes(kw) || (info.name || '').toLowerCase().includes(kw);
            const matchAccount = acc.toLowerCase().includes(kw);
            const matchIp = (info.ip || '').toLowerCase().includes(kw);
            const matchIpv6 = (info.ipv6 || '').toLowerCase().includes(kw);
            const matchRegion = (info.region || '').toLowerCase().includes(kw);
            const matchShape = (shapeFull || '').toLowerCase().includes(kw) || (info.shape || '').toLowerCase().includes(kw);
            const matchState = (info.state || '').toLowerCase().includes(kw);
            const matchLocation = (info.location || '').toLowerCase().includes(kw);
            const matchOcid = (ocid || '').toLowerCase().includes(kw);

            if (!matchName && !matchAccount && !matchIp && !matchIpv6 && !matchRegion && !matchShape && !matchState && !matchLocation && !matchOcid) {
                continue;
            }
        }

        if (!groups[acc]) groups[acc] = [];
        groups[acc].push({ ...info, ocid: ocid, cleanName: cleanName, shapeInfo: shapeInfo, shapeFull: shapeFull });
    }
    const sorted = {};
    Object.keys(groups).sort().forEach(k => { sorted[k] = groups[k]; });
    return sorted;
});

export const sortedAccountDetails = computed(() => {
    let list = [...accountDetails.value];

    if (accountTagFilter.value !== 'all') {
        if (accountTagFilter.value === 'untagged') {
            list = list.filter(acc => !acc.tag || !acc.tag.trim());
        } else {
            list = list.filter(acc => acc.tag && acc.tag.includes(accountTagFilter.value));
        }
    }

    if (accountSortMode.value === 'add_time_desc') {
        list.sort((a, b) => (b._orderIndex ?? 0) - (a._orderIndex ?? 0));
    } else if (accountSortMode.value === 'add_time_asc') {
        list.sort((a, b) => (a._orderIndex ?? 0) - (b._orderIndex ?? 0));
    } else if (accountSortMode.value === 'reg_time_desc' || accountSortMode.value === 'reg_time_asc') {
        list.sort((a, b) => {
            const getT = (tStr) => {
                if (!tStr || tStr === '未记录' || tStr === '自动抓取中...') return 0;
                return new Date(tStr).getTime() || 0;
            };
            const tA = getT(a.registerTime);
            const tB = getT(b.registerTime);
            if (tA !== tB) {
                return accountSortMode.value === 'reg_time_desc' ? tB - tA : tA - tB;
            }
            return (a._orderIndex ?? 0) - (b._orderIndex ?? 0);
        });
    } else if (accountSortMode.value === 'type') {
        const getScore = (acc) => {
            if (acc.isBanned === 'true') return 3;
            if (acc.isPayg === 'true') return 1;
            return 2;
        };
        list.sort((a, b) => {
            const diff = getScore(a) - getScore(b);
            if (diff !== 0) return diff;
            return (a._orderIndex ?? 0) - (b._orderIndex ?? 0);
        });
    } else if (accountSortMode.value === 'tag') {
        list.sort((a, b) => {
            const cmp = (a.tag || '').localeCompare(b.tag || '', 'zh-CN');
            if (cmp !== 0) return cmp;
            return (a._orderIndex ?? 0) - (b._orderIndex ?? 0);
        });
    }
    return list;
});

export const addTimeDescAccountList = computed(() => {
    if (!globalAccounts.value || globalAccounts.value.length === 0) return [];
    const orderMap = new Map();
    if (accountDetails.value && accountDetails.value.length > 0) {
        accountDetails.value.forEach((acc, idx) => {
            const idxVal = (typeof acc._orderIndex === 'number') ? acc._orderIndex : idx;
            orderMap.set(acc.name, idxVal);
            if (acc.name) {
                orderMap.set(acc.name.trim(), idxVal);
                orderMap.set(acc.name.replaceAll(/["'\s\[\]\r\n]/g, ''), idxVal);
            }
        });
    }
    const list = [...globalAccounts.value];
    list.sort((a, b) => {
        const cleanA = a ? a.replaceAll(/["'\s\[\]\r\n]/g, '') : '';
        const cleanB = b ? b.replaceAll(/["'\s\[\]\r\n]/g, '') : '';
        const orderA = orderMap.has(a) ? orderMap.get(a) : (orderMap.has(cleanA) ? orderMap.get(cleanA) : globalAccounts.value.indexOf(a));
        const orderB = orderMap.has(b) ? orderMap.get(b) : (orderMap.has(cleanB) ? orderMap.get(cleanB) : globalAccounts.value.indexOf(b));
        return orderB - orderA;
    });
    return list;
});

export const deleteAccountList = addTimeDescAccountList;
export const createAccountList = addTimeDescAccountList;
export const manageAccountList = addTimeDescAccountList;

export function isUpgradedAccount(accName, info) {
    if (info && (info.isPayg === true || String(info.isPayg).toLowerCase() === 'true')) return true;
    const cleanName = getDisplayAccountName(accName);
    const detail = (accountDetails.value || []).find(a => 
        a.name === accName || 
        getDisplayAccountName(a.name) === cleanName ||
        (a.name && cleanName && (a.name.toLowerCase() === cleanName.toLowerCase()))
    );
    if (detail && (detail.isPayg === true || String(detail.isPayg).toLowerCase() === 'true')) {
        return true;
    }
    if (info && (info.isPayg === false || String(info.isPayg).toLowerCase() === 'false')) return false;
    if (detail && (detail.isPayg === false || String(detail.isPayg).toLowerCase() === 'false')) return false;
    if (accName) {
        const lower = String(accName).toLowerCase();
        if (lower.includes('payg') || lower.includes('升级') || lower.includes('付费')) return true;
    }
    return false;
}

export const trafficStats = computed(() => {
    const all = Object.entries(trafficData.value || {});
    let payg = 0;
    let free = 0;
    all.forEach(([acc, info]) => {
        if (isUpgradedAccount(acc, info)) payg++;
        else free++;
    });
    return {
        total: all.length,
        payg,
        free
    };
});

export const sortedTrafficData = computed(() => {
    let list = Object.entries(trafficData.value || {}).map(([acc, info]) => ({ acc, info }));

    if (trafficFilterMode.value === 'payg') {
        list = list.filter(item => isUpgradedAccount(item.acc, item.info));
    } else if (trafficFilterMode.value === 'free') {
        list = list.filter(item => !isUpgradedAccount(item.acc, item.info));
    }

    if (trafficSortMode.value === 'usage_desc') {
        list.sort((a, b) => (b.info.usage_gb || 0) - (a.info.usage_gb || 0));
    } else if (trafficSortMode.value === 'usage_asc') {
        list.sort((a, b) => (a.info.usage_gb || 0) - (b.info.usage_gb || 0));
    } else if (trafficSortMode.value === 'percent_desc') {
        const getP = (i) => {
            if (i.info.error) return -1;
            if (!i.info.limit_gb || i.info.limit_gb <= 0) return 0;
            return (i.info.usage_gb / i.info.limit_gb) * 100;
        };
        list.sort((a, b) => getP(b) - getP(a));
    } else if (trafficSortMode.value === 'type') {
        list.sort((a, b) => {
            const aPayg = isUpgradedAccount(a.acc, a.info) ? 1 : 2;
            const bPayg = isUpgradedAccount(b.acc, b.info) ? 1 : 2;
            if (aPayg !== bPayg) return aPayg - bPayg;
            return (b.info.usage_gb || 0) - (a.info.usage_gb || 0);
        });
    }
    return list;
});

// 🔔 站内消息与通知中心响应式状态
export const unreadNoticeCount = ref(0);
export const notificationDrawerOpen = ref(false);
export const notificationsList = ref([]);
export const notificationFilterCategory = ref('ALL');
export const notificationUnreadOnly = ref(false);
export const notificationTotalCount = ref(0);
export const notificationLoading = ref(false);
export const toastNotifications = ref([]);
