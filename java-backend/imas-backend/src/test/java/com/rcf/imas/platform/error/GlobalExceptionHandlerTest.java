package com.rcf.imas.platform.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionRendersChosenKeyAndStatus() {
        ResponseEntity<Object> res =
                handler.handleApi(ApiException.error(401, "Invalid credentials"));
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().toString()).isEqualTo("{error=Invalid credentials}");
    }

    @Test
    void messageKeyVariant() {
        ResponseEntity<Object> res =
                handler.handleApi(ApiException.message(409, "Username already exists."));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().toString()).isEqualTo("{message=Username already exists.}");
    }

    @Test
    void extraFieldsAreIncluded() {
        ApiException ex = ApiException.error(401, "Session expired. Please login again.")
                .with("code", "PRE_AUTH_TOKEN_EXPIRED");
        ResponseEntity<Object> res = handler.handleApi(ex);
        assertThat(res.getBody().toString())
                .contains("code=PRE_AUTH_TOKEN_EXPIRED")
                .contains("error=Session expired. Please login again.");
    }

    @Test
    void unexpectedExceptionIs500WithGenericBody() {
        ResponseEntity<Object> res = handler.handleUnexpected(new RuntimeException("boom"));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().toString()).isEqualTo("{error=Internal Server Error}");
    }
}
