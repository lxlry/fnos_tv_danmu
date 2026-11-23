@echo off
cd /d "%~dp0"
py -3 -m pip install -r requirements.txt
if errorlevel 1 exit /b 1
py -3 fetch_mpv.py
if errorlevel 1 exit /b 1
py -3 -m PyInstaller --noconfirm FNTV.spec
if errorlevel 1 exit /b 1
if exist dist\FNTV\mpv rmdir /s /q dist\FNTV\mpv
xcopy /E /I /Y vendor\mpv dist\FNTV\mpv >nul
if not exist dist\FNTV\mpv\mpv.exe (
  echo 没有把 mpv 放进安装目录
  exit /b 1
)
py -3 pack_sfx.py
if errorlevel 1 exit /b 1
echo.
echo 已生成自解压包，文件名见 dist\FNTV_windows_v*.exe
echo 双击后会解压到本地目录并启动，不用单独安装 mpv
