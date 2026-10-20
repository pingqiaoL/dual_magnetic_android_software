# MK32 涂层测厚机器人 Android 客户端

详细开发、部署与维护说明见 [开发文档（0.8.6）](docs/DEVELOPMENT_GUIDE.md)，包含当前架构、协议、测厚轮次、历史存储、视频低延迟修复和实机验收记录。

这是一个面向 SIYI MK32 手持地面站的横屏原生 Android 客户端。当前版本实现了参考图中的数据仪表板，并明确区分测厚控制和车辆安全控制：

- MK32 摇杆、SA 安全开关经无线链路和天空端 **SBUS** 输出控制 STM32。
- Android 应用通过 MK32 内置数传 **UART 双向 MAVLink 2** 与 ORIN-NX 通信。
- 测厚开始、停止和校零通过 UART `COMMAND_LONG/COMMAND_ACK` 可靠控制；超时自动重试。
- 车辆解锁和急停只允许由 SA/SBUS 执行，不通过 UART。
- 虚拟机没有 MK32 时自动显示演示数据；MK32 上 `/dev/ttyHS0` 存在时连接真实双向链路。

## 当前界面

0.8.9 更新：实时/历史曲线支持双指横向缩放及“放大、缩小、复位”，范围每屏 1 秒～30 分钟；历史记录列表每条记录提供删除按钮，确认后删除所选 CSV 并刷新列表。

0.8.8 更新：主曲线每屏 10 秒、每格 2 秒；标签贴近对应红点并用短引线关联。读数超时前短暂下降，在 1.5 秒时归零并保持水平线；零线为显示效果，不写入真实测量记录。

0.8.7 更新：测厚主图采用保形平滑曲线，原始采样点以红点表示并标注厚度，密集标签自动避让；右下角测厚卡片仅显示大号数值。修复数据主画面透明区域未擦除白底、导致视频小窗被遮挡的问题。

- 1600×1000 横屏设计面，自动适配 MK32 的 1280×800 显示屏。
- 默认视频主画面、右下角实时测厚小窗；点击小窗可交换视频和测厚主画面。测厚主画面采用浅灰工业仪表风格，视频小窗仍固定在右下角。
- 内置 RTSP 播放地址 `rtsp://192.168.144.129:8554/live`，使用 Media3 RTSP 和原生 `SurfaceView`；优先使用 RTP/UDP，无 UDP 数据时自动回退 RTP/TCP。LoadControl 配置为 100/250/50/50 ms，该配置不是 RTSP 缓存的硬上限。
- 待播积压持续超过 300 ms 时，在新数据和画面都正常更新的条件下短时使用 1.1–1.2 倍速追帧，降至约 180 ms 后恢复正常速度，不进行 H.264 压缩参考帧随机丢弃或频繁 seek。MK32 USB 同机对照测试中，约 635 ms 积压降至约 177 ms；这不是摄像头到屏幕的绝对延迟。
- 播放后持续缓冲约 400 ms、停帧超过 1 秒、网卡消失/IP 改变或播放器报错时释放旧播放器并重建 RTSP 会话；超过 2 秒积压保留紧急重连。连接和首帧等待使用独立超时，Surface 重建后自动恢复监控。无流时显示“等待视频流”。
- 顶栏集中显示设备时间、遥控链路、电量、车辆三态、速度和测厚运行状态。
- 测厚拨杆开启时自动清空上一轮显示并从 `00:00.0` 开始计时；只有本轮真实 `THICKNESS` 消息进入曲线。
- 测厚拨杆关闭时停止计时、冻结本轮曲线并自动保存有效数据；也可使用“清空数据”按钮手动清屏。
- 数据主画面支持左右拖动回看、点击取点、时间/厚度浮窗和一键返回实时。
- 右上角“历史数据”打开已保存任务列表，选择记录即可查看曲线、拖动和取点；“返回测量”关闭查看窗口，后台实时采集始终继续。支持旧版 CSV，新记录保留拨杆起止时间。
- 当前厚度仅保持 1.5 秒；没有新读数时数值与曲线归零，历史真实数据继续保留，显示用的零线不作为真实读数保存。
- 历史缓存限制为最近 30 分钟且不超过 20000 点，避免长时间运行造成内存增长。
- 本地记录以 NX 回传的 `MEAS_RUN` 状态为准，不依赖命令 ACK；CSV 只记录该轮新的测厚读数，不重复记录 HEARTBEAT 携带的旧状态。

视频延迟的分层采集、无线 ADB 方法、QGC/GStreamer 对照和判断规则见 [视频延迟诊断](docs/VIDEO_LATENCY_DIAGNOSTICS.md)。

## 两条通信链路

不要把 SBUS 与 Android 数传 UART 混为一条串口：

| 链路 | 方向 | 建议参数 | 用途 |
|---|---|---|---|
| MK32 天空端 SBUS OUT → STM32 SBUS RX | 单向 | 100000, 8E2，反相按接收器硬件要求 | 摇杆、开关、安全控制 |
| ORIN-NX UART ↔ MK32 天空端 UART ↔ MK32 Android | 双向 | 天空端 115200, 8N1；Android `/dev/ttyHS0` 固定 115200 | 测厚命令/ACK、测量值、波形、车辆状态 |

ORIN-NX 与天空端 UART 必须交叉连接 TX/RX 并共地。STM32 数据先进入 NX，再由 NX 转换/转发给 MK32。SBUS 仍使用独立的 S.Bus OUT 接口。禁止在设备分别供电时互接 5V。

## MAVLink 遥测协议

接收层支持 MAVLink 1/2、CRC Extra 验证与错帧恢复；发送层使用 MAVLink 2。标准消息用于心跳、PING、命令 ACK、电池和速度；测厚业务值使用 `NAMED_VALUE_FLOAT/INT`，A/B 扫波形使用 `DEBUG_FLOAT_ARRAY`。完整的 NX 双向协议和联调程序见 [NX UART 协议](docs/NX_UART_BRIDGE_PROTOCOL.md)。

## 虚拟机编译

本机已经安装 Android SDK 到 `/home/lpq/Android/Sdk`，工程包含 Gradle wrapper：

```bash
./gradlew testDebugUnitTest assembleDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前 VMware 内未开放嵌套 VT-x/AMD-V 时，Android Emulator 只能使用很慢的 TCG 软件模拟。测试 AVD 因此默认使用 1280×720、2 核、1536 MB，并把演示刷新限制为 2 Hz；应用设计面仍会自动适配 MK32 的实际横屏分辨率。若系统反复提示“Emulator 无响应”，应关闭 VMware，勾选虚拟机处理器设置中的“虚拟化 Intel VT-x/EPT 或 AMD-V/RVI”，重新启动后确认 `/dev/kvm` 存在。

## 安装到 MK32

在 MK32 打开开发者模式和 USB 调试后，通过 USB 连接虚拟机，并把 USB 设备透传给虚拟机：

```bash
/home/lpq/Android/Sdk/platform-tools/adb devices
/home/lpq/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 当前实机固件中，ADB 数据口与内置 USB 集线器共用同一个 OTG 控制器。连接 USB 数据线后控制器会切换到 `peripheral` 模式，内置 CP2102 串口和 USB 以太网卡随即注销，`eth0` 消失，RTSP 视频因此无法访问。该固件拔线后通常不能自动切回 Host，需要断开 USB 数据线并重启一次；需要供电时使用独立供电口或仅充电线。应用会等待 `eth0` 获得 IPv4 后自动连接，不会在无网卡时反复创建播放器。

然后在 SIYI TX 中将数传连接设为 `UART`，飞控类型选 `PX4 / ArduPilot` 或 `Custom`，天空端与 STM32 的数传波特率设为一致。Android 应用侧按官方 SDK 定义读取 `/dev/ttyHS0`。

应用启动时会把 `/dev/ttyHS0` 明确配置并校验为 `115200 8N1 raw`，避免设备默认的 9600、行模式、回显或字符转换破坏 MAVLink 二进制帧。真机实时诊断日志：

```bash
adb logcat -v time -s Mk32Mavlink:D '*:S'
```

调试包可在不占用真实 UART 的情况下自动执行两轮虚拟测厚，用于检查从零计时、自动清屏、停表冻结和自动保存：

```bash
adb shell am force-stop com.climbwheel.mk32
adb shell am start -n com.climbwheel.mk32/.MainActivity --ez demo_wave_test true
```

## MK32 串口权限

普通 Android 第三方 APK 很可能没有直接读写 `/dev/ttyHS0` 的权限。如果 MK32 无法连接 UART：

```bash
adb shell ls -l /dev/ttyHS0
adb shell getenforce
```

不要长期用 `chmod 777` 或关闭 SELinux。正式方案应向 SIYI 获取 MK32 Datalink SDK、系统签名要求或串口权限白名单，再将 SDK 适配到 `TelemetrySource` 接口。现有 UI、协议解析和演示模式可以保留。

## 还需要确认的现场信息

进入硬件联调前，需要确定：MK32/固件版本、STM32 当前遥测协议、天空端 UART 电平、实际串口波特率、视频 RTSP 地址、屏幕实际分辨率，以及 SA 开关映射到哪个 SBUS 通道。
