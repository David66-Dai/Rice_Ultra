package com.smartrice.server.camera;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 网络摄像头抓拍代理的边界：只允许访问配置内的网段，并限制超时与体积。 */
@Component
@ConfigurationProperties(prefix = "app.camera")
public class CameraProxyProperties {

	/** 允许代理的摄像头地址网段，逗号分隔；默认只放行内网，回环与公网需显式配置。 */
	private String allowedCidrs = "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

	private int connectTimeoutMs = 4_000;

	private int readTimeoutMs = 8_000;

	private int maxImageBytes = 8 * 1024 * 1024;

	public List<CidrRange> getAllowedRanges() {
		List<CidrRange> ranges = new ArrayList<>();
		for (String part : allowedCidrs.split(",")) {
			String text = part.trim();
			if (!text.isEmpty()) {
				ranges.add(CidrRange.parse(text));
			}
		}
		return ranges;
	}

	public String getAllowedCidrs() {
		return allowedCidrs;
	}

	public void setAllowedCidrs(String allowedCidrs) {
		this.allowedCidrs = allowedCidrs;
	}

	public int getConnectTimeoutMs() {
		return connectTimeoutMs;
	}

	public void setConnectTimeoutMs(int connectTimeoutMs) {
		this.connectTimeoutMs = connectTimeoutMs;
	}

	public int getReadTimeoutMs() {
		return readTimeoutMs;
	}

	public void setReadTimeoutMs(int readTimeoutMs) {
		this.readTimeoutMs = readTimeoutMs;
	}

	public int getMaxImageBytes() {
		return maxImageBytes;
	}

	public void setMaxImageBytes(int maxImageBytes) {
		this.maxImageBytes = maxImageBytes;
	}
}
