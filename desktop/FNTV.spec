# -*- mode: python ; coding: utf-8 -*-
# 在 desktop 目录执行: py -3 -m PyInstaller --noconfirm FNTV.spec

import importlib.util

datas = [("fntv_desktop/web", "fntv_desktop/web"), ("version.json", ".")]
hidden = []
if importlib.util.find_spec("webview"):
    from PyInstaller.utils.hooks import collect_data_files, collect_submodules
    datas += collect_data_files("webview")
    hidden += collect_submodules("webview")

a = Analysis(
    ["main.py"],
    pathex=[],
    binaries=[],
    datas=datas,
    hiddenimports=hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="FNTV",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="FNTV",
)
