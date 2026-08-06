package com.beaconculinary.api.admin;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AuthorizeResponse {
    private String authorizationToken;
    private Long adminId;
    private String adminName;
    private LocalDateTime expiresAt;
}
