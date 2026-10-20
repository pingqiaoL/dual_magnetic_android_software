# MK32 磁轮检测机器人 Android 客户端开发文档

文档日期：2026-10-19。对应代码版本：`0.8.6`，`versionCode=21`，包名：`com.climbwheel.mk32`。

本文面向后续接手开发、NX/STM32 联调、现场部署和故障维护人员，以当前仓库实际实现为准。用户已反馈本轮视频问题成功解决；受控测试的量化结果与真实链路的用户验收分别记录，不把播放器缓存时间当作摄像头到屏幕的绝对延迟。

## 目录

1. [项目目标与实现范围](#1-项目目标与实现范围)
2. [系统架构与控制边界](#2-系统架构与控制边界)
3. [开发环境与构建](#3-开发环境与构建)
4. [源码结构与线程模型](#4-源码结构与线程模型)
5. [UART 接入与诊断](#5-uart-接入与诊断)
6. [MAVLink 消息与命令约定](#6-mavlink-消息与命令约定)
7. [界面与状态显示](#7-界面与状态显示)
8. [测厚轮次与实时曲线](#8-测厚轮次与实时曲线)
9. [文件保存与历史数据](#9-文件保存与历史数据)
10. [RTSP 视频与低延迟设计](#10-rtsp-视频与低延迟设计)
11. [视频故障根因与修复验收](#11-视频故障根因与修复验收)
12. [安装与真实链路联调](#12-安装与真实链路联调)
13. [虚拟数据与受控视频测试](#13-虚拟数据与受控视频测试)
14. [常见故障定位](#14-常见故障定位)
15. [测试与交付检查](#15-测试与交付检查)
16. [已知限制与后续开发](#16-已知限制与后续开发)

## 1. 项目目标与实现范围

客户端运行在思翼 MK32 安卓遥控器上，集中显示机器人运行状态、测厚读数/时间曲线及 Orin 视频，避免在主界面堆积开发调试信息。

当前已实现：

- 视频主画面与右下角测厚小窗互换；测厚主画面使用浅色工业仪表风格，视频小窗固定右下角。
- UART 双向 MAVLink 通信、心跳/PING、测厚命令发送、ACK 与超时重试。
- 根据 NX 回传的测厚开关状态建立测量轮次；从零计时、停止冻结、有效数据自动保存。
- 实时曲线拖动回看、点击取点、返回实时、清空显示、历史任务列表和历史曲线查看。
- RTSP 硬件解码、有限变速追帧、断流/网络变化后的会话重建、持久诊断日志。
- 无真机时的演示数据，以及调试包中的两轮虚拟测厚和受控视频积压测试入口。

需要区分：代码中仍保留开始/停止/校零、标记、文本报告等动作处理接口，但当前简洁 UI 并不显示早期版本的全部操作按钮。保留接口不等于当前屏幕具备对应操作入口。测厚轮次以 NX 的 `MEAS_RUN` 为准。

## 2. 系统架构与控制边界

```text
安全控制：MK32 摇杆/SA ──无线遥控── 天空端 SBUS OUT ── STM32
                                                        │
                                                        │ 状态/测量数据
                                                        ▼
数传链路：MK32 App /dev/ttyHS0 ↔ 天空端 UART ↔ Orin NX /dev/ttyTHS0
          MAVLink 2，115200 8N1；NX 汇聚 STM32 数据并处理测厚命令

视频链路：Orin 摄像头 → H.264/RTSP → 天空端/地面端网络 → MK32 eth0
          → Media3 RTSP → MediaCodec → SurfaceView
```

### 2.1 不能混淆的职责

| 部分 | 负责 | 不负责 |
| --- | --- | --- |
| MK32 Android | 状态显示、测厚业务通信、视频、数据存储 | 通过 UART 解锁车辆或执行车辆物理急停 |
| SA/SBUS | 独立的车辆解锁与安全控制 | Android UI 和文件存储 |
| Orin NX | 汇聚/转发 STM32 数据，发送测量状态，处理测厚命令，提供视频 | 由 Android 状态文字替代实际安全回路 |
| STM32 | 底层车辆控制、安全逻辑及数据上报 | 安卓视频播放 |

早期“Android UART 仅接收”的设计已经演进为双向通信。现版本会发送 HEARTBEAT、PING 和测厚命令；不能按只接 RX 的接线方式验证双向功能。车辆安全通道仍保持独立。

### 2.2 当前现场参数

| 项目 | 当前值 |
| --- | --- |
| 安卓串口 | `/dev/ttyHS0`，115200、8N1、raw |
| Orin 串口 | `/dev/ttyTHS0`，115200、8N1 |
| App MAVLink 地址 | system 255 / component 190 |
| NX MAVLink 地址 | system 1 / component 191 |
| RTSP 地址 | `rtsp://192.168.144.129:8554/live` |
| MK32 视频网络 | `eth0`；现场配置曾使用 `192.168.144.20`，以设备实际 IP 为准 |
| SBUS | 独立接口，100000、8E2；反相/电平由硬件确认 |

UART 的 TX/RX 交叉、信号共地；确认电平兼容，独立供电设备不要互接 5V。USB ADB 可用于安装和诊断，但不能直接替代天空端无线数传。

## 3. 开发环境与构建

### 3.1 技术栈

| 项目 | 配置 |
| --- | --- |
| UI | Java 原生 Activity + 自绘 Canvas；不是 WebView/Compose |
| Android SDK | minSdk 23，compileSdk / targetSdk 33 |
| Android Gradle Plugin | 7.4.2 |
| Gradle wrapper | 7.6.4 |
| Java 源码兼容级别 | 11；建议使用 JDK 11 构建本版本 |
| 视频库 | Media3 ExoPlayer / RTSP 1.1.1 |
| 单元测试 | JUnit 4.13.2 |
| 已验证 MK32 | Android 9 / API 28，1280×800，Qualcomm MSM8953 |
| 已观察硬解码器 | `OMX.qcom.video.decoder.avc` |

本机工程位于 `/home/lpq/climb_wheel_software`，SDK 位于 `/home/lpq/Android/Sdk`。换机器时通过 `local.properties` 配置 `sdk.dir`，不要提交开发者个人 SDK 路径。

### 3.2 常用命令

在工程根目录执行：

```bash
java -version
./gradlew --version
./gradlew testDebugUnitTest assembleDebug
./gradlew lintDebug
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。Lint 报告通常位于 `app/build/reports/lint-results-debug.html`，以构建输出为准。

`assembleRelease` 可用于构建 release 变体，但当前没有配置正式签名发布流程。正式交付需要保管发布密钥、确定签名与版本升级策略；不要把 debug 包当作正式发布包，不要把密钥或密码提交到仓库。

## 4. 源码结构与线程模型

以下路径相对 `app/src/main/java/com/climbwheel/mk32/`：

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 生命周期、数据源选择、命令动作、轮次记录、文件任务、视频窗口位置 |
| `model/TelemetrySnapshot.java` | 不可变遥测快照，包含链路/车辆/测厚状态和新读数接收时间 |
| `model/SavedMeasurement.java` | CSV 读取、有效数据过滤、历史轮次恢复 |
| `telemetry/TelemetrySource.java` | 真机和演示数据源公共接口 |
| `telemetry/MavlinkUartSource.java` | 串口配置、读写、发送调度、命令重试、通信日志 |
| `telemetry/MavlinkFrameParser.java` | MAVLink 1/2 字节流组帧与已知消息 CRC 校验 |
| `telemetry/MavlinkEncoder.java` | MAVLink 2 心跳、PING、COMMAND_LONG 编码 |
| `telemetry/MavlinkTelemetryDecoder.java` | 标准消息/命名业务值到遥测快照的映射 |
| `telemetry/DemoTelemetrySource.java` | 2 Hz 演示数据及两轮自动测厚测试 |
| `ui/DashboardView.java` | 状态栏、画中画、曲线绘制、拖动取点、历史只读视图 |
| `ui/ThicknessHistory.java` | 时间序列缓存、窗口统计、最近点查询 |
| `video/RtspVideoController.java` | 播放器/Surface 生命周期、网络监控、断流重连、诊断采集 |
| `video/LivePlaybackCatchUp.java` | 独立可测试的追帧状态机 |
| `video/VideoDiagnostics.java` | 异步 CSV 诊断与轮转 |

线程分工：

- UART 使用两个调度线程：阻塞读取与周期发送/命令检查，避免在绘图路径中等待串口读入。
- 遥测更新转回主线程后更新 UI 和轮次记录。每个新的有效读数触发重绘；状态定时器每 250 ms 刷新时间和超时显示，不代表测量采样率为 4 Hz。
- 文件列表、CSV 读写和报告导出使用独立单线程执行器。
- 视频追帧、播放器控制与看门狗由主线程协调；诊断文件由独立、有界队列后台线程写入，过载允许丢诊断记录。
- Activity `onStart` 启动数据源/视频，`onStop` 停止，`onDestroy` 释放资源。当前不是后台持续采集服务。

## 5. UART 接入与诊断

### 5.1 打开过程

当 `/dev/ttyHS0` 存在时选择真实 UART；不存在时选择演示数据。调试包显式启用虚拟测厚测试时优先使用演示源。串口存在但打开失败时报告错误，不会悄悄切换成假数据。

打开前通过 `/system/bin/stty` 配置并校验 115200、8 数据位、无校验、1 停止位、raw；关闭回显、软件流控和会改变二进制数据的字符转换，然后建立输入/输出流。仅创建 Java 文件流不会自动设置串口波特率。

### 5.2 日志与分层判断

```bash
adb shell ls -l /dev/ttyHS0
adb shell /system/bin/stty -F /dev/ttyHS0 -a
adb shell getenforce
adb logcat -v time -s Mk32Mavlink:D '*:S'
```

多设备时所有命令使用 `adb -s <MK32_SERIAL>`。日志记录打开结果、RX/TX 字节数、累计字节数、原始十六进制及解析结果；每条原始输出最多显示前 96 字节，不等于读入数据被截断。

定位顺序：

1. 节点不存在：检查设备型号、固件、端口，不先改 MAVLink 封装。
2. 打开失败：检查节点权限、SELinux 和厂商授权接口。
3. RX 始终为零：检查 NX 正在发送、接线、天空端配置和是否选错端口。
4. RX 有字节但无有效帧：对照十六进制，确认波特率/raw、MAVLink 帧头、CRC Extra、消息 ID。
5. 能解析但不显示：检查字段名、测厚轮次状态、源地址及读数是否为新值。
6. 遥测正常但命令超时：检查反向 TX、NX 对 255/190 的接收与 ACK；单向收到状态不等于双向链路成立。

同时关闭可能使用同一端口的串口终端、厂商地面站和测试脚本。可尝试 `adb shell lsof /dev/ttyHS0`；有些固件没有此工具或不允许查看其他进程，此时不能据“查不到”认定无占用。不要用另一个读取进程持续读取同一串口来抓包，以免分走 App 数据。

不要将 `chmod 777` 或关闭 SELinux 作为正式修复。批量部署应由厂商 SDK、系统签名/白名单或系统串口服务提供稳定权限。

### 5.3 周期与异常恢复

| 任务 | 当前实现 |
| --- | --- |
| App HEARTBEAT | 每 1 秒 |
| App PING | 启动后约 300 ms 首次，随后每 2 秒 |
| 接收新鲜度门限 | 3500 ms，用于命令发送准入等判断 |
| 命令状态检查 | 每 200 ms |
| 串口读取异常 | 关闭端口、失败结束待确认命令、停止执行器 |

串口读异常后尚未实现独立的后台自动重开状态机；可返回前台或重启 App 触发重新初始化。不要把视频的自动重连能力误认为 UART 也有同等恢复能力。

## 6. MAVLink 消息与命令约定

### 6.1 帧与地址

发送为未签名 MAVLink 2，帧头 `0xFD`，多字节业务字段使用小端。接收可识别 MAVLink 1（`0xFE`）和 2，对当前支持的消息使用 X25 CRC + CRC Extra。

NX 心跳必须来自 system 1 / component 191。此前观察到的其他地面站 `REQUEST_DATA_STREAM` 不能作为本 App 已运行或双向正常的证据，应核对 APK 版本与实际进程的 HEARTBEAT/PING 日志。

### 6.2 接收消息

| ID | 消息 | 当前用途 |
| --- | --- | --- |
| 0 | HEARTBEAT | NX 存活、车辆解锁位与异常状态 |
| 1 | SYS_STATUS | 车辆电量、错误信息 |
| 4 | PING | 广播请求应答、面向 App 的双向确认 |
| 32 | LOCAL_POSITION_NED | 根据速度分量求速度模长 |
| 74 | VFR_HUD | 地速 |
| 77 | COMMAND_ACK | 命令应答 |
| 147 | BATTERY_STATUS | 电量 |
| 251 / 252 | NAMED_VALUE_FLOAT / INT | 测厚与状态业务字段 |
| 350 | DEBUG_FLOAT_ARRAY | `WAVE_A` / `WAVE_B` 波形兼容解析 |

组帧器还认识部分其他消息的 CRC，但不意味着业务解码器均消费这些消息。当前主界面的时间—厚度曲线由 `THICKNESS` 新读数生成，不是完整的超声 A/B 扫显示器。

### 6.3 命名业务字段

字段名称最长 10 个 ASCII 字节；解码时转换为大写。

| 名称 | 含义与处理 |
| --- | --- |
| `THICKNESS` | 原始测厚值，单位 μm；更新新读数本地接收时间 |
| `THICK_MAX` / `THICK_MIN` / `THICK_AVG` | NX 提供的统计字段，不自动等同于当前可见曲线统计 |
| `MEAS_RUN` | 大于 0.5 为测厚开启，否则关闭；驱动本地轮次 |
| `STM_LINK` | 大于 0.5 表示 STM32 在线 |
| `VEH_STATE` | 0 异常，1 未解锁，2 已解锁 |
| `FAULT` | 故障码 |
| `SPEED` | m/s |
| `VEH_BATT` | 车辆电量百分比 |
| `RC_BATT` | 遥控器电量百分比，作为显示备用来源 |
| `SCAN_SIDE` | 大于 0.5 为 B，否则 A；保留协议兼容 |

NX 应周期发送 HEARTBEAT、MEAS_RUN、STM_LINK；只有获得有效新读数时发送 THICKNESS。不要为了保持链路而重复发送旧厚度，否则接收端会把它当作新数据。

目前命名消息内的 `time_boot_ms` 不参与曲线计时；曲线和 CSV 使用安卓本地接收时刻，而不是设备采样时刻。NX 与安卓时钟未做采样级同步。

**状态解释注意：**现解码器对部分测厚/速度/测量状态消息也会刷新 STM32 在线时间，可能覆盖此前 `STM_LINK=0` 的效果。HEARTBEAT 与 VEH_STATE 更新车辆状态时按收到顺序生效，不是锁存式安全判断。顶栏仅用于显示，不可承担安全控制。

### 6.4 测厚控制命令

App 使用 COMMAND_LONG（ID 76），目标 1/191，参数 1～7 当前均为零：

| command | 业务 |
| --- | --- |
| 31010 | 开始测厚 |
| 31011 | 停止测厚 |
| 31012 | 校零 |

NX 返回相同 command 的 COMMAND_ACK。结果 0 表示接受，5 表示进行中；其他结果作为失败返回。当前部分错误提示文字仍需与 MAVLink 标准结果码进一步对齐，联调以原始结果码为准。

正常等待 ACK 约 900 ms；**最多发送 3 次，包含首次发送，即最多重发 2 次**，confirmation 为 0、1、2。检查周期会产生约 200 ms 的调度粒度。IN_PROGRESS 将下一次处理延后 5 秒。一次只允许一条待确认命令；NX 应对重复命令进行幂等处理。

ACK 成功只表示命令被接受，不直接修改测量开关。实际运行状态和数据轮次仍等待 NX 的 MEAS_RUN，避免“按钮显示启动但硬件未启动”。

完整联调约定见 [NX UART 协议](NX_UART_BRIDGE_PROTOCOL.md)。

## 7. 界面与状态显示

### 7.1 布局

- 设计坐标为 1600×1000，按屏幕比例居中缩放，适配 MK32 的 1280×800。
- 顶栏高度为设计坐标 82，显示应用名称、设备时间、链路、电量、车辆状态、速度和测厚状态。
- 默认视频铺满顶栏以下区域，右下角为测厚卡片。
- 点击右下角小窗互换主画面。数据主页面右上角提供“历史数据”，曲线区域提供“清空数据”和返回实时操作。
- 无视频时背景黑色，保留“等待视频流”。开发诊断文字不占用常规画面。

SurfaceView 位于 DashboardView 后面；有视频时自绘层清出视频区域，无视频时覆盖旧画面，避免把断流前最后一帧误认为实时画面。切换主次窗口只调整显示位置，不应中断 UART 接收。

### 7.2 状态的真实含义

| 顶栏内容 | 当前来源/规则 |
| --- | --- |
| 遥控已连接 / 未连接 | NX HEARTBEAT 接收时间距现在小于 3500 ms；演示源单独显示“演示数据” |
| 遥控器电量 | Android 电池广播/属性，必要时读取 battery/capacity；有效读数缓存，协议 RC_BATT 备用 |
| 车辆异常 / 未解锁 / 已解锁 | NX HEARTBEAT 或 VEH_STATE |
| 速度 | NX 遥测值；颜色还参考 STM32 数据新鲜度 |
| 测厚运行中 / 已停止 | NX MEAS_RUN |

“遥控已连接”目前是端到端 NX 心跳可达的业务判断，不是直接读取厂商射频链路质量/RSSI 的实现；没有心跳可能是无线、串口、NX 业务或地址过滤问题。视频连通和 UART 连通必须分别检查。

## 8. 测厚轮次与实时曲线

### 8.1 状态流程

```text
停止/待机
  └─ 收到 MEAS_RUN=1 → 清上一轮显示和内存记录 → 以本地接收时刻作为 t=0
       └─ 新 THICKNESS → 更新当前值、追加有效点、刷新曲线
            ├─ 1.5 秒无新读数 → 当前数字回零，已有曲线不删除、不追加假零
            └─ 收到 MEAS_RUN=0 → 停止计时、冻结曲线 → 后台保存本轮有效数据
                 ├─ 清空数据 → 清显示，不删除历史文件
                 └─ 下轮 MEAS_RUN=1 → 自动清上一轮，再从 t=0 开始
```

收到 RUNNING 的状态跃迁才建立新一轮，不是每条 MEAS_RUN 都重置。开始时先重置本轮标记数和记录去重时间，防止旧快照被重复保存。

只在轮次开启期间将新的有效 THICKNESS 加入显示/记录；心跳或速度消息携带的旧厚度不会再次形成采样点。测量停止后即使继续收到厚度，也不会追加到已结束轮次。

### 8.2 数字、曲线与时间

| 项目 | 规则 |
| --- | --- |
| 当前大数字 | 新读数保持 1500 ms；超时或停止后为零 |
| 主曲线窗口 | 60 秒，横轴为相对轮次起点的时间 |
| 小窗曲线窗口 | 30 秒 |
| 起始显示 | 不足一屏时从轮次零点展示，不制造负时间 |
| 曲线断点 | 相邻有效点间隔超过 3500 ms 时分段，不画跨缺测区的连续线 |
| Y 轴 | 按当前窗口有效数据自适应范围并留边 |
| 拖动 | 水平拖动回看；后台仍接收和记录 |
| 点击取点 | 选择窗口内距离目标时刻最近的真实数据点，显示时间/厚度 |
| 返回实时 | 恢复跟随当前测量进度 |

“无新数据归零”是当前读数显示行为，不是将不存在的测量值写进曲线或文件。轮次结束保留曲线，但当前数字归零，不代表保存的数据被抹去。

### 8.3 清空与容量

“清空数据”仅清除显示缓存：不删除已保存 CSV，也不清空 Activity 正在记录的本轮数据。运行中清屏后仍沿用当前轮次计时原点；新读数继续显示。

实时显示缓存保留最近 30 分钟且最多 20000 点；本轮 CSV 记录队列最多 18000 行，超限移除最早记录。两者上限不同，长时/高频任务需按采样率评估，不应宣称无限时长完整记录。

## 9. 文件保存与历史数据

### 9.1 保存位置与格式

首选应用外部专属目录：

```text
/sdcard/Android/data/com.climbwheel.mk32/files/Documents/ClimbWheel/
    task_yyyyMMdd_HHmmss_SSS.csv
    report_yyyyMMdd_HHmmss_SSS.txt
```

外部目录不可用时回退内部 files 目录下的 `ClimbWheel/`。应用不需要广泛存储权限，但卸载通常会删除应用专属文件；Manifest 已关闭 Android 自动备份，交付前应显式备份任务。

CSV 使用 UTF-8，先写同名 `.tmp`，写完再改名为 `.csv`，历史列表只显示完成后的文件。当前不是数据库事务或抗突然掉电的流式记录器。

```csv
timestamp_ms,event,scan,thickness_um,max_um,min_um,avg_um,speed_mps,battery_percent,fault_code,elapsed_ms,round_start_ms,round_end_ms
```

| 列 | 含义 |
| --- | --- |
| timestamp_ms | DATA 为新厚度本地接收时间；标记事件为快照时间 |
| event | DATA 或 MARK_n |
| scan | A/B 兼容字段 |
| thickness_um | 当前厚度，μm |
| max_um / min_um / avg_um | NX 快照统计值 |
| speed_mps | m/s |
| battery_percent | 车辆电量，而非遥控器电量 |
| fault_code | 故障码 |
| elapsed_ms | 相对本轮开始时间 |
| round_start_ms / round_end_ms | 本地轮次起止/保存时快照时间 |

自动保存发生在 RUNNING → 非 RUNNING 时；没有有效读数时提示无有效数据而不自动创建空任务。手动保存接口仍保留。仅退出 App 或意外断电并不能保证触发完整的轮次保存。

### 9.2 历史查看

点击“历史数据”异步枚举 `task_*.csv`，按修改时间倒序，显示时间和文件大小。选择后打开独立全屏只读 Dashboard，支持拖动和取点；底层实时接收/本轮记录继续进行。

CSV 读取按列名而不是固定列号；支持缺少新轮次字段的旧版记录，此时以有效数据推断范围。只恢复 DATA 行，跳过损坏、非有限值、非正时间戳以及时间重复/倒退的数据。最多读取 20000 个有效点，无有效数据则提示错误。

备份示例：

```bash
adb pull /sdcard/Android/data/com.climbwheel.mk32/files/Documents/ClimbWheel artifacts/measurements-backup
```

若使用内部目录回退，debug 包可借助 `run-as` 访问；release 包不保证允许 `run-as`。

## 10. RTSP 视频与低延迟设计

### 10.1 播放链路

```text
eth0 获得 IPv4 → 新建 ExoPlayer/RTSP 会话 → UDP 优先/TCP 回退
→ H.264 顺序解码 → SurfaceView 首帧 → 正常播放/积压追帧
→ 断流、网络变化或播放错误 → 黑色等待画面 → 释放旧会话 → 重试
```

生产地址固定为 Orin 地址；播放器静音。先确认 eth0 有 IPv4 再建链，当前不采用任意 Wi-Fi 自动替代视频链路。测试专用 loopback 地址例外。

### 10.2 参数

| 参数 | 当前值/用途 |
| --- | --- |
| LoadControl | min/max/start/rebuffer = 100/250/50/50 ms，优先时间阈值 |
| RTP 无数据超时 | 2000 ms，用于 UDP/TCP 回退 |
| 建链/首帧等待 | 6000 ms 超时保护 |
| 播放后 BUFFERING | 约 400 ms 后重建 |
| 停帧保护 | 首帧后约 1000 ms 无帧更新时恢复 |
| 网络检查 | 每 500 ms 检查 eth0/IP |
| 监控周期 | 100 ms |
| 重试间隔 | 1000 ms |
| 紧急积压重建 | 待播跨度超过 2000 ms |

这些是控制参数，不是所有场景下的精确时延承诺。LoadControl 的 250 ms 不是 RTSP 接收/解码总队列的硬上限。

### 10.3 有迟滞的有限追帧

`LivePlaybackCatchUp` 使用播放器待播媒体时间跨度，而非猜测绝对摄像头时延：

1. 输入和画面均持续更新，且播放器实际播放时，才允许追帧。
2. 待播跨度达到 300 ms，并持续至少 300 ms 后启动。
3. 积压达到 350 ms 时用 1.20 倍速，否则用 1.10 倍速。
4. 降到 180 ms 或以下时回到 1.0 倍速。
5. BUFFERING、输入不新鲜或渲染不流动时取消加速，避免欠缓冲。

不通过随机丢弃 H.264 压缩参考帧、不频繁 seek、不把正常的约 600 ms 启动积压当成必须反复断开的故障。只有长停顿或严重积压才重建。

### 10.4 生命周期与网络恢复

首次首帧与播放中缓冲分开处理；READY 不等于已经显示第一帧。Surface 销毁时释放播放器，Surface 重建后重新启动监控/建链。网络消失/IP 变化时先覆盖旧画面、释放旧播放器，再等待网络恢复，避免继续播放旧会话队列。

客户端尽力释放 RTSP 会话；网络物理断开时不能保证 TEARDOWN 到达服务器，Orin 仍需自己的会话超时清理，不能无限保留失联客户端。

### 10.5 日志

Logcat 标签：`Mk32Video`、`Mk32VideoDiag`，RTSP 建链还可看 `RtspClient`。

内部文件：`files/video_diagnostics.csv` 和 `files/video_diagnostics.previous.csv`，每份约 2 MiB 轮转。列为 `wall_time_ms,elapsed_realtime_ms,event,details`，用于核对版本、传输、首帧、缓冲、解码、丢帧、追帧和重连。

```bash
bash tools/collect_mk32_video_diagnostics.sh <MK32_SERIAL>
```

默认输出 `artifacts/mk32-video-diagnostics/`。采集脚本的 `run-as` 依赖 debug 包；读取失败会单独保留错误文件。视频诊断若包含 URL 或未来的鉴权信息，分享日志前应脱敏。

`buffered_ms` 是待播媒体时间跨度；`live_offset_ms` 可能是 TIME_UNSET；PTS、release lead、帧率和渲染耗时均不能单独证明端到端时延。物理时延应拍摄同一秒表/闪灯进行对照。

## 11. 视频故障根因与修复验收

### 11.1 本次确认的根因

0.8.5 诊断样本显示：实际使用 UDP、硬解约 30 FPS、稳定阶段 dropped=0，但 READY 的 68 次采样待播跨度为 596–658 ms，平均 625.44 ms，且速度始终为 1.0。

先前为消除花屏撤去激进重连后，正常启动积压被保留；后续 1200 ms 门限无法处理持续约 625 ms 的落后。Media3 1.1.1 这条 RTSP 时间线路径不能直接依靠内置直播追帧消掉该积压，仅把 LoadControl 数字调小也不够。

这说明客户端缺少积压消化机制，**不是证明发送端、无线或摄像头在所有情况下都没有延迟**。初始积压形成的全部来源未通过同步时钟独立分解。

### 11.2 0.8.6 的验证

同一 MK32 上用本地 GStreamer 测试源，720p/30 FPS、x264 zerolatency、约 6 Mbit/s，通过 USB ADB reverse 走 TCP。对播放注入 600 ms 暂停，进行开/关追帧对照：

| 指标 | 结果 |
| --- | --- |
| 关闭追帧的稳定待播均值 | 635.29 ms |
| 开启追帧后的稳定待播均值 | 176.6 ms |
| 消化到目标附近的耗时 | 3068 ms |
| 稳定播放速度 | 恢复 1.0 |
| 追帧阶段重连 | 0 |
| 稳定阶段 renderer dropped | 0 |
| 模拟断流后的重建触发 | BUFFERING 后 402 ms |
| 源恢复后 | 同一 App 自动新建会话并显示首帧 |

本地证据位于 `artifacts/rtsp-ab-test/`，不随 Git 自动提交。22 项单元测试已通过。用户随后反馈真实问题成功解决，作为本次实际使用验收记录；未获得新的摄像头到屏幕精确时延数字，因此不宣称“真实端到端延迟 177 ms”。

发送端最新信息来自现场反馈：1280×720/30 FPS、软件 x264、ultrafast、zerolatency、Constrained Baseline、无 B 帧、GOP 30、IDR 重发 SPS/PPS，码率由 2 Mbit/s 改为约 6 Mbit/s。早期资料写队列 2 帧，后续排查报告为最多 4 帧，应以当前 Orin 管线实测为准。仓库不包含 Orin 的 `/workspace/tools/start_video.sh`，不能把用户报告当成本仓库发送端实现。

完整根因、版本演进及官方源码依据见 [视频延迟修复记录](VIDEO_LATENCY_FINDINGS.md)。QGC/GStreamer 对照方法见 [视频诊断文档](VIDEO_LATENCY_DIAGNOSTICS.md)。对照的意义是比较实时队列/时钟策略，不代表本 App 已替换为 QGC 的 GStreamer 播放栈。

## 12. 安装与真实链路联调

### 12.1 核对设备与 APK

```bash
export PATH="/home/lpq/Android/Sdk/platform-tools:$PATH"
adb devices -l
adb -s <MK32_SERIAL> shell getprop ro.product.model
adb -s <MK32_SERIAL> install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s <MK32_SERIAL> shell dumpsys package com.climbwheel.mk32
adb -s <MK32_SERIAL> shell am force-stop com.climbwheel.mk32
adb -s <MK32_SERIAL> shell am start -n com.climbwheel.mk32/.MainActivity
```

将 `<MK32_SERIAL>` 替换为实际序列号/IP:端口，不要原样输入尖括号。核对 versionName=0.8.6、versionCode=21。使用 `-r` 更新通常保留应用数据；不同签名可能拒绝覆盖，不要直接卸载绕过而丢失任务，先备份。

### 12.2 UART 分步验证

1. 核对 MK32 天空端数传 UART 参数与 NX 的 ttyTHS0 一致；确保没有其他程序竞争端口。
2. 开启 App 后确认串口打开日志及 TX HEARTBEAT/PING，NX 能收到源 255/190。
3. NX 每秒发送源 1/191 的 HEARTBEAT、MEAS_RUN、STM_LINK，确认 App RX 字节增长和心跳连接状态。
4. 切换 MEAS_RUN 0→1，发送几条不同 THICKNESS，确认从零计时和新点更新。
5. 暂停 THICKNESS 超过 1.5 秒，保留心跳，确认当前值归零但曲线保留。
6. MEAS_RUN 1→0，确认冻结和自动保存；进入历史查看。
7. 再次开启，确认自动清上一轮而历史文件不丢失。

如需独立协议冒烟测试，将脚本复制到 NX 后运行。该脚本只使用 Python 3 标准库（包括 Linux 的 termios），不需要额外安装 pyserial 或 pymavlink：

```bash
python3 tools/nx_mavlink_smoke_test.py /dev/ttyTHS0 --baud 115200 --vehicle-state locked
```

这是替代真实业务发送端的测试程序，应先停止占用同一串口的业务进程，并在测试完成后退出；不要同时运行两个发送/读取程序。本仓库路径不会自动出现在远端，如复制位置不同需调整命令。该程序验证心跳、PING 和命令 ACK，不生成真实 THICKNESS，不能用来验收完整测厚曲线。

### 12.3 视频网络与 USB 限制

当前 MK32 固件的 ADB 数据口与内置 USB Hub/以太网共用 OTG 控制器。接入 USB 数据线可能切到 peripheral 模式，导致内部 USB 网卡注销、eth0 消失；拔线后该固件通常需重启才能恢复 Host。

因此，**USB 安装成功并不意味着此时真实 Orin 视频网络可用**。完成安装后拔掉数据线，必要时重启 MK32，让 eth0 恢复；供电使用独立电源口或确认不传数据的充电方式。App 可以恢复已有网络上的视频会话，不能在 Android 网卡设备已消失时用播放器代码恢复 USB Host。

若需要保留诊断连接，使用已配置且可达的无线/网络 ADB；不要为了排查播放器而盲目改变整机 OTG 模式或持久系统属性。

```bash
adb shell ip -4 addr
adb shell ip route
adb shell ping -c 3 192.168.144.129
```

上述操作必须在 ADB 可达时执行。网络恢复后核对 eth0 的 IP/路由、Orin RTSP 服务监听 8554，再打开视频；Orin 视频服务的启动与维护由发送端工程负责。

## 13. 虚拟数据与受控视频测试

### 13.1 UI/轮次验证

Ubuntu 模拟器：

```bash
./scripts/start_android_emulator.sh
./scripts/install_and_run_emulator.sh
```

脚本使用现有 `MK32_AOSP_API28` AVD；当前测试显示配置为 1280×720，启动参数为 2 核、1536 MB、SwiftShader。可用 `/dev/kvm` 时自动选择 KVM，否则软件模拟。脚本不是全新机器的 SDK/AVD 安装器。

模拟器和 MK32 同时连接时，脚本里的未指定目标 ADB 命令可能报多设备错误；应只保留目标设备，或改为手动 `adb -s` 安装。模拟器 ANR/低帧率不能代表 MK32 真机性能；隐藏系统错误对话框也不等于解决性能问题。

在 debug 包运行两轮自动测厚：

```bash
adb shell am force-stop com.climbwheel.mk32
adb shell am start -n com.climbwheel.mk32/.MainActivity --ez demo_wave_test true
```

生成间隔 500 ms，自动运行两段约 8 秒的测厚，中间停止；检查从零计时、清屏、冻结、两份 CSV 和历史查看。不改真实 UART 协议。退出测试后 force-stop，再不带 extra 正常启动。此入口应只用于调试。

### 13.2 视频积压 A/B 测试

Ubuntu 需提供 Python GI、GStreamer RTSP Server introspection、`x264enc`、`timeoverlay` 等插件；缺失时先按本机软件源安装依赖。测试服务器仅监听 127.0.0.1，避免意外暴露到外网。

终端 A：

```bash
/usr/bin/python3 tools/rtsp_backlog_test_server.py --port 18554
```

终端 B（示例假设仅一台 ADB 设备）：

```bash
adb reverse tcp:18554 tcp:18554
adb shell am force-stop com.climbwheel.mk32
adb shell am start -n com.climbwheel.mk32/.MainActivity \
  --es video_test_url rtsp://127.0.0.1:18554/live \
  --ei video_test_pause_ms 600 \
  --ez video_test_disable_catch_up true
```

采集稳定日志作为 baseline；然后 force-stop，使用同一命令但将 `video_test_disable_catch_up` 改为 `false`，观察 CATCH_UP 和待播下降。暂停注入上限为 1000 ms，仅用于可重复制造积压。

只在 debug 且目标为 loopback RTSP 时接受这些视频测试选项。此路径为适配 USB reverse 强制使用 TCP，不能据此认定生产 Orin 路径改成了 TCP。

断流测试可对测试服务器进程执行短暂暂停/恢复，观察 BUFFERING → RELEASE → 新会话 → FIRST_FRAME。只操作自己启动的服务器进程，不停止真实采集任务。

已有结果复核：

```bash
/usr/bin/python3 tools/verify_rtsp_backlog_test.py \
  artifacts/rtsp-ab-test/baseline.csv \
  artifacts/rtsp-ab-test/catch-up.csv \
  artifacts/rtsp-ab-test/recovery.csv
```

退出受控测试后：

```bash
adb reverse --remove tcp:18554
adb shell am force-stop com.climbwheel.mk32
adb shell am start -n com.climbwheel.mk32/.MainActivity
```

同时正常结束终端 A 的测试服务器。只删除本测试端口的 reverse，不清掉其他任务的转发规则。

### 13.3 QGC 风格参考对照

在能直接访问 Orin 的 Ubuntu 上，可执行：

```bash
bash tools/test_rtsp_qgc_low_latency.sh rtsp://192.168.144.129:8554/live
```

脚本使用 GStreamer RTP/UDP、latency=0、drop-on-latency、关闭重传及显示端 sync=false，便于对照客户端实时策略。它不是运行完整 QGC，也不保证与任意 QGC 版本的全部管线一致；零缓冲在丢包链路也可能增加花屏，不能无条件照搬。

## 14. 常见故障定位

| 现象 | 优先检查 | 处理方向 |
| --- | --- | --- |
| ADB 看不到设备 | VMware USB 透传、设备 USB 调试授权、adb devices | 先修连接，不改 App |
| App 一直演示数据 | ttyHS0 是否存在、是否带 demo extra | 核对真机和启动参数 |
| UART 打开失败 | stty 输出、文件权限、SELinux | 厂商权限/串口配置，不先改封装 |
| 有字节无遥测 | 帧头/CRC/消息长度、源 1/191、命名字段 | 对照原始十六进制和解析日志 |
| 能看状态不能控制 | App TX、NX ACK、端口竞争 | 验证双向，区分 ACK 与实际 MEAS_RUN |
| 有心跳但厚度为零 | MEAS_RUN、新 THICKNESS、1.5 秒有效期 | 确认本轮有效新数据，不重复旧值 |
| 清空后历史仍在 | 清屏与删除不是同一功能 | 当前设计正常 |
| 停止后没有文件 | 是否收到运行→停止、是否有有效数据、保存错误 | 查看提示和目录；仅退出不保证保存 |
| 等待视频流 | eth0/IP、ping、服务 8554、RTSP 日志 | 从网络到服务逐层检查 |
| USB 插拔后视频不恢复 | 内部网卡是否注销 | 拔数据线，必要时重启恢复 Host |
| 连接成功却灰屏 | Orin 本地解码帧、首帧/解码日志、Surface | 区分源画面灰与客户端未出帧 |
| 快速运动出现马赛克 | 丢包、码率、GOP、编码参数、是否频繁重连 | 兼顾画质与带宽，不随机丢压缩帧 |
| 固定落后但 FPS 正常 | buffered_ms、speed、CATCH_UP、实际 APK | 检查是否运行 0.8.6 及追帧是否触发 |
| 越播越落后 | 输入/解码/渲染速率、网络抖动、队列增长 | 留存日志，区分不断积压与固定启动积压 |
| 断开后 Orin 仍显示客户端 | 服务器超时、物理断线 TEARDOWN 不可达 | 服务端清理失联会话 |

不要在没有记录旧版本、参数和日志时同时修改码率、传输方式、重连门限和编码器；应一次改变一个变量，并保留对照结果。

## 15. 测试与交付检查

### 15.1 单元测试覆盖

当前 22 项测试包含：MAVLink 编码 3 项、业务解码 5 项、历史文件读取 3 项、时间序列缓存 5 项、追帧状态/流量仿真 6 项。具体以 `testDebugUnitTest` 的实际报告为准，不把一次通过当作未来版本永久保证。

开发完成后建议执行：

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
git diff --check
```

单元测试不能替代实机 UART 权限、无线丢包、硬解码、Surface、USB/网卡切换和长期运行测试。

### 15.2 每次交付验收表

- [ ] 版本号/版本码递增，安装包与源码版本一致，记录 APK SHA-256。
- [ ] 串口 raw 115200 8N1 打开成功，真实 RX/TX 与 NX 地址正确。
- [ ] 测厚状态切换、有效数据去重、1.5 秒归零、停止保存和下轮清空正常。
- [ ] 曲线拖动/取点、清屏、历史打开不阻塞实时接收。
- [ ] 真实 Orin 视频首帧、快速运动、长时播放及积压追帧正常。
- [ ] 网络中断/恢复、Surface/前后台切换不残留旧画面或反复重连。
- [ ] 正确区分播放器待播跨度与摄像头到屏幕时延，留存物理延迟对照。
- [ ] 不带 demo/video 测试 extras，不残留测试 reverse/测试源。
- [ ] 历史任务已备份，不提交 APK、artifacts、密钥或个人 local.properties。
- [ ] 明确车辆解锁/急停仍由 SA/SBUS 控制，UI 不替代安全措施。

生成校验值可运行 `sha256sum app/build/outputs/apk/debug/app-debug.apk`。当前仓库没有完整连续版本提交，后续应按每个可交付版本提交并打标签，便于回滚和差异定位；本文不自动执行提交或推送。

## 16. 已知限制与后续开发

以下为尚需完善项，不是本次已经实现的能力：

1. **协议健壮性**：当前手写解析器只支持已知消息 CRC；MAVLink 2 尾零截短载荷尚未统一补齐，部分合法短载荷会被业务最小长度检查拒绝。签名帧能跳过签名区，但不校验签名，不具备认证能力。
2. **来源与握手校验**：HEARTBEAT 严格检查 1/191，其他命名值、ACK/PING 的来源过滤不统一；PING 应答尚未与待发送序号严格关联，不能当作安全认证。
3. **状态独立性**：需把 STM_LINK 显式状态与“收到业务数据”的推断拆开，并对陈旧车辆/速度状态做更明确的失效显示。当前射频连接是 NX 心跳推断，不是厂商链路质量接口。
4. **UART 恢复与界面控制**：增加串口失败退避重开、统一错误码；如需屏幕操作测厚，明确简洁 UI 的入口及二次确认，不重新混入车辆解锁控制。
5. **数据可靠性**：当前内存按轮次保存，异常退出可能丢失未结束轮次；可增加分段落盘/日志恢复、存储配额、任务命名、导出分享和删除管理。长任务需要解决 18000/20000 点限制。
6. **计时与采样**：曲线当前用本地 wall clock，系统校时可能影响顺序。可将轮次时长改用单调时钟，并保留采样时刻/接收时刻双时间戳及 NX 时钟同步。
7. **长期运行**：当前数据随 Activity 生命周期启动/停止；如要求后台连续记录，应设计前台服务、任务恢复和断电容错，不能仅扩大缓存。
8. **视频质量与可配置性**：增加经过验证的地址/传输/画质配置入口、物理端到端测量与持续弱网测试。低延迟默认值不保证所有链路最佳，不自动把 Media3 升级当成修复。
9. **发布工程**：完善正式签名、厂商权限方案、版本化发布说明、自动化构建及 MK32 固件兼容矩阵。

继续开发时优先保持“通信、安全控制、显示、存储”边界清晰，任何涉及协议或低延迟策略的变更都应保留可复现测试和真机回归记录。
