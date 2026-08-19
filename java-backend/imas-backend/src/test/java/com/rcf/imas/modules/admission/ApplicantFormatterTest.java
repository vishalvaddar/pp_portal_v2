package com.rcf.imas.modules.admission;

import com.rcf.imas.modules.admission.service.ApplicantFormatter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicantFormatterTest {

    private final ApplicantFormatter fmt = new ApplicantFormatter();

    @Test
    void mapsGenderCodesToWords() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gender", "M");
        fmt.formatResponse(m);
        assertThat(m.get("gender")).isEqualTo("Male");

        m.put("gender", "F"); fmt.formatResponse(m); assertThat(m.get("gender")).isEqualTo("Female");
        m.put("gender", "O"); fmt.formatResponse(m); assertThat(m.get("gender")).isEqualTo("Other");
    }

    @Test
    void leavesUnknownGenderUntouched() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gender", "Male"); // already a word → genderMap miss → unchanged
        fmt.formatResponse(m);
        assertThat(m.get("gender")).isEqualTo("Male");

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("gender", null);
        fmt.formatResponse(n);
        assertThat(n.get("gender")).isNull();
    }

    @Test
    void reformatsDobToIsoWhateverTheInputType() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dob", java.sql.Date.valueOf(LocalDate.of(2010, 3, 7)));
        fmt.formatResponse(m);
        assertThat(m.get("dob")).isEqualTo("2010-03-07");

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("dob", LocalDate.of(2010, 12, 31));
        fmt.formatResponse(n);
        assertThat(n.get("dob")).isEqualTo("2010-12-31");
    }

    @Test
    void formatResponseIgnoresMissingFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("student_name", "Asha");
        fmt.formatResponse(m); // no gender/dob keys → no-op
        assertThat(m.get("student_name")).isEqualTo("Asha");
    }

    @Test
    void controllerSanitizeDateIsLenientAndIso() {
        assertThat(fmt.sanitizeControllerDate("07-03-2010")).isEqualTo("2010-03-07"); // DD-MM-YYYY
        assertThat(fmt.sanitizeControllerDate("2010-03-07")).isEqualTo("2010-03-07"); // YYYY-MM-DD
        assertThat(fmt.sanitizeControllerDate("2010-03-07T00:00:00Z")).isEqualTo("2010-03-07"); // ISO_8601
        assertThat(fmt.sanitizeControllerDate("")).isNull();
        assertThat(fmt.sanitizeControllerDate(null)).isNull();
        assertThat(fmt.sanitizeControllerDate("not-a-date")).isNull();
    }

    @Test
    void bulkSanitizeDateIsStrictNoIsoFallback() {
        assertThat(fmt.sanitizeBulkDate("07-03-2010")).isEqualTo("2010-03-07");
        assertThat(fmt.sanitizeBulkDate("2010-03-07")).isEqualTo("2010-03-07");
        // strict mode: ISO datetime is NOT accepted by the bulk parser
        assertThat(fmt.sanitizeBulkDate("2010-03-07T00:00:00Z")).isNull();
        assertThat(fmt.sanitizeBulkDate("bad")).isNull();
        assertThat(fmt.sanitizeBulkDate(null)).isNull();
    }
}
