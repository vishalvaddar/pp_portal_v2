package com.rcf.imas.modules.coordinator.service;

import com.rcf.imas.modules.coordinator.persistence.CoordinatorDashboardRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * attendanceAnalyticsController.getBatchWeeklyAverage -- N+1 loop (fine for a handful of batches per
 * coordinator, ported as-is per ground truth §4.9/§8's "flag, don't silently fix" instruction) + the
 * "last Mon-Sun" week window computed with java.time, matching JS `Date#getDay()` (Sunday=0) numbering.
 */
@Service
public class CoordinatorDashboardService {

    private final CoordinatorDashboardRepository dashboard;

    public CoordinatorDashboardService(CoordinatorDashboardRepository dashboard) {
        this.dashboard = dashboard;
    }

    public List<Map<String, Object>> batchWeeklyAverage(String coordinatorUserId) {
        LocalDate[] window = lastMondayToSundayWindow(LocalDate.now());
        String fromDate = window[0].toString();
        String toDate = window[1].toString();

        // #35's own batch list (Node's query, NO ORDER BY) -- NOT allBatchesForCoordinator (ORDER BY id DESC),
        // since this is a bare wire-visible array whose order must track Node's.
        List<Map<String, Object>> batches = dashboard.weeklyAvgBatchList(coordinatorUserId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> b : batches) {
            String batchId = String.valueOf(b.get("batch_id"));
            BigDecimal avg = dashboard.weeklyBatchAverage(batchId, fromDate, toDate);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch_id", b.get("batch_id"));
            row.put("batch_name", b.get("batch_name"));
            row.put("cohort_name", b.get("cohort_name"));
            row.put("avg_attendance", CoordinatorDashboardRepository.jsNumber(avg.doubleValue()));
            results.add(row);
        }
        return results;
    }

    /** JS: `const day = today.getDay(); const lastSunday = today - day days; const lastMonday = lastSunday - 6 days`.
     *  java.time's DayOfWeek is MONDAY=1..SUNDAY=7 -- `% 7` remaps SUNDAY to 0, matching JS's Sunday=0. */
    static LocalDate[] lastMondayToSundayWindow(LocalDate today) {
        int day = today.getDayOfWeek().getValue() % 7;
        LocalDate lastSunday = today.minusDays(day);
        LocalDate lastMonday = lastSunday.minusDays(6);
        return new LocalDate[]{lastMonday, lastSunday};
    }
}
