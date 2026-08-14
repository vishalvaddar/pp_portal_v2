package com.rcf.imas.modules.identity.persistence;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordTest {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder =
                new BCryptPasswordEncoder(
                        BCryptPasswordEncoder.BCryptVersion.$2B,
                        10
                );

        String password = "admin123";

        System.out.println(encoder.encode(password));
    }
}