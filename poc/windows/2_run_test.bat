@echo off
rem koja Windows test. Messages are printed by Python (Korean). Keep this file ASCII.
rem uv, Python and caches all live inside this folder (no PATH change). Deleting the folder removes everything.
chcp 65001 >nul
setlocal
cd /d "%~dp0"
set "PYTHONUTF8=1"
set "UV_LINK_MODE=copy"
set "UV_INSTALL_DIR=%~dp0tools\uv"
set "UV_NO_MODIFY_PATH=1"
set "UV_CACHE_DIR=%~dp0tools\uv-cache"
set "UV_PYTHON_INSTALL_DIR=%~dp0tools\python"
set "UV=%UV_INSTALL_DIR%\uv.exe"
if exist "%UV%" goto have_uv
echo.
echo [ERROR] Setup is not finished. Run 1_setup.bat first.
pause
exit /b 1
:have_uv
"%UV%" run --project poc python -X utf8 poc\win_run.py %*
echo.
pause
