package com.smartrice.server.realtime;

import com.smartrice.server.astrbot.AstrBotAlertSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class SprayWindInterlock {

	private final PreventionSafetyGate safety;
	private final DeviceActivityService devices;
	private final AstrBotAlertSender alerts;

	public SprayWindInterlock(PreventionSafetyGate safety, DeviceActivityService devices, AstrBotAlertSender alerts) {
		this.safety = safety;
		this.devices = devices;
		this.alerts = alerts;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onWind(WindReadingEvent event) {
		if (!safety.exceedsWindLimit(event.windSpeedMs())) return;
		WindInterlockOutcome outcome = devices.stopForWind(event.stationId(), event.windSpeedMs(), safety.maximumWind());
		if (!outcome.attempted()) return;
		String detail = "当前风速 %.2f m/s 超过上限 %.2f m/s。".formatted(
			event.windSpeedMs(), safety.maximumWind());
		alerts.sendDeviceFeedback(event.stationId(), DeviceCommandService.PUMP, false,
			outcome.stopped() ? "风速联锁关闭" : "风速联锁停止失败",
			outcome.stopped() ? detail + "已提交关闭喷药指令。" : detail + "请现场检查设备。");
	}
}
