package com.smartrice.server.realtime;

/** 下发站点设备开关指令；无串口时可以不提供实现。 */
public interface DeviceActuator {

	/** Read-only readiness probe. Implementations must not open hardware as a side effect. */
	default boolean isAvailable() {
		return true;
	}

	void sendCommand(int commandCode);
}
