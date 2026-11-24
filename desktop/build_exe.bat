@echo off
cd /d "%~dp0"
py -3 -m pip install -r requirements.txt pillow
if errorlevel 1 exit /b 1
py -3 make_icon.py
if errorlevel 1 exit /b 1
py -3 fetch_mpv.py
if errorlevel 1 exit /b 1
py -3 fetch_uosc.py
if errorlevel 1 exit /b 1
py -3 -m PyInstaller --noconfirm FNTV.spec
if errorlevel 1 exit /b 1
if exist dist\FNTV\mpv rmdir /s /q dist\FNTV\mpv
xcopy /E /I /Y vendor\mpv dist\FNTV\mpv >nul
if not exist dist\FNTV\mpv\mpv.exe (
  echo 没有把 mpv 放进安装目录
  exit /b 1
)
if exist dist\FNTV\uosc rmdir /s /q dist\FNTV\uosc
xcopy /E /I /Y vendor\uosc dist\FNTV\uosc >nul
if not exist dist\FNTV\uosc\scripts\uosc\main.lua (
  echo 没有把播放器界面放进安装目录
  exit /b 1
)
py -3 pack_sfx.py
if errorlevel 1 exit /b 1
echo.
echo 已生成安装程序，文件名见 dist\FNTV_windows_v*.exe
echo 安装时可选择目录，并可在系统“应用”里卸载
