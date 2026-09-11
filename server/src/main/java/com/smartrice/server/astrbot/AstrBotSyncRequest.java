package com.smartrice.server.astrbot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record AstrBotSyncRequest(@NotNull @Valid AstrBotIdentity identity) {
}
