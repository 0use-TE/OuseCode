# OpenCode Android

[English](README.en.md)

独立 Android 应用：内置 PRoot、Ubuntu 24.04.5 ARM64、Node.js 24.22.1、Git 和 OpenCode 2.3.2，在 WebView 中使用 OpenCode 自带的智能体界面。无需 Termux、电脑服务器或 Android Root。

应用显示名称为 OpenCode，内部工程目录仍为 OuseCode。桌面图标采用 OpenCode 官方深色标志，保留原始比例和灰白配色，并适配 Android 图标形状。图形来源见 `artwork/opencode-logo-dark.svg`；这是独立 Android 宿主项目。

首次启动解压运行环境；之后复用已安装环境。AI 模型调用、安装依赖和在线更新需要网络。应用有独立包名 `io.github.ouse.ousecode`，可以与 DSH Ha 同时安装。

## 使用

1. 安装 `dist/OpenCode-0.2.5-arm64.apk`，打开 OpenCode，等待首次解压。应用会通过官方一次性配对流程自动连接内置 OpenCode，不需要手填服务器地址。
2. 在 OpenCode 网页中选择项目并配置模型。默认项目目录为 `/root/workspace`。
3. 运行后由 OpenCode 占满应用内容区。点击边缘把手展开设置按钮，再点击打开设置；把手可拖动并贴靠左右边缘，位置会保留，按钮空闲 3 秒收起。实体键盘可按 `Ctrl+Alt+O` 打开设置，`Ctrl+Shift+R` 刷新页面。
4. 设置分为“终端”“Android 访问”“运行环境”“关于”。终端页可打开 Ubuntu 终端、修改显示名称；运行环境页提供刷新、重启、OpenCode 更新、日志和停止。终端显示名称只修改 Bash 提示符，默认 `ouse@OpenCode`，不改变 root 用户或 `/root` 路径；新终端自动生效，已有终端执行 `source ~/.bashrc`。输入支持 1–40 个字母、汉字、数字、空格及 `_ . @ -`。不会修改用户已有的提示符配置行，而是在 `.bashrc` 引用独立的 `.ousecode/prompt.bash`。
5. 离开界面后，Android 前台服务继续管理运行环境。从桌面或通知返回会复用原界面，保留网页状态，不重复播放启动动画。通知或设置中的“停止”会结束任务；项目和会话保留。

触摸板两指滚动在宿主中转换为滚轮输入，适用于对话、模型列表等网页滚动区域；触屏保持原来的操作方式。宿主也统一触摸板悬停事件的鼠标类型，让分隔条和正文之间的光标样式正常切换。

宿主对 OpenCode 分隔条设置 `touch-action: none`，避免触摸调整被浏览器滚动取消；松开、失去窗口焦点或未按键的鼠标移出后清理该次拖动和指针捕获。实体键盘开始组词时会请求恢复输入法候选界面，最终显示方式由输入法决定。

`Ctrl+Alt+T` 在 OpenCode 网页或终端内展开／收起临时 Ubuntu 终端；也可以从设置打开。它是 PRoot 内的交互式 Bash，默认目录 `/root/workspace`，与 OpenCode 共用项目文件，不是 Android Shell。支持实体键盘、IME、Tab 补全、方向键历史、Ctrl+C 和长按复制／粘贴（`Ctrl+Shift+V` 粘贴）。收起保留当前目录和任务，× 或 `exit` 结束会话；重启／停止运行环境也会结束会话。OpenCode 自己的快捷键（包括 `Ctrl+反引号`）保持原样。Termux 终端组件随 APK 内置，无需安装 Termux 应用。

启动动画由 Kotlin Canvas 原生绘制黑白数字雨与标题解码，不加载动画网页。解压时显示真实阶段进度，无法确定百分比的启动阶段显示活动进度条；OpenCode 网页首帧准备好后，以固定 700 毫秒完成启动画面淡出和页面淡入，并配合轻微缩放。系统动画倍率仍然生效；隐藏或后台时暂停数字雨。首次进入不弹出改名窗口，默认名称可随时在设置修改。

## Android 访问

此功能可选，默认关闭；Ubuntu 的日常使用不需要 Shizuku。需要操作 Android 本机时，先安装并启动 [Shizuku](https://shizuku.rikka.app/)，然后在设置 → Android 访问中授权 OpenCode。Shizuku 的系统授权只需首次确认；系统重启后需要重新启动 Shizuku。

- **关闭**：禁止 Android 工具调用，并结束已打开的 Android 终端和正在执行的 Android 命令。
- **每次确认**：每次 AI 调用 `android_exec` 都使用 Android 原生确认卡；用户手动打开 Android 终端时，确认一次后可在该会话内交互。
- **智能**：应用识别命令，只自动放行已知的状态查询。修改文件／系统设置、安装卸载、进程控制、敏感数据读取，以及脚本、重定向、管道、复合命令等需要本次确认。弹窗显示完整命令和 AI 提供的用途；拒绝、30 秒未处理或切换模式都会取消，不保存“永久允许”。判断由本机规则执行，不把 AI 自报“安全”当作授权。
- **直接执行**：AI 调用及手动打开 Android 终端不再弹出额外确认。Shizuku 的首次系统授权仍然需要用户允许。

AI 使用 MCP 工具 `android_exec`，参数包括 `command`、可选的 `purpose` 和 `timeoutSeconds`（默认 30，范围 1–120 秒）；返回标准输出、标准错误、退出码和超时状态。普通项目命令仍在 Ubuntu 中运行。Android 工作目录为 `/data/local/tmp`，实际权限由 Shizuku 的启动方式决定：ADB 模式通常为 shell UID 2000，Root 模式为 UID 0，不会自动获得 Root。

设置中的“打开 Android 终端”提供独立交互式 Android Shell。智能和每次确认模式在进入手动交互会话时确认一次，会话内不逐条拦截输入。收起保留会话，× 结束会话；Ubuntu 终端和 `Ctrl+Alt+T` 的行为保持原样。执行模式更改会重新加载 OpenCode 配置，无需重启整个 Ubuntu，但会取消 OpenCode 当前等待中的权限确认和表单。

“每次确认”和“智能”的待确认命令统一由 Android 宿主执行本次授权，OpenCode 中的持久允许规则不能跳过它，不改变 Ubuntu 工具的权限规则；智能查询规则不做完整 Shell 语义分析，无法识别就确认。此功能不把 PRoot 当作不可信代码的安全隔离；安装的程序共享应用身份。MCP 只监听本机、使用随机认证凭据，关闭模式在宿主端拒绝执行。

确认卡显示命令、用途、剩余时间及“拒绝／仅允许本次”。期限为请求创建后的 30 秒，切换应用或重新显示不会重置，过期授权无效。有“显示在其他应用上层”权限时，卡片显示在其他应用上方；没有此权限时，在 OpenCode 内显示相同卡片。确认通知也提供这两个按钮及倒计时。设置 → Android 访问中可管理悬浮窗和确认通知；关闭通知会影响跨应用提醒。拒绝、超时、切换模式或停止环境都会移除对应确认界面。

要求 Android 8.0+、ARM64，首次安装建议至少预留 1.5 GB 空间。后台存活仍取决于设备电池策略，前台服务不是永久保活承诺。预览版面向 APK 安装，targetSdk 28 用于兼容 PRoot 中的 Linux 可执行文件，不是 Google Play 发布配置。

## 更新与数据

- OpenCode 可在设置中独立更新，不需要重建 APK。更新会停止当前任务，并保留 `opencode.previous` 可执行文件。
- 大版本可能迁移会话数据库；保留旧程序不能替代数据备份。更新前应导出重要项目与会话。
- 插件使用 OpenCode 自己的配置和安装机制；网页是否显示插件的专属 UI 由插件决定。
- APK 中 PRoot 和 Android 宿主的更新需要新 APK。当前预览版不自动迁移不同版本的 Ubuntu 基础包。
- 项目、凭据、会话等保存在应用私有 `files/home`；程序在 `files/engine`；Ubuntu 位于 `files/runtime/rootfs`。卸载应用会删除这些数据，覆盖安装不会。
- OpenCode 只监听 `127.0.0.1`，使用随机本机密码。WebView 仅向本机服务提交认证；外部登录在浏览器打开。不会预置任何模型账号或 API Key。

## 构建

项目源代码位于 `D:\Projects\OuseCode`。当前构建环境为 WSL Ubuntu-22.04，构建副本 `/home/ouse/projects/OuseCode`。使用 JDK 17、Android SDK 36、Gradle 9.3.1、AGP 9.1.0 和 NDK 28.2.13676358。预览版使用构建账户的本地 Android debug keystore 签名；保留这把密钥才能覆盖更新当前 APK。

已有 `app/src/main/assets/runtime` 和 `app/src/main/jniLibs` 后：

```bash
bash tools/build.sh
```

当前 Windows/WSL 环境的一步同步构建：

```powershell
wsl -d Ubuntu-22.04 -- bash /mnt/d/Projects/OuseCode/tools/sync-build.sh
```

重新组成运行时使用 `tools/prepare_runtime.py`。它从 DSH Go 的已校验构建缓存取得 Ubuntu Base、额外 Ubuntu 包、Node 和 PRoot，**不带入 DSH 引擎或用户数据**；OpenCode 来自官方 Linux ARM64 发布包。输入及 SHA-256 记录在 `runtime-inputs.json` 和 `ubuntu-packages.lock.json`。

```bash
python3 tools/prepare_runtime.py \
  --dsh /home/ouse/projects/dsh-Go \
  --source /home/ouse/projects/OuseCode \
  --engine-archive /path/to/opencode-linux-arm64.tar.gz \
  --engine-version 2.3.2
```

## 真机检查

传入 `-e host true` 单独检查宿主：连续三次切到后台、从桌面返回及通知打开不创建新主界面、不重载网页、不重启引擎；并用独立测试网页检查两指滚动、普通触屏和分隔条光标恢复，不读取用户对话。

传入 `-e approvals true` 在 Android 10+ 真机上检查后台悬浮确认、通知允许／拒绝、倒计时、过期授权、旧通知失效及模式切换取消。需要已授权的 Shizuku、悬浮窗和通知权限，只执行无害测试命令并恢复原执行模式。

自定义 instrumentation 使用 APK 内真实 ARM64 PRoot、Ubuntu 和 OpenCode，检查应用名称与自适应图标、启动、API 认证、网页、一次性配对、Node/npm/Git、证书和重启后的文件保留。不使用模型凭据，不调用付费模型。传入 `-e ui true` 验证原生动画过渡、全屏网页、悬浮设置拖动与收起、键盘入口、提示符编辑、登录 Shell 和 root 身份保留（UI 自动化需要 Android 10+）；传入 `-e extended true` 还会验证 Ubuntu 中的 DNS/HTTPS，以及当前版本的应用内更新与重启流程。

传入 `-e terminal true` 验证真实 Ubuntu PTY、共享目录、Unicode 输入输出、Tab、方向键历史、Ctrl+C、快捷键路由、隐藏保留会话和关闭后子进程清理。

基础检查也验证 Android 附加组在 Ubuntu 中能解析为组名、交互和登录 Bash 不出现找不到组名的提示，以及重启不会重复添加组记录。启动时仅为缺少名称的组 ID 补充本地组名，保留已有组和成员，不修改 Android 权限。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r device-tests/build/outputs/apk/debug/device-tests-debug.apk
adb shell am instrument -w io.github.ouse.ousecode.test/io.github.ouse.ousecode.SmokeInstrumentation
adb shell am instrument -w -e ui true io.github.ouse.ousecode.test/io.github.ouse.ousecode.SmokeInstrumentation
```

独立 `device-tests` 测试包面向 SDK 36。首次授权 Shizuku 后，添加 `-e android true` 检查本机 MCP 认证、关闭拦截、执行策略重载、真实 Shizuku UID、标准输出／错误、超时和交互 Android PTY，并验证手动终端的确认、取消和会话复用（弹窗检查需要 Android 10+）。智能模式检查包含查询识别、危险／复合命令识别、原生弹窗、未批准／拒绝时不创建测试文件、仅允许本次，以及切换模式取消待确认命令；危险命令样例只做分类，不实际执行。测试结束恢复原执行模式。此检查不调用付费模型，也不代替实际模型的 OpenCode 权限弹窗验证。

0.2.3 已在 Lenovo TB378FC / Android 15 ARM64 上通过基础、设置界面、Ubuntu 终端和 Android 访问检查；Shizuku 实际身份为 UID 2000。应用内更新与 DNS/HTTPS 扩展检查在此前版本通过，本次未重复执行。APK 构建、资源/原生库哈希校验及 Android lint 均已通过。

模型订阅登录、第三方插件和长时间后台使用需要额外真机验证，基础 smoke 检查不证明这些功能可用。

0.2.5 在真机通过返回应用复用界面、两指滚动、光标恢复、持续触摸调整、丢失松开／窗口失焦清理，以及新旧输入法接口的中文组词与提交检查；构建和资源哈希校验通过，lint 无错误。候选框实际显示及触摸板手感由用户验证，未将模拟组词检查视为真实输入法候选框验证。

## 许可与来源

应用源码按 AGPL-3.0 提供。`TarExtractor.kt` 改编自 [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go)，修改了包名；PRoot 的 Android 原生库取自该项目已构建的 Termux 二进制。OpenCode 为 MIT，Ubuntu 各软件包保留自己的许可证。详见 `LICENSE`、`app/src/main/assets/NOTICE.txt` 和运行时中的包许可文件。

交互终端使用 Termux v0.118.3 的 terminal-emulator 和 terminal-view，源码、版本和许可说明位于 `third_party/termux`；APK 附带 GPL-3.0 和 Apache-2.0 许可全文。

Android 访问使用 Shizuku API/provider 13.1.5（MIT）；许可全文随 APK 附带。

OuseCode 是独立项目，与 OpenCode、Canonical、Termux 团队无隶属关系。
