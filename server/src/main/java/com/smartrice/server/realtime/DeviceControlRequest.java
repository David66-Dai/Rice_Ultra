package com.smartrice.server.realtime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DeviceControlRequest(
	String stationId,
	String device,
	@NotNull(message = "enabled 不能为空") Boolean enabled,
	@NotNull(message = "expectedRevision 不能为空") @Min(value = 0, message = "expectedRevision 不能为负数") Long expectedRevision
) {
}
