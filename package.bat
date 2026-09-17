@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo ============================================================
echo   MCP Debugger    ^|  JetBrains 插件一键打包
echo ============================================================
echo.

REM ============================================================
REM  用法:
REM    直接双击本文件，或在命令行执行  package.bat
REM
REM  可选环境变量:
REM    MCP_JDK   指定 JDK 17+ 的目录（优先级最高）
REM                  例: set MCP_JDK=C:\Program Files\Java\jdk-17
REM
REM  成功后插件安装包位于:
REM    build\distributions\mcp-debug-idea-plugin-版本号.zip
REM
REM  维护须知: 本文件必须以 ANSI/GBK 编码 + CRLF 换行保存。
REM            若存成 UTF-8，cmd 会按 GBK 解码，中文注释里的多字节
REM            序列会吞掉紧随其后的字符，整份脚本将被解析成无效命令；
REM            若存成 LF 换行，cmd 的按行解析同样会错乱。
REM            另外 REM 注释里不要出现小于号或大于号，会被当成重定向。
REM ============================================================

REM ---------------- 1. 定位 JDK 17 及以上 ----------------
set "JDK="

if defined MCP_JDK (
    call :check_jdk "%MCP_JDK%"
    if defined JDK_OK set "JDK=%MCP_JDK%"
)

if not defined JDK if defined JAVA_HOME (
    call :check_jdk "%JAVA_HOME%"
    if defined JDK_OK (
        set "JDK=%JAVA_HOME%"
        echo [提示] 使用 JAVA_HOME 指向的 JDK !JDK_MAJOR!。
    )
)

REM 常见安装位置: 目录名带版本号，必须用 for /d 枚举，
REM 通配符写在路径中间时 if exist 匹配不到（如 jdk-17* 这种写法无效）
if not defined JDK (
    for %%R in (
        "%ProgramFiles%\Java"
        "%ProgramFiles%\Eclipse Adoptium"
        "%ProgramFiles%\Eclipse Temurin"
        "%ProgramFiles%\Microsoft"
        "%ProgramFiles%\Amazon Corretto"
        "%ProgramFiles%\Zulu"
        "%ProgramFiles%\BellSoft"
        "%ProgramFiles%\Semeru"
    ) do (
        if not defined JDK (
            for /d %%D in ("%%~R\*") do (
                if not defined JDK (
                    call :check_jdk "%%~D"
                    if defined JDK_OK set "JDK=%%~D"
                )
            )
        )
    )
)

if not defined JDK (
    echo [错误] 未找到 JDK 17 或更高版本。
    echo.
    echo  本插件需要 JDK 17+ 构建，当前 JAVA_HOME=%JAVA_HOME%
    echo.
    echo  解决办法: 安装 JDK 17，或手动指定安装目录后重试，例如:
    echo      set MCP_JDK=C:\Program Files\Java\jdk-17
    echo      package.bat
    echo.
    exit /b 1
)

echo [1/3] 使用 JDK: !JDK!  ^(版本 !JDK_MAJOR!^)
set "JAVA_HOME=!JDK!"
set "PATH=!JDK!\bin;%PATH%"

REM 网络不稳定时强制走 IPv4，避免下载依赖超时
set "JAVA_OPTS=%JAVA_OPTS% -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8"

REM ---------------- 2. 选择 Gradle ----------------
REM 优先使用本地独立 Gradle 发行版（免下载），否则回退到 Gradle Wrapper
set "GRADLE_CMD="
if defined GRADLE_HOME if exist "%GRADLE_HOME%\bin\gradle.bat" set "GRADLE_CMD=%GRADLE_HOME%\bin\gradle.bat"

if not defined GRADLE_CMD (
    for /d %%G in ("%USERPROFILE%\gradle-dist\gradle-*") do (
        if not defined GRADLE_CMD if exist "%%G\bin\gradle.bat" set "GRADLE_CMD=%%G\bin\gradle.bat"
    )
)

if defined GRADLE_CMD (
    echo [2/3] 使用本地 Gradle: !GRADLE_CMD!
) else (
    echo [2/3] 使用 Gradle Wrapper ^(首次运行需联网下载 Gradle，请耐心等待^)
    set "GRADLE_CMD=%~dp0gradlew.bat"
)

if not exist "!GRADLE_CMD!" (
    echo.
    echo [错误] 找不到 Gradle 执行文件: !GRADLE_CMD!
    echo.
    exit /b 1
)

REM ---------------- 3. 打包 ----------------
echo [3/3] 开始构建，首次构建会下载 IntelliJ Platform SDK 与依赖，可能耗时较久...
echo.

call "!GRADLE_CMD!" buildPlugin --console=plain
set "RC=!ERRORLEVEL!"

if not "!RC!"=="0" (
    echo.
    echo [错误] 打包失败（退出码 !RC!）。请查看上方日志。
    echo        若是下载依赖超时，可重试一次；或预先用迅雷/浏览器下载
    echo        gradle-wrapper.properties 中的 Gradle 包并放入 %USERPROFILE%\.gradle\wrapper\dists
    echo.
    exit /b !RC!
)

echo.
echo ============================================================
echo   打包成功
echo ============================================================
echo.

set "FOUND="
for %%F in ("%~dp0build\distributions\*.zip") do (
    if exist "%%F" (
        echo   安装包: %%~fF
        set "FOUND=1"
    )
)

if not defined FOUND (
    echo   [警告] 未在 build\distributions 下找到 zip，请检查构建日志。
    echo.
    exit /b 1
)

echo.
echo   安装方式: IDE 打开 Settings / Preferences -^> Plugins
echo             -^> 齿轮图标 -^> Install Plugin from Disk... 选择上面的 zip
echo.

REM 打开产物所在目录，方便直接取包
start "" "%~dp0build\distributions"

endlocal
exit /b 0

REM ============================================================
REM  子过程: 检查目录是否为 JDK 17+，是则设置 JDK_OK / JDK_MAJOR
REM ============================================================
:check_jdk
set "JDK_OK="
set "JDK_MAJOR="
set "JDK_VER="
if not exist "%~1\bin\javac.exe" goto :eof
if not exist "%~1\release" goto :eof
for /f "usebackq tokens=2 delims==" %%a in (`findstr /b "JAVA_VERSION=" "%~1\release" 2^>nul`) do set "JDK_VER=%%~a"
if not defined JDK_VER goto :eof
for /f "tokens=1 delims=." %%a in ("!JDK_VER!") do set "JDK_MAJOR=%%a"
if !JDK_MAJOR! GEQ 17 set "JDK_OK=1"
goto :eof
