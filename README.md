# MK32 涂层测厚机器人 Android 客户端

这是一个面向 SIYI MK32 手持地面站的横屏原生 Android 客户端。当前版本实现了参考图中的数据仪表板，并明确区分测厚控制和车辆安全控制：

- MK32 摇杆、SA 安全开关经无线链路和天空端 **SBUS** 输出控制 STM32。
- Android 应用通过 MK32 内置数传 **UART 双向 MAVLink 2** 与 ORIN-NX 通信。
- 测厚开始、停止和校零通过 UART `COMMAND_LONG/COMMAND_ACK` 可靠控制；超时自动重试。
- 车辆解锁和急停只允许由 SA/SBUS 执行，不通过 UART。
- 虚拟机没有 MK32 时自动显示演示数据；MK32 上 `/dev/ttyHS0` 存在时连接真实双向链路。

## 当前界面

- 1920×1080 横屏设计面，自动适配不同横屏分辨率。
- 数据/图像页签，可点击的 A/B 扫波形、实时厚度和滚动波形。
- 车辆异常/未解锁/已解锁三态、测厚状态、电池、速度和故障码。
- 遥控器连接、NX 下行、NX 双向握手、STM32 数据链路独立显示并带超时提示。
- 开始/停止测量收到 NX ACK 后会同步开始/停止本地记录；标记点、保存 CSV 和导出报告可用，文件保存在应用专属 `Documents/ClimbWheel` 目录。
- 控制类按钮不会通过 UART 发命令，所有点击结果都会在应用顶部直接提示，不依赖系统 Toast。
- 图像页播放按钮可点击并显示配置状态；需拿到实际摄像头 URL 后再接 RTSP 播放器。

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

然后在 SIYI TX 中将数传连接设为 `UART`，飞控类型选 `PX4 / ArduPilot` 或 `Custom`，天空端与 STM32 的数传波特率设为一致。Android 应用侧按官方 SDK 定义读取 `/dev/ttyHS0`。

## MK32 串口权限

普通 Android 第三方 APK 很可能没有直接读写 `/dev/ttyHS0` 的权限。如果 MK32 无法连接 UART：

```bash
adb shell ls -l /dev/ttyHS0
adb shell getenforce
```

不要长期用 `chmod 777` 或关闭 SELinux。正式方案应向 SIYI 获取 MK32 Datalink SDK、系统签名要求或串口权限白名单，再将 SDK 适配到 `TelemetrySource` 接口。现有 UI、协议解析和演示模式可以保留。

## 还需要确认的现场信息

进入硬件联调前，需要确定：MK32/固件版本、STM32 当前遥测协议、天空端 UART 电平、实际串口波特率、视频 RTSP 地址、屏幕实际分辨率，以及 SA 开关映射到哪个 SBUS 通道。
