package com.rcf.imas.modules.selectionreports;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SelectionReportsTurnoutSelectionPdfIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970701,'srAdmin970d','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        adminToken = jwt.issueFinalToken("970701", "srAdmin970d", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970701").update();
    }

    @Test
    void downloadTurnoutPdfBlockUsesTimestampedFilename() throws Exception {
        String body = """
                {
                  "year": "2025",
                  "type": "block",
                  "reportPayload": [
                    { "districtName": "Belagavi",
                      "blocks": [ { "label": "Block A", "called_count": 10, "appeared_count": 7, "turnout_percentage": 70 } ] }
                  ]
                }
                """;
        var result = mvc.perform(post("/api/selection-reports/download-turnout-pdf")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(content().contentType(MediaType.APPLICATION_PDF))
           .andExpect(header().string("Content-Disposition",
                   matchesRegex(".*filename=\"NMMS_Block_TurnOut_2025_\\d+\\.pdf\".*")))
           .andReturn();
        assertPdfMagicBytes(result.getResponse().getContentAsByteArray());
    }

    @Test
    void downloadSelectionPdfDistrictUsesTimestampedFilename() throws Exception {
        String body = """
                {
                  "year": "2025",
                  "type": "district",
                  "reportPayload": [
                    { "blocks": [ { "label": "Belagavi", "appeared_count": 20, "selected_count": 5, "selection_percentage": 25 } ] }
                  ]
                }
                """;
        var result = mvc.perform(post("/api/selection-reports/download-selection-pdf")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(content().contentType(MediaType.APPLICATION_PDF))
           .andExpect(header().string("Content-Disposition",
                   matchesRegex(".*filename=\"NMMS_District_Selection_2025_\\d+\\.pdf\".*")))
           .andReturn();
        assertPdfMagicBytes(result.getResponse().getContentAsByteArray());
    }

    private static void assertPdfMagicBytes(byte[] bytes) {
        String magic = new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
        org.junit.jupiter.api.Assertions.assertEquals("%PDF", magic);
    }
}
