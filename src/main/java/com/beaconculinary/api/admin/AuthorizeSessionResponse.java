package com.beaconculinary.api.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AuthorizeSessionResponse {
    private String sessionToken;
    private Long adminId;
    private LocalDateTime expiresAt;
}
