package com.rcf.imas;

import com.rcf.imas.platform.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class ImasApplication {
    public static void main(String[] args) {
        SpringApplication.run(ImasApplication.class, args);
    }
}
