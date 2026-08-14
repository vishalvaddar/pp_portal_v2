package com.rcf.imas.modules.coordinator.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceSupportTest {

    @Test
    void normalizeTimeToDB_passesThroughHHmm() {
        assertThat(AttendanceSupport.normalizeTimeToDB("09:05")).isEqualTo("09:05:00");
    }

    @Test
    void normalizeTimeToDB_passesThroughHHmmss() {
        assertThat(AttendanceSupport.normalizeTimeToDB("09:05:30")).isEqualTo("09:05:30");
    }

    @Test
    void normalizeTimeToDB_convertsPM() {
        assertThat(AttendanceSupport.normalizeTimeToDB("2:15 PM")).isEqualTo("14:15:00");
    }

    @Test
    void normalizeTimeToDB_convertsPMAlreadyGreaterThan12IsUnchanged() {
        // PM and hrs already >= 12 must NOT add another 12 (guards the "hrs < 12" branch condition)
        assertThat(AttendanceSupport.normalizeTimeToDB("12:30 PM")).isEqualTo("12:30:00");
    }

    @Test
    void normalizeTimeToDB_convertsAM12ToZeroHundred() {
        assertThat(AttendanceSupport.normalizeTimeToDB("12:00 AM")).isEqualTo("00:00:00");
    }

    @Test
    void normalizeTimeToDB_convertsAMNormalHourUnchanged() {
        assertThat(AttendanceSupport.normalizeTimeToDB("9:05 AM")).isEqualTo("09:05:00");
    }

    @Test
    void normalizeTimeToDB_stripsNarrowNoBreakSpaceBeforeAmPm() {
        // Zoom/Excel exports commonly separate the time and AM/PM with U+202F (narrow no-break space)
        String raw = "9:05 AM";
        assertThat(AttendanceSupport.normalizeTimeToDB(raw)).isEqualTo("09:05:00");
    }

    @Test
    void normalizeTimeToDB_stripsRegularNoBreakSpace() {
        String raw = "9:05 AM";
        assertThat(AttendanceSupport.normalizeTimeToDB(raw)).isEqualTo("09:05:00");
    }

    @Test
    void normalizeTimeToDB_nullReturnsFallback() {
        assertThat(AttendanceSupport.normalizeTimeToDB(null)).isEqualTo("00:00:00");
    }

    @Test
    void normalizeTimeToDB_blankReturnsFallback() {
        assertThat(AttendanceSupport.normalizeTimeToDB("   ")).isEqualTo("00:00:00");
    }

    @Test
    void normalizeTimeToDB_literalStringNullReturnsFallback() {
        assertThat(AttendanceSupport.normalizeTimeToDB("null")).isEqualTo("00:00:00");
    }

    @Test
    void normalizeTimeToDB_unparseableReturnsFallback() {
        assertThat(AttendanceSupport.normalizeTimeToDB("garbage")).isEqualTo("00:00:00");
    }

    @Test
    void timeToMinutes_convertsNormalizedTime() {
        assertThat(AttendanceSupport.timeToMinutes("2:15 PM")).isEqualTo(14 * 60 + 15);
    }

    @Test
    void timeToMinutes_midnightFallbackIsZero() {
        assertThat(AttendanceSupport.timeToMinutes(null)).isEqualTo(0);
        assertThat(AttendanceSupport.timeToMinutes("garbage")).isEqualTo(0);
    }

    @Test
    void parseDurationToMinutes_hoursAndMinutes() {
        assertThat(AttendanceSupport.parseDurationToMinutes("1 hr 25 min")).isEqualTo(85);
    }

    @Test
    void parseDurationToMinutes_minutesOnly() {
        assertThat(AttendanceSupport.parseDurationToMinutes("45 min")).isEqualTo(45);
    }

    @Test
    void parseDurationToMinutes_secondsRoundUp() {
        // 40 sec = 0.666... min, rounds to 1
        assertThat(AttendanceSupport.parseDurationToMinutes("40 sec")).isEqualTo(1);
    }

    @Test
    void parseDurationToMinutes_hoursMinutesSecondsCombined() {
        // 1 hr = 60, 10 min = 10, 30 sec = 0.5 -> 70.5 -> Math.round -> 71 (round-half-up, matches JS Math.round)
        assertThat(AttendanceSupport.parseDurationToMinutes("1 hr 10 min 30 sec")).isEqualTo(71);
    }

    @Test
    void parseDurationToMinutes_nullIsZero() {
        assertThat(AttendanceSupport.parseDurationToMinutes(null)).isEqualTo(0);
    }

    @Test
    void parseDurationToMinutes_literalStringNullIsZero() {
        assertThat(AttendanceSupport.parseDurationToMinutes("null")).isEqualTo(0);
    }

    @Test
    void parseDurationToMinutes_unrecognizedTextIsZero() {
        assertThat(AttendanceSupport.parseDurationToMinutes("N/A")).isEqualTo(0);
    }

    @Test
    void parseDurationToMinutes_caseInsensitive() {
        assertThat(AttendanceSupport.parseDurationToMinutes("1 HR 5 MIN")).isEqualTo(65);
    }
}
