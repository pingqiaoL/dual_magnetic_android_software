# MK32 + ORIN-NX + STM32 开发入口

接手开发请先阅读 [详细开发文档（0.8.6）](DEVELOPMENT_GUIDE.md)。该文档按当前代码整理，区分已实现功能、实机验证结果与已知限制。

项目已经从早期“STM32 直连天空端、Android UART 只读”方案升级为：

- MK32 Android 与 ORIN-NX 通过 UART/MAVLink 双向通信。
- 测厚开始、停止和校零由 UART 命令控制，并要求 NX 返回 ACK。
- STM32 测量数据先进入 NX，再由 NX 转发到 MK32。
- 车辆解锁和急停只使用 SA/SBUS，不允许由 Android/UART 控制。
- 界面分别监测遥控链路、NX 双向握手和 STM32 数据链路。

完整协议、消息 ID、超时策略、接线与 NX 联调方法见：

- [NX UART 双向协议](NX_UART_BRIDGE_PROTOCOL.md)
- [Ubuntu/模拟器测试](UBUNTU_TESTING.md)

ORIN-NX 可先运行仓库中的 `tools/nx_mavlink_smoke_test.py`，在尚未接入完整 ROS 2/STM32 业务时独立验证 MK32 双向通信。
