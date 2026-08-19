package com.rcf.imas.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    record Sample(String userName, LocalDate examDate) {}

    @Test
    void serializesSnakeCaseAndIsoDates() throws Exception {
        ObjectMapper om = new JacksonConfig().objectMapper();
        String json = om.writeValueAsString(new Sample("admin", LocalDate.of(2026, 7, 5)));
        assertThat(json).isEqualTo("{\"user_name\":\"admin\",\"exam_date\":\"2026-07-05\"}");
    }

    @Test
    void deserializesSnakeCase() throws Exception {
        ObjectMapper om = new JacksonConfig().objectMapper();
        Sample s = om.readValue("{\"user_name\":\"x\",\"exam_date\":\"2026-01-31\"}", Sample.class);
        assertThat(s.userName()).isEqualTo("x");
    }
}
