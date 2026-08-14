package com.rcf.imas;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

@SpringBootTest
public abstract class PgIntegrationTest {

    // One real PostgreSQL (bundled binary, no Docker) for the whole test JVM.
    static final EmbeddedPostgres PG;
    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.flyway.baseline-on-migrate", () -> "false"); // empty DB: actually run V1
        r.add("imas.jwt.secret", () -> "test-secret-test-secret-test-secret-1234");
    }
}
