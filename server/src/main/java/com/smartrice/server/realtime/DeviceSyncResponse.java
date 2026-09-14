package com.smartrice.server.realtime;

import com.smartrice.server.notifications.PlatformNotification;
import java.util.List;

public record DeviceSyncResponse(String cursor, boolean canControl, boolean available,
		List<DeviceState> devices, PreventionPolicyState preventionPolicy,
		List<PlatformNotification> notifications, long unreadCount) {
}
