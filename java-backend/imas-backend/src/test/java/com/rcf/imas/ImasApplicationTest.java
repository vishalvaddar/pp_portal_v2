package com.rcf.imas;

import org.junit.jupiter.api.Test;

class ImasApplicationTest {
    @Test
    void mainClassExists() {
        // Full context boot is covered by PgIntegrationTest subclasses (Task 2).
        org.assertj.core.api.Assertions.assertThat(ImasApplication.class).isNotNull();
    }
}
