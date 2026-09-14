package com.smartrice.server.realtime;

import com.fazecast.jSerialComm.SerialPort;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.realtime.serial.enabled", havingValue = "true")
public class SerialSensorCollector implements SmartLifecycle, DeviceActuator {

	private static final Logger log = LoggerFactory.getLogger(SerialSensorCollector.class);
	private static final Charset DEVICE_CHARSET = Charset.forName("GBK");
	private final RealtimeSensorIngestionService ingestion;
	private final WeatherRainfallService rainfallService;
	/** 候选串口，按配置顺序尝试；采集器重新枚举后换了 COM 号也无需改配置。 */
	private final List<String> portNames;
	private final int baudRate;
	private final String configuredStationId;

	private volatile boolean running;
	private volatile SerialPort activePort;
	private Thread worker;
	private final Object writeLock = new Object();

	public SerialSensorCollector(RealtimeSensorIngestionService ingestion,
			WeatherRainfallService rainfallService,
			@Value("${app.realtime.serial.port:COM4,COM5}") String configuredPorts,
			@Value("${app.realtime.serial.baud-rate:9600}") int baudRate,
			@Value("${app.realtime.serial.station-id:S01}") String configuredStationId) {
		this.ingestion = ingestion;
		this.rainfallService = rainfallService;
		this.portNames = parsePortNames(configuredPorts);
		this.baudRate = baudRate;
		this.configuredStationId = configuredStationId.trim().toUpperCase();
	}

	/** 支持单个串口和以逗号/分号分隔的多个候选串口，例如 "COM4,COM5"。 */
	private static List<String> parsePortNames(String configuredPorts) {
		List<String> names = Arrays.stream(configuredPorts.split("[,;]"))
			.map(String::trim)
			.filter(name -> !name.isEmpty())
			.distinct()
			.toList();
		if (names.isEmpty()) {
			throw new IllegalArgumentException("app.realtime.serial.port 未配置任何串口");
		}
		return names;
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		worker = Thread.ofPlatform()
			.name("serial-sensor-" + String.join("-", portNames))
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

	/** Reports the actual port connection, without opening it or writing any bytes. */
	@Override
	public boolean isAvailable() {
		return isConnected();
	}

	public boolean isConnected() {
		SerialPort port = activePort;
		return port != null && port.isOpen();
	}

	public void sendCommand(int commandCode) {
		byte[] command = {(byte) 0xFA, (byte) commandCode};
		String target;
		synchronized (writeLock) {
			SerialPort port = activePort;
			if (port == null || !port.isOpen()) {
				throw new IllegalStateException("串口 " + describePorts() + " 当前未连接");
			}
			target = port.getSystemPortName();
			int written = port.writeBytes(command, command.length);
			if (written != command.length) {
				throw new IllegalStateException("串口指令发送不完整");
			}
		}
		log.info("已向 {} 发送设备控制指令 FA{}", target, "%02X".formatted(commandCode));
	}

	private void readLoop() {
		while (running) {
			SerialPort port = openFirstAvailablePort();
			if (port == null) {
				pauseBeforeRetry();
				continue;
			}
			String portName = port.getSystemPortName();
			activePort = port;

			log.info("串口采集已启动: {} / {} / 8N1 / GBK", portName, baudRate);
			SerialSensorFrameParser parser = new SerialSensorFrameParser(configuredStationId);
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(port.getInputStream(), DEVICE_CHARSET))) {
				while (running && port.isOpen()) {
					String line = reader.readLine();
					if (line == null) {
						break;
					}
					RealtimeSensorReading reading = parser.accept(line, Instant.now());
					if (reading != null) {
						reading.rainfallMmH = rainfallService.currentHourlyRainfall();
						ingestion.save(reading);
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

	/** 按顺序尝试候选串口，返回第一个成功打开的；设备换到另一个 COM 号时自动跟随。 */
	private SerialPort openFirstAvailablePort() {
		for (String portName : portNames) {
			if (!running) {
				return null;
			}
			SerialPort port = SerialPort.getCommPort(portName);
			port.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
			port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
			if (port.openPort()) {
				return port;
			}
			log.debug("串口 {} 暂不可用，尝试下一个候选", portName);
		}
		log.warn("候选串口 {} 均无法打开，5 秒后重试", describePorts());
		return null;
	}

	private String describePorts() {
		return String.join("/", portNames);
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

}
