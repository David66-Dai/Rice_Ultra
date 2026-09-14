package com.smartrice.server.astrbot;

public record AstrBotQueryStatus(
	String username,
	String displayName,
	String defaultStation,
	int maxRangeDays,
	String dataSource
) {
}
