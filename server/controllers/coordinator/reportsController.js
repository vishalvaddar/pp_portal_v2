const { ReportsModel } = require("../../models/coordinator/reportsModel");
const pool = require("../../config/db");


const requireAuth = (req, res, next) => {
    const auth = req.headers.authorization;
    if (!auth) return res.status(401).json({ error: "Missing authorization" });
    next();
};



const getAttendanceReport = async (req, res) => {
    const { batchId, fromDate, toDate } = req.query;

    try {
        // 1. Batch Info
        const batchInfoQuery = `
            SELECT b.batch_name, c.cohort_name
            FROM pp.batch b
            JOIN pp.cohort c ON c.cohort_number = b.cohort_number
            WHERE b.batch_id = $1;
        `;
        const batchInfoRes = await ReportsModel.query(batchInfoQuery, [batchId]);
        const batch_name = batchInfoRes.rows[0]?.batch_name || "";
        const cohort_name = batchInfoRes.rows[0]?.cohort_name || "";

        // 2. Conducted (structured)
        const conductedQuery = `
            WITH batch_classrooms AS (
                SELECT classroom_id FROM pp.classroom_batch WHERE batch_id = $1
            )
            SELECT 
                subj.subject_code,
                t.teacher_name,
                COUNT(DISTINCT cs.session_id) AS conducted
            FROM pp.class_session cs
            JOIN batch_classrooms bc ON bc.classroom_id = cs.classroom_id
            JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
            JOIN pp.subject subj ON subj.subject_id = c.subject_id
            LEFT JOIN pp.teacher t ON t.teacher_id = cs.teacher_id
            WHERE cs.session_date BETWEEN $2::date AND $3::date
            GROUP BY subj.subject_code, t.teacher_name;
        `;

        const conductedRes = await ReportsModel.query(conductedQuery, [batchId, fromDate, toDate]);

        // 🔥 GROUP BY SUBJECT
        const conductedStructured = {};

        conductedRes.rows.forEach(r => {
            if (!conductedStructured[r.subject_code]) {
                conductedStructured[r.subject_code] = [];
            }

            conductedStructured[r.subject_code].push({
                teacher_name: r.teacher_name,
                conducted: parseInt(r.conducted, 10)
            });
        });

        // 3. Student Attendance
        const studentQuery = `
            WITH batch_classrooms AS (
                SELECT classroom_id FROM pp.classroom_batch WHERE batch_id = $1
            ),
            batch_students AS (
                SELECT sm.student_id, sm.student_name, ins.inactive_date
                FROM pp.student_master sm
                LEFT JOIN pp.inactive_students ins ON ins.student_id = sm.student_id
                WHERE sm.batch_id = $1
                  AND (ins.student_id IS NULL OR ins.inactive_date > $2::date)
            ),
            sessions AS (
                SELECT 
                    cs.session_id,
                    cs.teacher_id,
                    subj.subject_code,
                    cs.session_date
                FROM pp.class_session cs
                JOIN batch_classrooms bc ON bc.classroom_id = cs.classroom_id
                JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                JOIN pp.subject subj ON subj.subject_id = c.subject_id
                WHERE cs.session_date BETWEEN $2::date AND $3::date
            ),
            student_sessions AS (
                SELECT 
                    bs.student_id,
                    bs.student_name,
                    bs.inactive_date,
                    s.session_id,
                    s.subject_code,
                    s.teacher_id,
                    s.session_date
                FROM sessions s
                JOIN batch_students bs
                    ON (bs.inactive_date IS NULL OR s.session_date < bs.inactive_date)
            )
            SELECT 
                ss.student_id,
                ss.student_name,
                ss.subject_code,
                t.teacher_name,
                COUNT(DISTINCT ss.session_id) FILTER (
                    WHERE sa.status IN ('PRESENT','LATE JOINED','LEAVE')
                ) AS attended
            FROM student_sessions ss
            LEFT JOIN pp.student_attendance sa 
                ON sa.session_id = ss.session_id 
                AND sa.student_id = ss.student_id
            LEFT JOIN pp.teacher t ON t.teacher_id = ss.teacher_id
            GROUP BY ss.student_id, ss.student_name, ss.subject_code, t.teacher_name
            ORDER BY ss.student_name;
        `;

        const stRes = await ReportsModel.query(studentQuery, [batchId, fromDate, toDate]);

        // 🔥 STRUCTURE STUDENTS
        const studentMap = {};

        stRes.rows.forEach(r => {
            if (!studentMap[r.student_id]) {
                studentMap[r.student_id] = {
                    id: r.student_id,
                    name: r.student_name,
                    subjects: {}
                };
            }

            if (!studentMap[r.student_id].subjects[r.subject_code]) {
                studentMap[r.student_id].subjects[r.subject_code] = {};
            }

            studentMap[r.student_id].subjects[r.subject_code][r.teacher_name] = parseInt(r.attended || 0, 10);
        });

        const students = Object.values(studentMap);

        res.json({
            reportId: `ATT-${batchId}-${fromDate}-${toDate}`,
            cohort_name,
            batch_name,
            subjects: conductedStructured,
            students
        });

    } catch (err) {
        console.error(err);
        res.status(500).json({ error: "Server error generating attendance report" });
    }
};

const getAbsenteesReport = async (req, res) => {
    const { batch_id, fromDate, toDate } = req.query;
    if (!batch_id || !fromDate || !toDate)
        return res.status(400).json({ error: "batch_id, fromDate, and toDate required" });

    try {
        const missedQuery = `
            WITH dates AS (
                SELECT generate_series($2::date, $3::date, interval '1 day')::date AS dt
            ),
            batch_classrooms AS (
                SELECT cb.classroom_id FROM pp.classroom_batch cb WHERE cb.batch_id = $1
            ),
            scheduled AS (
                SELECT c.classroom_id, s.subject_code, d.dt
                FROM pp.classroom c
                JOIN batch_classrooms bc ON bc.classroom_id = c.classroom_id
                JOIN pp.timetable t ON t.classroom_id = c.classroom_id
                JOIN dates d ON trim(upper(t.day_of_week)) = trim(upper(to_char(d.dt, 'DAY')))
                JOIN pp.subject s ON s.subject_id = c.subject_id
            ),
            attended AS (
                SELECT sa.student_id, c.subject_id, cs.session_date AS date, sa.status
                FROM pp.student_attendance sa
                JOIN pp.class_session cs ON cs.session_id = sa.session_id
                JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
                WHERE cs.session_date BETWEEN $2::date AND $3::date
            ),
            compare AS (
                SELECT bs.student_id, bs.student_name, sch.subject_code,
                       COUNT(*) AS scheduled_count,
                       COUNT(att.*) FILTER (WHERE att.status IN ('PRESENT','LATE JOINED','LEAVE')) AS attended_count,
                       ARRAY_AGG(CASE WHEN att.status = 'ABSENT' THEN att.date END)
                         FILTER (WHERE att.status = 'ABSENT') AS absent_dates
                FROM (SELECT sm.student_id, sm.student_name FROM pp.student_master sm WHERE sm.batch_id = $1) bs
                JOIN scheduled sch ON TRUE
                LEFT JOIN attended att
                  ON att.student_id = bs.student_id
                  AND att.subject_id = (SELECT subject_id FROM pp.subject WHERE subject_code = sch.subject_code LIMIT 1)
                  AND att.date = sch.dt
                GROUP BY bs.student_id, bs.student_name, sch.subject_code
            )
            SELECT student_id, student_name, subject_code AS subject, scheduled_count, attended_count,
                   (scheduled_count - attended_count) AS missed_count,
                   COALESCE(absent_dates, '{}') AS missed_dates
            FROM compare
            WHERE (scheduled_count - attended_count) > 0
            ORDER BY missed_count DESC;
        `;
        const missedRes = await ReportsModel.query(missedQuery, [batch_id, fromDate, toDate]);
        const grouped = {};
        for (const r of missedRes.rows) {
            const sid = r.student_id;
            if (!grouped[sid])
                grouped[sid] = { id: sid, name: r.student_name, missedClasses: [], totalMissed: 0 };
            grouped[sid].missedClasses.push({
                subject: r.subject,
                count: parseInt(r.missed_count, 10),
                dates: (r.missed_dates || []).filter(Boolean),
            });
            grouped[sid].totalMissed += parseInt(r.missed_count, 10);
        }
        res.json({ reportId: `ABS-${batch_id}-${fromDate}-${toDate}`, students: Object.values(grouped) });
    } catch (err) {
        res.status(500).json({ error: "Server error generating absentees report" });
    }
};


const getTeacherLoad = async (req, res) => {
    try {
        const { fromDate, toDate } = req.query;

        let query = `
            SELECT 
                t.teacher_name AS teacher, 
                b.cohort_number AS cohort,
                c.classroom_name AS classroom, 
                s.subject_code AS subject,
                COUNT(DISTINCT cs.session_id) AS total_classes_taken

            FROM pp.class_session cs

            JOIN pp.classroom c 
                ON cs.classroom_id = c.classroom_id

            JOIN pp.classroom_batch cb 
                ON c.classroom_id = cb.classroom_id

            JOIN pp.batch b 
                ON cb.batch_id = b.batch_id

            -- 🔥 FIX HERE
            JOIN pp.teacher t 
                ON cs.teacher_id = t.teacher_id

            JOIN pp.subject s 
                ON c.subject_id = s.subject_id
        `;

        const params = [];

        if (fromDate && toDate) {
            query += ` WHERE cs.session_date BETWEEN $1::date AND $2::date `;
            params.push(fromDate, toDate);
        }

        query += `
            GROUP BY 
                t.teacher_name, 
                b.cohort_number, 
                c.classroom_name, 
                s.subject_code

            ORDER BY 
                t.teacher_name, 
                b.cohort_number, 
                c.classroom_name
        `;

        const { rows } = await pool.query(query, params);

        res.status(200).json({ teacherClassCounts: rows });

    } catch (error) {
        console.error(error);
        res.status(500).json({ message: "Internal server error" });
    }
};


// const getTeacherLoad = async (req, res) => {
//     try {
//         const { fromDate, toDate } = req.query;
//         let query = `
//             SELECT DISTINCT 
//                    t.teacher_name AS teacher, b.cohort_number AS cohort,
//                    c.classroom_name AS classroom, s.subject_code AS subject,
//                    COUNT(DISTINCT cs.session_id) AS total_classes_taken
//             FROM pp.class_session cs
//             JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
//             JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
//             JOIN pp.batch b ON cb.batch_id = b.batch_id
//             JOIN pp.teacher t ON c.teacher_id = t.teacher_id
//             JOIN pp.subject s ON c.subject_id = s.subject_id
//         `;
//         const params = [];
//         if (fromDate && toDate) {
//             query += ` WHERE cs.session_date BETWEEN $1::date AND $2::date `;
//             params.push(fromDate, toDate);
//         }
//         query += ` GROUP BY t.teacher_name, b.cohort_number, c.classroom_name, s.subject_code
//                    ORDER BY t.teacher_name, b.cohort_number, c.classroom_name `;
//         const { rows } = await pool.query(query, params);
//         res.status(200).json({ teacherClassCounts: rows });
//     } catch (error) {
//         res.status(500).json({ message: "Internal server error" });
//     }
// };

/**
 * 4️⃣ getTeacherPerformance
 * Compares scheduled vs conducted classes for a teacher.
 */
const getTeacherPerformance = async (req, res) => {
    const { teacherId, fromDate, toDate } = req.query;
    if (!teacherId || !fromDate || !toDate)
        return res.status(400).json({ error: "teacherId, fromDate, and toDate required" });

    try {
        const scheduledQuery = `
            WITH dates AS (SELECT generate_series($2::date, $3::date, interval '1 day')::date AS dt),
            scheduled AS (
                SELECT s.subject_code, d.dt FROM pp.classroom c
                JOIN pp.timetable t ON t.classroom_id = c.classroom_id
                JOIN dates d ON trim(upper(t.day_of_week)) = trim(upper(to_char(d.dt, 'DAY')))
                JOIN pp.subject s ON s.subject_id = c.subject_id
                WHERE c.teacher_id = $1
            )
            SELECT subject_code AS subject, COUNT(*) AS scheduled FROM scheduled GROUP BY subject_code;
        `;
        const scheduledRes = await ReportsModel.query(scheduledQuery, [teacherId, fromDate, toDate]);

        const conductedQuery = `
            SELECT subj.subject_code AS subject, COUNT(DISTINCT cs.session_id) AS conducted
            FROM pp.class_session cs
            JOIN pp.classroom c ON c.classroom_id = cs.classroom_id
            JOIN pp.subject subj ON subj.subject_id = c.subject_id
            WHERE c.teacher_id = $1 AND cs.session_date BETWEEN $2::date AND $3::date
            GROUP BY subj.subject_code;
        `;
        const conductedRes = await ReportsModel.query(conductedQuery, [teacherId, fromDate, toDate]);

        const subjectsMap = {};
        scheduledRes.rows.forEach(r => subjectsMap[r.subject] = { scheduled: +r.scheduled, conducted: 0 });
        conductedRes.rows.forEach(r => {
            if (!subjectsMap[r.subject]) subjectsMap[r.subject] = { scheduled: 0, conducted: +r.conducted };
            else subjectsMap[r.subject].conducted = +r.conducted;
        });

        const subjects = Object.keys(subjectsMap).map(subj => ({
            subject: subj, ...subjectsMap[subj],
            completion: subjectsMap[subj].scheduled > 0 ? +((subjectsMap[subj].conducted / subjectsMap[subj].scheduled) * 100).toFixed(1) : 0
        }));

        res.json({ reportId: `TP-${teacherId}-${fromDate}-${toDate}`, subjects });
    } catch (err) {
        res.status(500).json({ error: "Server error generating teacher performance" });
    }
};

/* ===========================================================
   SECTION 3: DASHBOARD ANALYTICS (RAINBOWS & BAR GRAPHS)
   =========================================================== */

/**
 * 5️⃣ getGlobalAttendanceStats
 * Powers the Monthly Cohort Rainbow Gauges.
 */
const getGlobalAttendanceStats = async (req, res) => {
    try {
        const query = `
            WITH current_month AS (
                SELECT date_trunc('month', CURRENT_DATE) as start_dt,
                       (date_trunc('month', CURRENT_DATE) + interval '1 month') as end_dt
            ),
            metrics AS (
                SELECT 
                    b.batch_id, b.batch_name, b.cohort_number,
                    (SELECT COUNT(*) FROM pp.student_master WHERE batch_id = b.batch_id AND active_yn = 'ACTIVE') as s_count,
                    (SELECT COUNT(DISTINCT cs.session_id) FROM pp.classroom_batch cb 
                     JOIN pp.class_session cs ON cs.classroom_id = cb.classroom_id 
                     CROSS JOIN current_month cm
                     WHERE cb.batch_id = b.batch_id AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt) as sess_count,
                    (SELECT COUNT(sa.attendance_id) FROM pp.student_attendance sa
                     JOIN pp.class_session cs ON sa.session_id = cs.session_id
                     JOIN pp.student_master sm ON sm.student_id = sa.student_id
                     CROSS JOIN current_month cm
                     WHERE sm.batch_id = b.batch_id AND sm.active_yn = 'ACTIVE' 
                     AND sa.status IN ('PRESENT', 'LEAVE')
                     AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt) as p_count
                FROM pp.batch b
            )
            SELECT 
                c.cohort_name, c.cohort_number,
                ROUND(AVG(CASE WHEN (m.sess_count * m.s_count) > 0 THEN (m.p_count::float / (m.sess_count * m.s_count)) * 100 ELSE 0 END)::numeric, 2) as cohort_avg,
                jsonb_agg(jsonb_build_object(
                    'batch_name', m.batch_name,
                    'avg', ROUND(CASE WHEN (m.sess_count * m.s_count) > 0 THEN (m.p_count::float / (m.sess_count * m.s_count)) * 100 ELSE 0 END::numeric, 2),
                    'classes_held', m.sess_count
                ) ORDER BY m.batch_name) as batches
            FROM pp.cohort c
            JOIN metrics m ON m.cohort_number = c.cohort_number
            GROUP BY c.cohort_name, c.cohort_number ORDER BY c.cohort_number;
        `;
        const { rows } = await pool.query(query);
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
};

/**
 * 6️⃣ getTeacherSubjectMonthlyStats
 * Powers the Nested Subject Rainbow for a specific batch.
 */
const getTeacherSubjectMonthlyStats = async (req, res) => {
    const { batchId } = req.query;
    try {
        const query = `
            WITH current_month AS (
                SELECT date_trunc('month', CURRENT_DATE) as start_dt,
                       (date_trunc('month', CURRENT_DATE) + interval '1 month') as end_dt
            ),
            student_pop AS (
                SELECT COUNT(*) as active_students FROM pp.student_master WHERE batch_id = $1 AND active_yn = 'ACTIVE'
            )
            SELECT 
                s.subject_code, t.teacher_name,
                ROUND(CASE WHEN (COUNT(DISTINCT cs.session_id) * (SELECT active_students FROM student_pop)) > 0 
                      THEN (COUNT(sa.attendance_id) FILTER (WHERE sa.status IN ('PRESENT', 'LEAVE'))::float / (COUNT(DISTINCT cs.session_id) * (SELECT active_students FROM student_pop))) * 100 
                      ELSE 0 END::numeric, 2) as percentage
            FROM pp.class_session cs
            JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
            JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
            JOIN pp.teacher t ON c.teacher_id = t.teacher_id
            JOIN pp.subject s ON c.subject_id = s.subject_id
            LEFT JOIN pp.student_attendance sa ON sa.session_id = cs.session_id
            CROSS JOIN current_month cm
            WHERE cb.batch_id = $1 AND cs.session_date >= cm.start_dt AND cs.session_date < cm.end_dt
            GROUP BY s.subject_code, t.teacher_name ORDER BY percentage DESC;
        `;
        const { rows } = await pool.query(query, [batchId]);
        res.json(rows);
    } catch (err) {
        res.status(500).json({ error: err.message });
    }
};



// const getBatchClassDetails = async (req, res) => {
//     const { batchId, fromDate, toDate } = req.query;
//     try {
//         const query = `
//             SELECT 
//                 cs.session_id,
//                 cs.session_date AS date, 
//                 t.teacher_name, 
//                 co.cohort_name,
//                 c.classroom_name,
//                 -- 🔥 Check if THIS specific batch has marked attendance for this session
//                 EXISTS (
//                     SELECT 1 FROM pp.student_attendance sa
//                     JOIN pp.student_master sm ON sa.student_id = sm.student_id
//                     WHERE sa.session_id = cs.session_id AND sm.batch_id = $1
//                 ) AS attendance_marked
//             FROM pp.class_session cs
//             JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
//             JOIN pp.teacher t ON c.teacher_id = t.teacher_id
//             JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
//             JOIN pp.batch b ON cb.batch_id = b.batch_id
//             JOIN pp.cohort co ON b.cohort_number = co.cohort_number
//             WHERE b.batch_id = $1 AND cs.session_date BETWEEN $2::date AND $3::date
//             ORDER BY cs.session_date DESC;
//         `;
//         const { rows } = await pool.query(query, [batchId, fromDate, toDate]);
//         res.json({ success: true, count: rows.length, classes: rows });
//     } catch (err) {
//         console.error(err);
//         res.status(500).json({ error: "Internal Server Error" });
//     }
// };

const getBatchClassDetails = async (req, res) => {
    const { batchId, fromDate, toDate } = req.query;

    try {
        const query = `
            SELECT 
                cs.session_id,
                cs.session_date AS date, 
                t.teacher_name, 
                co.cohort_name,
                c.classroom_name,

                -- 🔥 Check if THIS specific batch has marked attendance
                EXISTS (
                    SELECT 1 
                    FROM pp.student_attendance sa
                    JOIN pp.student_master sm 
                        ON sa.student_id = sm.student_id
                    WHERE sa.session_id = cs.session_id 
                      AND sm.batch_id = $1
                ) AS attendance_marked

            FROM pp.class_session cs

            JOIN pp.classroom c 
                ON cs.classroom_id = c.classroom_id

            -- 🔥 FIX: use session teacher (NOT classroom teacher)
            JOIN pp.teacher t 
                ON cs.teacher_id = t.teacher_id

            JOIN pp.classroom_batch cb 
                ON c.classroom_id = cb.classroom_id

            JOIN pp.batch b 
                ON cb.batch_id = b.batch_id

            JOIN pp.cohort co 
                ON b.cohort_number = co.cohort_number

            WHERE b.batch_id = $1 
              AND cs.session_date BETWEEN $2::date AND $3::date

            ORDER BY cs.session_date DESC;
        `;

        const { rows } = await pool.query(query, [batchId, fromDate, toDate]);

        res.json({
            success: true,
            count: rows.length,
            classes: rows
        });

    } catch (err) {
        console.error(err);
        res.status(500).json({ error: "Internal Server Error" });
    }
};



// const getTeacherClassDetails = async (req, res) => {
//     const { teacherId, fromDate, toDate } = req.query;
//     try {
//         const isNumeric = /^\d+$/.test(teacherId);
//         const filterColumn = isNumeric ? "t.teacher_id" : "t.teacher_name";
//         const query = `
//             SELECT DISTINCT ON (cs.session_id) 
//                    cs.session_date AS date, t.teacher_name, co.cohort_name,
//                    b.batch_name, c.classroom_name
//             FROM pp.class_session cs
//             JOIN pp.classroom c ON cs.classroom_id = c.classroom_id
//             JOIN pp.teacher t ON c.teacher_id = t.teacher_id
//             JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
//             JOIN pp.batch b ON cb.batch_id = b.batch_id
//             JOIN pp.cohort co ON b.cohort_number = co.cohort_number
//             WHERE ${filterColumn} = $1 AND cs.session_date BETWEEN $2::date AND $3::date
//             ORDER BY cs.session_id, cs.session_date DESC;
//         `;
//         const { rows } = await pool.query(query, [teacherId, fromDate, toDate]);
//         res.json({ success: true, count: rows.length, classes: rows });
//     } catch (err) {
//         res.status(500).json({ error: "Internal Server Error" });
//     }
// };

//THE FOLLOWING FUNCTION BEING USED TO FETCH THE TOTAL CLASSES CONDCTED BY EACH TEACHER FOLLOWED BY BATCH AND CLASSROOM
const getTeacherClassDetails = async (req, res) => {
    const { teacherId, fromDate, toDate } = req.query;

    try {
        const isNumeric = /^\d+$/.test(teacherId);

        // 🔥 filter will now work correctly with session teacher
        const filterColumn = isNumeric ? "t.teacher_id" : "t.teacher_name";

        const query = `
            SELECT DISTINCT ON (cs.session_id) 
                   cs.session_date AS date, 
                   t.teacher_name, 
                   co.cohort_name,
                   b.batch_name, 
                   c.classroom_name

            FROM pp.class_session cs

            JOIN pp.classroom c 
                ON cs.classroom_id = c.classroom_id

            -- 🔥 FIX: use session teacher (NOT classroom teacher)
            JOIN pp.teacher t 
                ON cs.teacher_id = t.teacher_id

            JOIN pp.classroom_batch cb 
                ON c.classroom_id = cb.classroom_id

            JOIN pp.batch b 
                ON cb.batch_id = b.batch_id

            JOIN pp.cohort co 
                ON b.cohort_number = co.cohort_number

            WHERE ${filterColumn} = $1 
              AND cs.session_date BETWEEN $2::date AND $3::date

            ORDER BY cs.session_id, cs.session_date DESC;
        `;

        const { rows } = await pool.query(query, [teacherId, fromDate, toDate]);

        res.json({
            success: true,
            count: rows.length,
            classes: rows
        });

    } catch (err) {
        console.error(err);
        res.status(500).json({ error: "Internal Server Error" });
    }
};

module.exports = {
    requireAuth,
    getAttendanceReport,
    getAbsenteesReport,
    getTeacherLoad,
    getTeacherPerformance,
    getGlobalAttendanceStats,
    getTeacherSubjectMonthlyStats,
    getBatchClassDetails,
    getTeacherClassDetails,
};