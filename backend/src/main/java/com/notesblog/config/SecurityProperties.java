package com.notesblog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security")
public record SecurityProperties(String jwtSecret, long jwtExpirationHours,
                                 String adminUsername, String adminPassword) {
}
