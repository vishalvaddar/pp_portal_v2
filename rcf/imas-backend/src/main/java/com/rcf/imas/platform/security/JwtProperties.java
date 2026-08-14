package com.rcf.imas.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imas.jwt")
public record JwtProperties(String secret, String expiresIn, String preAuthExpiresIn) {}
