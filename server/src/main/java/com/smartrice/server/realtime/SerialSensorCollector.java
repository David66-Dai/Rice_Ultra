package com.smartrice.server.realtime;

import com.fazecast.jSerialComm.SerialPort;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.realtime.serial.enabled", havingValue = "true")
public class SerialSensorCollector implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(SerialSensorCollector.class);
	private static final Charset DEVICE_CHARSET = Charset.forName("GBK");
	private static final Pattern FRAME_PATTERN = Pattern.compile(
		"当前站点\\s*(\\d+)\\s*(.+?)为[：:]\\s*(.*)", Pattern.CASE_INSENSITIVE
	);
	private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

	private final RealtimeSensorReadingRepository repository;
	private final WeatherRainfallService rainfallService;
	private final String portName;
	private final int baudRate;
	private final String configuredStationId;

	private volatile boolean running;
	private volatile SerialPort activePort;
	private Thread worker;
	private final Object writeLock = new Object();

	public SerialSensorCollector(RealtimeSensorReadingRepository repository,
			WeatherRainfallService rainfallService,
			@Value("${app.realtime.serial.port:COM4}") String portName,
			@Value("${app.realtime.serial.baud-rate:9600}") int baudRate,
			@Value("${app.realtime.serial.station-id:S01}") String configuredStationId) {
		this.repository = repository;
		this.rainfallService = rainfallService;
		this.portName = portName;
		this.baudRate = baudRate;
		this.configuredStationId = configuredStationId.trim().toUpperCase();
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		worker = Thread.ofPlatform()
			.name("serial-sensor-" + portName)
			.daemon(true)
			.start(this::readLoop);
	}

	@Override
	public synchronized void stop() {
		running = false;
		SerialPort port = activePort;
		if (port != null && port.isOpen()) {
			port.closePort();
		}
		if (worker != null) {
			worker.interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	public void sendCommand(int commandCode) {
		byte[] command = {(byte) 0xFA, (byte) commandCode};
		synchronized (writeLock) {
			SerialPort port = activePort;
			if (port == null || !port.isOpen()) {
				throw new IllegalStateException("串口 " + portName + " 当前未连接");
			}
			int written = port.writeBytes(command, command.length);
			if (written != command.length) {
				throw new IllegalStateException("串口指令发送不完整");
			}
		}
		log.info("已向 {} 发送设备控制指令 FA{}", portName, "%02X".formatted(commandCode));
	}

	private void readLoop() {
		while (running) {
			SerialPort port = SerialPort.getCommPort(portName);
			activePort = port;
			port.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
			port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);

			if (!port.openPort()) {
				log.warn("无法打开串口 {}，5 秒后重试", portName);
				pauseBeforeRetry();
				continue;
			}

			log.info("串口采集已启动: {} / {} / 8N1 / GBK", portName, baudRate);
			SensorFrameAccumulator accumulator = new SensorFrameAccumulator();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(port.getInputStream(), DEVICE_CHARSET))) {
				while (running && port.isOpen()) {
					String line = reader.readLine();
					if (line == null) {
						break;
					}
					parseFrame(line.replace("\u0000", "").trim(), accumulator);
					if (accumulator.complete()) {
						saveReading(accumulator);
						accumulator.clear();
					}
				}
			}
			catch (Exception ex) {
				if (running) {
					log.warn("串口 {} 读取中断，准备重连: {}", portName, ex.getMessage());
				}
			}
			finally {
				if (port.isOpen()) {
					port.closePort();
				}
				activePort = null;
			}
			pauseBeforeRetry();
		}
	}

	private void parseFrame(String line, SensorFrameAccumulator accumulator) {
		Matcher frame = FRAME_PATTERN.matcher(line);
		if (!frame.find()) {
			if (!line.isBlank()) {
				log.debug("忽略无法解析的串口帧: {}", line);
			}
			return;
		}

		int stationNumber = Integer.parseInt(frame.group(1));
		if (stationNumber < 1 || stationNumber > 10) {
			log.warn("忽略站点编号超出范围的串口帧: {}", line);
			return;
		}
		Matcher number = NUMBER_PATTERN.matcher(frame.group(3));
		if (!number.find()) {
			log.debug("串口帧没有数值: {}", line);
			return;
		}

		String stationId = "S%02d".formatted(stationNumber);
		if (!stationId.equals(configuredStationId)) {
			log.debug("忽略非配置站点的串口帧: {}，当前只采集 {}", stationId, configuredStationId);
			return;
		}
		String metric = frame.group(2).trim();
		double value = Double.parseDouble(number.group());
		accumulator.useStation(stationId);

		if (metric.contains("土壤温度") || metric.contains("土壤湿度")) {
			return;
		}
		if (metric.contains("电导率")) {
			accumulator.soilEcMsCm = value;
		}
		else if (metric.toUpperCase().contains("PH") || metric.contains("酸碱")) {
			accumulator.soilPh = value;
		}
		else if (metric.contains("氮肥")) {
			accumulator.soilNitrogenMgKg = value;
		}
		else if (metric.contains("磷肥")) {
			accumulator.soilPhosphorusMgKg = value;
		}
		else if (metric.contains("钾肥")) {
			accumulator.soilPotassiumMgKg = value;
		}
		else if (metric.contains("光照")) {
			accumulator.lightKlx = value / 1000;
		}
		else if (metric.contains("风速")) {
			accumulator.windSpeedMs = value;
		}
		else if (metric.contains("温度")) {
			accumulator.airTemperatureC = value;
		}
		else if (metric.contains("湿度")) {
			accumulator.airHumidityPercent = value;
		}
	}

	private void saveReading(SensorFrameAccumulator values) {
		RealtimeSensorReading reading = new RealtimeSensorReading();
		reading.stationId = values.stationId;
		reading.sampledAt = Instant.now();
		reading.lightKlx = values.lightKlx;
		reading.windSpeedMs = values.windSpeedMs;
		reading.rainfallMmH = rainfallService.currentHourlyRainfall();
		reading.airTemperatureC = values.airTemperatureC;
		reading.airHumidityPercent = values.airHumidityPercent;
		reading.soilNitrogenMgKg = values.soilNitrogenMgKg;
		reading.soilPhosphorusMgKg = values.soilPhosphorusMgKg;
		reading.soilPotassiumMgKg = values.soilPotassiumMgKg;
		reading.soilPh = values.soilPh;
		reading.soilEcMsCm = values.soilEcMsCm;
		repository.save(reading);
		log.debug("已保存 {} 实时传感器记录，采样时间 {}", reading.stationId, reading.sampledAt);
	}

	private void pauseBeforeRetry() {
		if (!running) {
			return;
		}
		try {
			Thread.sleep(5_000);
		}
		catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class SensorFrameAccumulator {
		private String stationId;
		private Double lightKlx;
		private Double windSpeedMs;
		private Double airTemperatureC;
		private Double airHumidityPercent;
		private Double soilNitrogenMgKg;
		private Double soilPhosphorusMgKg;
		private Double soilPotassiumMgKg;
		private Double soilPh;
		private Double soilEcMsCm;

		void useStation(String nextStationId) {
			if (stationId != null && !stationId.equals(nextStationId)) {
				clear();
			}
			stationId = nextStationId;
		}

		boolean complete() {
			return stationId != null
				&& lightKlx != null
				&& windSpeedMs != null
				&& airTemperatureC != null
				&& airHumidityPercent != null
				&& soilNitrogenMgKg != null
				&& soilPhosphorusMgKg != null
				&& soilPotassiumMgKg != null
				&& soilPh != null
				&& soilEcMsCm != null;
		}

		void clear() {
			stationId = null;
			lightKlx = null;
			windSpeedMs = null;
			airTemperatureC = null;
			airHumidityPercent = null;
			soilNitrogenMgKg = null;
			soilPhosphorusMgKg = null;
			soilPotassiumMgKg = null;
			soilPh = null;
			soilEcMsCm = null;
		}
	}
}
