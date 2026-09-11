package com.smartrice.server.realtime;

import com.fazecast.jSerialComm.SerialPort;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Instant;


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
	private final RealtimeSensorIngestionService ingestion;
	private final WeatherRainfallService rainfallService;
	private final String portName;
	private final int baudRate;
	private final String configuredStationId;

	private volatile boolean running;
	private volatile SerialPort activePort;
	private Thread worker;
	private final Object writeLock = new Object();

	public SerialSensorCollector(RealtimeSensorIngestionService ingestion,
			WeatherRainfallService rainfallService,
			@Value("${app.realtime.serial.port:COM4}") String portName,
			@Value("${app.realtime.serial.baud-rate:9600}") int baudRate,
			@Value("${app.realtime.serial.station-id:S01}") String configuredStationId) {
		this.ingestion = ingestion;
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

	/** Reports the actual port connection, without opening it or writing any bytes. */
	public boolean isConnected() {
		SerialPort port = activePort;
		return port != null && port.isOpen();
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
