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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class SelectionReportsNmmsPdfIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;

    String adminToken;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("INSERT INTO pp.\"user\"(user_id, user_name, enc_password, locked_yn) VALUES (970601,'srAdmin970c','x','N')").update();
        jdbc.sql("SELECT setval('pp.user_id_seq', (SELECT MAX(user_id)::bigint FROM pp.\"user\"))").query(Long.class).single();
        adminToken = jwt.issueFinalToken("970601", "srAdmin970c", "ADMIN");
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        jdbc.sql("DELETE FROM pp.\"user\" WHERE user_id = 970601").update();
    }

    @Test
    void downloadNmmsPdfDistrictReturnsPdfBytesWithCorrectFilename() throws Exception {
        String body = """
                {
                  "year": "2025",
                  "type": "district",
                  "reportPayload": [
                    { "blocks": [
                        { "label": "Belagavi", "applicant_count": 5 },
                        { "label": "Bagalkot", "applicant_count": 3 }
                    ] }
                  ]
                }
                """;
        var result = mvc.perform(post("/api/selection-reports/download-pdf")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(content().contentType(MediaType.APPLICATION_PDF))
           .andExpect(header().string("Content-Disposition", containsString("filename=\"NMMS_District_Report_2025.pdf\"")))
           .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertPdfMagicBytes(bytes);
    }

    @Test
    void downloadNmmsPdfBlockUsesBlockFilenameAndMultiplePages() throws Exception {
        String body = """
                {
                  "year": "2025",
                  "type": "block",
                  "reportPayload": [
                    { "districtName": "Belagavi", "blocks": [ { "label": "Block A", "applicant_count": 2 } ] },
                    { "districtName": "Bagalkot", "blocks": [ { "label": "Block B", "applicant_count": 1 } ] }
                  ]
                }
                """;
        var result = mvc.perform(post("/api/selection-reports/download-pdf")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", containsString("filename=\"NMMS_Block_Report_2025.pdf\"")))
           .andReturn();

        assertPdfMagicBytes(result.getResponse().getContentAsByteArray());
    }

    private static void assertPdfMagicBytes(byte[] bytes) {
        String magic = new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
        org.junit.jupiter.api.Assertions.assertEquals("%PDF", magic);
    }
}
