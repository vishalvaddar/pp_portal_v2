package com.rcf.imas.modules.tracking;

import com.rcf.imas.PgIntegrationTest;
import com.rcf.imas.platform.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ActiveTimetablePdfIT extends PgIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @Test
    void downloadPdfRendersPostedDataOnlyNoDbQuery() throws Exception {
        String adminToken = jwt.issueFinalToken("962001", "ttpAdmin962", "ADMIN");
        String body = """
            {
              "timetableData": [
                {"teacher_name":"Teacher X","subject_name":"Maths","batch_name":"Batch A","day_of_week":"monday","start_time":"09:00","end_time":"10:00"},
                {"teacher_name":"Teacher Y","subject_name":"Science","batch_name":"Batch B","day_of_week":"tuesday","start_time":"11:00","end_time":"12:00"}
              ],
              "cohortName": "Cohort 962",
              "viewType": "combined",
              "fileName": "TIMETABLE_Cohort_962.pdf"
            }
            """;
        var result = mvc.perform(post("/api/activetimetable/download-pdf").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Type", "application/pdf"))
           .andExpect(header().string("Content-Disposition", "attachment; filename=TIMETABLE_Cohort_962.pdf"))
           .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(pdf.length).isGreaterThan(100);
        org.assertj.core.api.Assertions.assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF"); // valid PDF header
    }

    @Test
    void downloadPdfDefaultsFilenameFromCohort() throws Exception {
        String adminToken = jwt.issueFinalToken("962002", "ttpAdmin962b", "ADMIN");
        String body = """
            {"timetableData":[],"cohortName":"Cohort 962","viewType":"combined"}
            """;
        mvc.perform(post("/api/activetimetable/download-pdf").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk())
           .andExpect(header().string("Content-Disposition", "attachment; filename=TIMETABLE_Cohort 962.pdf"));
    }
}
