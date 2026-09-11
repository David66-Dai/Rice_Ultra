package com.smartrice.server.auth.dto;

import jakarta.validation.constraints.Size;

public record LogoutRequest(@Size(max = 256) String rememberToken) {
}
