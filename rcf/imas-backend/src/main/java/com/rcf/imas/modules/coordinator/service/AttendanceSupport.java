package com.rcf.imas.modules.coordinator.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time/duration helpers ported VERBATIM from the live Node source
 * (server/controllers/coordinator/attendanceController.js:257-307, attendanceController.js:275-281). Every
 * regex, every branch condition, and the AM/PM 12-hour conversion logic must match Node's behavior exactly --
 * these functions are reused across getOrFindSession, previewCSVAttendance, and commitCSVAttendance. Static,
 * stateless -- not a Spring bean.
 */
public final class AttendanceSupport {

    private static final Pattern HHMM_OR_HHMMSS = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?$");
    private static final Pattern AMPM = Pattern.compile("(\\d+):(\\d+)\\s*(AM|PM)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HR = Pattern.compile("(\\d+)\\s*hr");
    private static final Pattern MIN = Pattern.compile("(\\d+)\\s*min");
    private static final Pattern SEC = Pattern.compile("(\\d+)\\s*sec");

    private AttendanceSupport() {}

    /** normalizeTimeToDB (attendanceController.js:284-307). */
    public static String normalizeTimeToDB(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.trim().equalsIgnoreCase("null")) {
            return "00:00:00";
        }
        // Node: raw.replace(/ | /g, " ").trim() -- strip narrow-no-break-space / nbsp
        String s = raw.replace(' ', ' ').replace(' ', ' ').trim();

        Matcher hhmm = HHMM_OR_HHMMSS.matcher(s);
        if (hhmm.matches()) {
            return s.length() == 5 ? s + ":00" : s;
        }

        Matcher ampm = AMPM.matcher(s);
        if (ampm.find()) {
            int hrs = Integer.parseInt(ampm.group(1));
            int mins = Integer.parseInt(ampm.group(2));
            String suffix = ampm.group(3).toUpperCase();
            if (suffix.equals("PM") && hrs < 12) hrs += 12;
            if (suffix.equals("AM") && hrs == 12) hrs = 0;
            return String.format("%02d:%02d:00", hrs, mins);
        }

        return "00:00:00";
    }

    /** timeToMinutes (attendanceController.js:275-281). */
    public static int timeToMinutes(String raw) {
        String timeStr = normalizeTimeToDB(raw);
        if (timeStr == null || timeStr.equals("00:00:00")) return 0;
        String[] parts = timeStr.split(":");
        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        return h * 60 + m;
    }

    /** parseDurationToMinutes (attendanceController.js:258-272). */
    public static int parseDurationToMinutes(String raw) {
        if (raw == null || raw.trim().equalsIgnoreCase("null")) return 0;
        String s = raw.toLowerCase();
        double totalMinutes = 0;

        Matcher hr = HR.matcher(s);
        if (hr.find()) totalMinutes += Integer.parseInt(hr.group(1)) * 60;

        Matcher min = MIN.matcher(s);
        if (min.find()) totalMinutes += Integer.parseInt(min.group(1));

        Matcher sec = SEC.matcher(s);
        if (sec.find()) totalMinutes += Integer.parseInt(sec.group(1)) / 60.0;

        return (int) Math.round(totalMinutes);
    }
}
