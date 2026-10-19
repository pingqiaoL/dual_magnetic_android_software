# MK32 ↔ ORIN-NX 双向 MAVLink 协议

## 1. 链路与安全边界

```text
MK32 Android 应用
  │ /dev/ttyHS0，MAVLink 2，115200 8N1
  ▼
MK32 地面端 ⇄ 无线数传 ⇄ 天空端 UART
                              │
                              ▼
                         ORIN-NX 上位机
                              │ 双向 UART / ROS 2 bridge
                              ▼
                            STM32

MK32 SA 拨杆 ──无线遥控──> 天空端 S.Bus OUT ──100000 8E2──> STM32
```

- UART/MAVLink：测厚装置启停、校零、状态、厚度和波形。
- SA/SBUS：车辆解锁和硬件急停。Android 不得通过 UART 解锁车辆。
- NX 是 UART/MAVLink 与 STM32 业务协议之间的唯一桥接端。

## 2. MAVLink 地址

| 节点 | system id | component id |
|---|---:|---:|
| MK32 Android GCS | 255 | 190 (`MAV_COMP_ID_MISSIONPLANNER`) |
| ORIN-NX bridge | 1 | 191 (`MAV_COMP_ID_ONBOARD_COMPUTER`) |

第一阶段统一使用 MAVLink 2。MK32 内部 `/dev/ttyHS0` 固定为 115200；天空端外接 NX 的 UART 也设置为 115200、8N1。

## 3. 链路检测

| 消息 | 方向 | 周期/超时 | 作用 |
|---|---|---|---|
| `HEARTBEAT` | MK32 → NX | 1 Hz | NX 判断地面应用在线 |
| `HEARTBEAT` | NX → MK32 | 1 Hz；3.5 s 超时 | MK32 显示“遥控器已连接/NX 下行正常” |
| `PING` | MK32 → NX → MK32 | 2 s；5 s 超时 | 确认上行、下行均正常 |
| `NAMED_VALUE_INT STM_LINK` | NX → MK32 | 1 Hz；3.5 s 超时 | NX 报告 STM32 数据链路状态 |

收到目标地址为 `0/0` 的 `PING` 请求后，NX 必须原样返回 `time_usec` 和 `seq`，并把 `target_system/target_component` 设置为请求方 `255/190`。

## 4. 车辆三态

推荐 NX 同时使用两种兼容表示：

1. `HEARTBEAT.base_mode` 的 `MAV_MODE_FLAG_SAFETY_ARMED (0x80)` 表示已解锁。
2. `NAMED_VALUE_INT name="VEH_STATE"` 明确报告业务三态：

| value | UI 状态 |
|---:|---|
| 0 | 异常 |
| 1 | 未解锁 |
| 2 | 已解锁 |

若 `HEARTBEAT.system_status >= MAV_STATE_CRITICAL (5)`，MK32 强制显示异常。NX 应从 STM32 的 SBUS/SA 判定结果读取车辆状态，不能根据 Android 按钮推测解锁状态。

## 5. 测厚控制命令

MK32 使用 `COMMAND_LONG`，目标为 `1/191`：

| 命令 | MAV_CMD | 行为 |
|---|---:|---|
| 开始测量 | `MAV_CMD_USER_1` / 31010 | NX 转发给测厚管理节点/STM32 |
| 停止测量 | `MAV_CMD_USER_2` / 31011 | NX 停止测厚任务 |
| 校零 | `MAV_CMD_USER_3` / 31012 | NX 执行测厚传感器校零 |

NX 必须返回同一 command id 的 `COMMAND_ACK`。只有 NX 已接受业务操作后才返回 `MAV_RESULT_ACCEPTED (0)`；不支持、忙、参数错误或执行失败必须返回相应错误。MK32 在 900 ms 内没有收到 ACK 会重发，最多 3 次，重发时 `confirmation` 递增。

START/STOP/ZERO 只作用于测厚装置，不改变车辆解锁状态。

## 6. STM32 与测厚数据

NX 向 MK32 转发：

- `THICKNESS`、`THICK_MAX`、`THICK_MIN`、`THICK_AVG`
- `MEAS_RUN`、`SCAN_SIDE`、`FAULT`、`VEH_BATT`、`SPEED`
- `WAVE_A`、`WAVE_B` (`DEBUG_FLOAT_ARRAY`)
- `STM_LINK=1/0`

NX 在 3.5 秒内未收到 STM32 有效数据时必须发送 `STM_LINK=0`；恢复后发送 `STM_LINK=1`。不能在 STM32 断线时继续重复旧测量值。

## 7. NX 联调程序

先把仓库中的 `tools/nx_mavlink_smoke_test.py` 复制到 ORIN-NX。确认 NX 串口设备名后运行：

```bash
python3 nx_mavlink_smoke_test.py /dev/ttyUSB0 --baud 115200 --vehicle-state locked
```

MK32 界面应在 3.5 秒内显示：遥控器已连接、NX 下行正常、NX 双向正常、STM32 数据正常、车辆未解锁。点击开始/停止/校零时，NX 终端应输出对应命令及 `ACK accepted`。

断开任一物理链路，相关状态应在 3.5–5 秒内变为断开或待握手。

## 8. 真机检查

MK32：

```bash
adb shell ls -l /dev/ttyHS0
adb shell getenforce
adb shell logcat | grep -E 'climbwheel|ttyHS0'
```

ORIN-NX：

```bash
ls -l /dev/ttyUSB* /dev/ttyTHS* 2>/dev/null
stty -F /dev/ttyUSB0 115200 cs8 -cstopb -parenb -ixon -ixoff
```

关闭 UniGCS/QGC 等可能占用同一串口的应用。正式部署不要使用 `chmod 777` 或关闭 SELinux；MK32 串口权限应通过 SIYI SDK、系统签名或厂商白名单解决。
