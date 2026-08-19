package com.rcf.imas.modules.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoginAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("LOGIN_AUDIT");

    public void log(String userName, String status, String reason, String ip) {
        AUDIT.info("user_name={} status={} reason={} ip={}", userName, status, reason, ip);
    }
}
