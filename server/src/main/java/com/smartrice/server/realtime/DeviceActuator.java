package com.smartrice.server.realtime;

/** 下发站点设备开关指令；无串口时可以不提供实现。 */
public interface DeviceActuator {

	void sendCommand(int commandCode);
}
