(function () {
    var loader = document.getElementById('app-loader');
    if (!loader) return;
    var sub = loader.querySelector('.app-loader-sub');

    function actionButton(label, background, clearStorage) {
        var button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.style.cssText = 'padding:6px 14px;background:' + background + ';color:#fff;border:none;border-radius:6px;cursor:pointer;font-size:12px;font-weight:600;';
        button.addEventListener('click', function () {
            if (clearStorage) {
                try { localStorage.clear(); sessionStorage.clear(); } catch (e) {}
            }
            location.reload();
        });
        return button;
    }

    function showFallback(title, titleColor, detail) {
        if (!document.getElementById('app-loader') || !sub) return;
        sub.textContent = '';
        var heading = document.createElement('span');
        heading.style.color = titleColor;
        heading.style.fontWeight = '700';
        heading.textContent = title;
        sub.appendChild(heading);
        if (detail) {
            var box = document.createElement('div');
            box.style.cssText = 'font-size:11.5px;color:#94a3b8;margin:8px auto;max-width:360px;line-height:1.4;word-break:break-all;background:rgba(0,0,0,0.3);padding:8px;border-radius:6px;border:1px solid rgba(239,68,68,0.2);';
            box.textContent = detail;
            sub.appendChild(box);
        }
        var row = document.createElement('div');
        row.style.cssText = 'margin-top:12px;display:flex;gap:8px;justify-content:center;';
        row.appendChild(actionButton(detail ? '立即重试' : '强制刷新', detail ? '#ef4444' : '#3b82f6', false));
        row.appendChild(actionButton(detail ? '清缓存重试' : '清除缓存重载', '#475569', true));
        sub.appendChild(row);
    }

    var timeoutTimer = setTimeout(function () {
        showFallback('⚠️ 控制台加载超时或受浏览器缓存影响', '#f59e0b', '');
    }, 6000);

    window.addEventListener('error', function (e) {
        clearTimeout(timeoutTimer);
        var errorMsg = (e && e.message) ? e.message : '前端模块依赖解析或语法错误';
        showFallback('❌ 控制台模块加载失败', '#ef4444', errorMsg);
    });
})();
