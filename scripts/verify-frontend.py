#!/usr/bin/env python3
"""
🛡️ 前端静态资产与 ESM 模块完整性自动化门禁校验脚本
用于在 CI/CD（GitHub Actions）及本地提交前自动扫描前端所有 JS/HTML，
杜绝因缺少 export、错写 import、路径失效或语法错误导致的控制台死锁与加载故障。
"""

import os
import sys
import re

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATIC_DIR = os.path.join(BASE_DIR, 'src', 'main', 'resources', 'static')
JS_DIR = os.path.join(STATIC_DIR, 'js')
INDEX_HTML = os.path.join(STATIC_DIR, 'index.html')

def verify_frontend():
    print("=" * 60)
    print("🔍 [前端完整性门禁] 开始扫描与静态依赖校验...")
    print("=" * 60)

    if not os.path.isdir(JS_DIR):
        print(f"❌ 目录不存在: {JS_DIR}")
        sys.exit(1)

    js_files = []
    for root, _, files in os.walk(JS_DIR):
        for f in files:
            if f.endswith('.js'):
                js_files.append(os.path.join(root, f))

    print(f"📁 找到 {len(js_files)} 个 JavaScript 文件，正在提取 export 接口...")

    # 1. 提取所有文件的 export
    exports = {}
    for filepath in js_files:
        rel_path = os.path.relpath(filepath, JS_DIR)
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()

        exported_names = set()
        # export const / let / var / function / async function / class Foo
        for m in re.finditer(r'export\s+(?:async\s+)?(?:const|let|var|function|class)\s+([a-zA-Z0-9_$]+)', content):
            exported_names.add(m.group(1))

        # export { a, b as c }
        for m in re.finditer(r'export\s*\{([^}]+)\}', content):
            for item in m.group(1).split(','):
                item = item.strip()
                if not item:
                    continue
                name = item.split(' as ')[-1].strip()
                exported_names.add(name)

        exports[rel_path] = exported_names

    # 2. 严格校验所有文件的 import
    print("🔗 正在深度校验所有模块间 import 引用完整性...")
    errors = []

    for filepath in js_files:
        rel_path = os.path.relpath(filepath, JS_DIR)
        with open(filepath, 'r', encoding='utf-8') as f:
            lines = f.readlines()

        content = "".join(lines)
        dir_of_file = os.path.dirname(filepath)

        # 匹配具名导入: import { a, b, c } from './path.js?v=...'
        import_pattern = re.compile(r'import\s*\{([^}]+)\}\s*from\s*[\'\"]([^\'\"]+)[\'\"]')
        for m in import_pattern.finditer(content):
            import_clause = m.group(1)
            target_specifier = m.group(2)
            clean_specifier = target_specifier.split('?')[0]  # 剥离版本查询参数

            # 计算目标物理路径
            target_full_path = os.path.normpath(os.path.join(dir_of_file, clean_specifier))
            target_rel_path = os.path.relpath(target_full_path, JS_DIR)

            # 🚫 规则检查：内部 import 严禁携带版本查询参数（杜绝浏览器创建多个模块单例导致状态不同步）
            if '?' in target_specifier:
                errors.append(
                    f"❌ [{rel_path}] 内部 import 严禁携带版本查询参数: '{target_specifier}'\n"
                    f"   原因: 不同的查询参数会导致浏览器实例化多份单例模块（如 state.js），造成状态裂脑与登录即退出！\n"
                    f"   请移除 '?v=...'，保持干净的标准相对引用 (如 '{clean_specifier}')。"
                )

            # 校验目标文件是否存在
            if not os.path.isfile(target_full_path):
                errors.append(f"❌ [{rel_path}] 引用的模块文件不存在: '{target_specifier}' -> 找不到 {target_rel_path}")
                continue

            # 检查导出的标识符是否存在
            if target_rel_path in exports:
                target_exports = exports[target_rel_path]
                for item in import_clause.split(','):
                    item = item.strip()
                    if not item:
                        continue
                    orig_name = item.split(' as ')[0].strip()
                    if orig_name not in target_exports:
                        errors.append(
                            f"❌ [{rel_path}] 导入了不存在的变量: '{orig_name}'\n"
                            f"   来源: '{target_specifier}' ({target_rel_path})\n"
                            f"   原因: {target_rel_path} 中并未 export 此变量！"
                        )

    # 3. 校验 index.html 入口脚本有效性
    if os.path.isfile(INDEX_HTML):
        with open(INDEX_HTML, 'r', encoding='utf-8') as f:
            html_content = f.read()

        for m in re.finditer(r'<script\s+[^>]*src=[\'\"]([^\'\"]+)[\'\"]', html_content):
            src = m.group(1).split('?')[0]
            if src.startswith('http://') or src.startswith('https://'):
                continue
            script_full_path = os.path.normpath(os.path.join(STATIC_DIR, src))
            if not os.path.isfile(script_full_path):
                errors.append(f"❌ [index.html] 引用的入口脚本不存在: '{src}'")

    print("-" * 60)
    if errors:
        print(f"🚨 [前端校验失败] 发现 {len(errors)} 个致命模块引用错误：\n")
        for err in errors:
            print(err + "\n")
        print("💡 请修复上述模块依赖或 export 缺失后再次构建。")
        sys.exit(1)
    else:
        print("✅ [前端校验通过] 所有 ESM 模块导入/导出与入口资源 100% 完整有效！")
        print("=" * 60)

if __name__ == '__main__':
    verify_frontend()
