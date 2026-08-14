
// const fs = require("fs");
// const path = require("path");
// const Papa = require("papaparse");
// const pool = require("../../config/db");

// // --- Helper 1: Parse "1 hr 25 min" into Total Minutes ---
// const parseDurationToMinutes = (raw) => {
//     if (!raw || String(raw).toLowerCase() === "null") return 0;
//     const s = String(raw).toLowerCase();
//     let totalMinutes = 0;
//     const hrMatch = s.match(/(\d+)\s*hr/);
//     const minMatch = s.match(/(\d+)\s*min/);
//     const secMatch = s.match(/(\d+)\s*sec/);
//     if (hrMatch) totalMinutes += parseInt(hrMatch[1], 10) * 60;
//     if (minMatch) totalMinutes += parseInt(minMatch[1], 10);
//     if (secMatch) totalMinutes += (parseInt(secMatch[1], 10) / 60);
//     return Math.round(totalMinutes);
// };

// // --- Helper 2: Convert time string to minutes ---
// const timeToMinutes = (raw) => {
//     const timeStr = normalizeTimeToDB(raw);
//     if (!timeStr || timeStr === "00:00:00") return 0;
//     const [h, m] = timeStr.split(':').map(Number);
//     return h * 60 + m;
// };

// // --- Helper 3: Robust Normalization for Postgres TIME ---
// const normalizeTimeToDB = (raw) => {
//     if (!raw || String(raw).trim() === "" || String(raw).toLowerCase() === "null") return "00:00:00";
//     let s = String(raw).replace(/\u202f|\u00a0/g, " ").trim();
//     if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(s)) return s.length === 5 ? s + ":00" : s;
//     const ampmMatch = s.match(/(\d+):(\d+)\s*(AM|PM)/i);
//     if (ampmMatch) {
//         let hrs = parseInt(ampmMatch[1], 10);
//         let mins = parseInt(ampmMatch[2], 10);
//         const ampm = ampmMatch[3].toUpperCase();
//         if (ampm === "PM" && hrs < 12) hrs += 12;
//         if (ampm === "AM" && hrs === 12) hrs = 0;
//         return `${String(hrs).padStart(2, '0')}:${String(mins).padStart(2, '0')}:00`;
//     }
//     return "00:00:00";
// };

// // 1. getOrFindSession (Find existing session based on filters)
// exports.getOrFindSession = async (req, res) => {
//     try {
//         const { classroom_id, session_date, start_time } = req.query;
//         const normalizedStart = normalizeTimeToDB(start_time);
//         const q = `SELECT session_id, start_time::text, end_time::text, duration_minutes 
//                    FROM pp.class_session 
//                    WHERE classroom_id = $1 AND session_date = $2 
//                    AND to_char(start_time, 'HH24:MI:SS') = $3 LIMIT 1`;
//         const r = await pool.query(q, [classroom_id, session_date, normalizedStart]);
//         res.status(200).json(r.rows[0] || { session_id: null });
//     } catch (err) { res.status(500).json({ message: err.message }); }
// };

// // 2. fetchAttendance (Manual Tab)
// // Shows Active students OR anyone who was previously marked for this session
// exports.fetchAttendance = async (req, res) => {
//     try {
//         const { session_id, batchId } = req.query;
//         const q = `
//             SELECT sm.student_id, sm.student_name, sm.enr_id, sm.contact_no1, sm.student_email, sm.active_yn, sa.status as db_status 
//             FROM pp.student_master sm 
//             LEFT JOIN pp.student_attendance sa ON sa.student_id = sm.student_id AND sa.session_id = $1 
//             WHERE sm.batch_id = $2 
//             AND (sm.active_yn = 'ACTIVE' OR sa.session_id IS NOT NULL)
//             ORDER BY sm.student_name`;
//         const r = await pool.query(q, [session_id || null, batchId]);
//         res.status(200).json(r.rows);
//     } catch (err) { res.status(500).json({ message: err.message }); }
// };

// // 3. previewCSVAttendance (Bulk Upload Logic)
// exports.previewCSVAttendance = async (req, res) => {
//     try {
//         if (!req.file) return res.status(400).json({ message: "No file uploaded" });
//         const results = await new Promise((resolve, reject) => {
//             const stream = fs.createReadStream(req.file.path);
//             Papa.parse(stream, { skipEmptyLines: true, complete: (r) => resolve(r.data), error: (e) => reject(e) });
//         });

//         if (results.length < 2) return res.status(400).json({ message: "CSV missing data rows." });

//         // Total Duration from Row 2 (Index 1), Column D (Index 3)
//         const summaryDurationRaw = results[1][3]; 
//         const totalCSVDurationMins = parseDurationToMinutes(summaryDurationRaw);

//         const batchId = req.body.batch_id;
//         const dbStudentsRes = await pool.query(
//             `SELECT student_id, student_name, enr_id, active_yn FROM pp.student_master WHERE batch_id = $1`, 
//             [batchId]
//         );
//         const dbStudents = dbStudentsRes.rows;

//         // Map CSV data - STRICTLY COLUMN A (Index 0)
//         const csvMap = new Map();
//         for (let i = 2; i < results.length; i++) {
//             const row = results[i];
//             const rawNameColumnA = String(row[0] || "").trim();
//             if (!rawNameColumnA) continue;

//             const nameKey = rawNameColumnA.toLowerCase();
//             const duration = parseDurationToMinutes(row[3]); 

//             if (!csvMap.has(nameKey) || csvMap.get(nameKey).duration_minutes < duration) {
//                 csvMap.set(nameKey, { 
//                     originalName: rawNameColumnA, 
//                     duration_minutes: duration, 
//                     time_joined: row[4], 
//                     time_exited: row[5] 
//                 });
//             }
//         }

//         const previewData = []; 
//         const unmatchedStudents = []; 
//         const inactiveStudents = [];
//         const matchedCSVKeys = new Set();

//         for (const student of dbStudents) {
//             const dbNameClean = student.student_name.trim().toLowerCase();
//             let matchedCsvData = null;
//             let matchedKey = null;

//             // FUZZY SEARCH: DB name inside CSV Column A
//             for (let [csvKey, data] of csvMap) {
//                 if (csvKey.includes(dbNameClean)) {
//                     matchedCsvData = data;
//                     matchedKey = csvKey;
//                     break; 
//                 }
//             }

//             // Categorize Inactive Students separately
//             if (student.active_yn !== 'ACTIVE') {
//                 if (matchedCsvData) {
//                     inactiveStudents.push({ 
//                         student_name: student.student_name, 
//                         duration_minutes: matchedCsvData.duration_minutes 
//                     });
//                     matchedCSVKeys.add(matchedKey);
//                 }
//                 continue;
//             }

//             if (matchedCsvData) {
//                 const pct = totalCSVDurationMins > 0 ? (matchedCsvData.duration_minutes / totalCSVDurationMins) * 100 : 0;
//                 previewData.push({ 
//                     student_id: student.student_id, 
//                     student_name: student.student_name, 
//                     enr_id: student.enr_id, 
//                     duration_minutes: matchedCsvData.duration_minutes, 
//                     time_joined: matchedCsvData.time_joined, 
//                     time_exited: matchedCsvData.time_exited, 
//                     status: pct >= 75 ? "PRESENT" : (pct >= 40 ? "LATE JOINED" : "ABSENT")
//                 });
//                 matchedCSVKeys.add(matchedKey);
//             } else {
//                 // Active but missing in CSV = ABSENT
//                 previewData.push({ 
//                     student_id: student.student_id, student_name: student.student_name, enr_id: student.enr_id, 
//                     duration_minutes: 0, time_joined: "N/A", time_exited: "N/A", status: "ABSENT"
//                 });
//             }
//         }

//         // CSV rows that matched nothing
//         for (let [key, data] of csvMap) {
//             if (!matchedCSVKeys.has(key)) {
//                 unmatchedStudents.push({ student_name: data.originalName, duration_minutes: data.duration_minutes });
//             }
//         }

//         previewData.sort((a, b) => a.student_name.localeCompare(b.student_name));
//         fs.unlinkSync(req.file.path);
//         res.status(200).json({ previewData, unmatchedStudents, inactiveStudents });
//     } catch (err) { res.status(500).json({ message: err.message }); }
// };

// // 4. commitCSVAttendance
// exports.commitCSVAttendance = async (req, res) => {
//     const client = await pool.connect();
//     try {
//         const { previewData, session_date, classroom_id, start_time, end_time } = req.body;
//         await client.query("BEGIN");
//         const startMins = timeToMinutes(start_time);
//         const endMins = timeToMinutes(end_time);
//         const totalSessionMins = (endMins > startMins) ? (endMins - startMins) : 0;

//         const sRes = await client.query(
//             `INSERT INTO pp.class_session (classroom_id, session_date, start_time, end_time, duration_minutes) 
//              VALUES ($1,$2,$3,$4,$5) 
//              ON CONFLICT (classroom_id, session_date, start_time, end_time) 
//              DO UPDATE SET duration_minutes = EXCLUDED.duration_minutes, updated_at = NOW() RETURNING session_id`, 
//             [classroom_id, session_date, normalizeTimeToDB(start_time), normalizeTimeToDB(end_time), totalSessionMins]
//         );
//         const sid = sRes.rows[0].session_id;

//         for (const r of previewData) {
//             if (!r.student_id) continue;
//             const attPct = totalSessionMins > 0 ? (r.duration_minutes / totalSessionMins) * 100 : 0;
//             await client.query(
//                 `INSERT INTO pp.student_attendance 
//                     (session_id, student_id, status, time_joined, time_exited, duration_minutes, attendance_percent) 
//                  VALUES ($1,$2,$3,$4,$5,$6,$7) 
//                  ON CONFLICT (session_id, student_id) 
//                  DO UPDATE SET 
//                     status = EXCLUDED.status, duration_minutes = EXCLUDED.duration_minutes, 
//                     time_joined = EXCLUDED.time_joined, time_exited = EXCLUDED.time_exited,
//                     attendance_percent = EXCLUDED.attendance_percent, updated_at = NOW()`, 
//                 [sid, r.student_id, r.status, normalizeTimeToDB(r.time_joined), normalizeTimeToDB(r.time_exited), r.duration_minutes || 0, Math.min(100, parseFloat(attPct.toFixed(2)))]
//             );
//         }
//         await client.query("COMMIT");
//         res.status(200).json({ session_id: sid });
//     } catch (err) { await client.query("ROLLBACK"); res.status(500).json({ message: "Commit failed: " + err.message }); } 
//     finally { client.release(); }
// };

// exports.undoLastAttendanceCommit = async (req, res) => {
//     try { 
//         await pool.query(`DELETE FROM pp.student_attendance WHERE session_id = $1`, [req.body.session_id]); 
//         await pool.query(`DELETE FROM pp.class_session WHERE session_id = $1`, [req.body.session_id]); 
//         res.status(200).json({ message: "Undo Successful" }); 
//     } catch (err) { res.status(500).json({ message: err.message }); }
// };

// exports.checkOverlap = async (req, res) => {
//     const { classroomId, date, startTime, endTime } = req.query;
//     const r = await pool.query(`SELECT session_id FROM pp.class_session WHERE classroom_id=$1 AND session_date=$2 AND (start_time, end_time) OVERLAPS ($3::time, $4::time)`, [classroomId, date, startTime, endTime]);
//     res.json({ overlap: r.rows.length > 0 });
// };

// exports.downloadSampleCSV = (req, res) => { 
//     const filePath = path.join(__dirname, "../../uploads/sample_attendance.csv");
//     res.download(filePath); 
// };

// exports.submitBulkAttendance = async (req, res) => { res.status(200).json({ message: "Bulk submission logic active" }); };




const fs = require("fs");
const path = require("path");
const Papa = require("papaparse");
const pool = require("../../config/db");

// Import model session helper (IMPORTANT)
const { getOrCreateSession } = require("../../models/coordinator/attendanceModel");


// --- Helper 1: Parse "1 hr 25 min" into Total Minutes ---
const parseDurationToMinutes = (raw) => {
    if (!raw || String(raw).toLowerCase() === "null") return 0;
    const s = String(raw).toLowerCase();
    let totalMinutes = 0;

    const hrMatch = s.match(/(\d+)\s*hr/);
    const minMatch = s.match(/(\d+)\s*min/);
    const secMatch = s.match(/(\d+)\s*sec/);

    if (hrMatch) totalMinutes += parseInt(hrMatch[1], 10) * 60;
    if (minMatch) totalMinutes += parseInt(minMatch[1], 10);
    if (secMatch) totalMinutes += (parseInt(secMatch[1], 10) / 60);

    return Math.round(totalMinutes);
};


const timeToMinutes = (raw) => {
    const timeStr = normalizeTimeToDB(raw);
    if (!timeStr || timeStr === "00:00:00") return 0;

    const [h, m] = timeStr.split(":").map(Number);
    return h * 60 + m;
};

// --- Helper 2: Normalize Time ---
const normalizeTimeToDB = (raw) => {
    if (!raw || String(raw).trim() === "" || String(raw).toLowerCase() === "null")
        return "00:00:00";

    let s = String(raw).replace(/\u202f|\u00a0/g, " ").trim();

    if (/^\d{1,2}:\d{2}(:\d{2})?$/.test(s)) {
        return s.length === 5 ? s + ":00" : s;
    }

    const ampmMatch = s.match(/(\d+):(\d+)\s*(AM|PM)/i);
    if (ampmMatch) {
        let hrs = parseInt(ampmMatch[1], 10);
        let mins = parseInt(ampmMatch[2], 10);
        const ampm = ampmMatch[3].toUpperCase();

        if (ampm === "PM" && hrs < 12) hrs += 12;
        if (ampm === "AM" && hrs === 12) hrs = 0;

        return `${String(hrs).padStart(2, '0')}:${String(mins).padStart(2, '0')}:00`;
    }

    return "00:00:00";
};


// --- FETCH SESSION ---
exports.getOrFindSession = async (req, res) => {
    try {
        const { classroom_id, session_date, start_time } = req.query;

        const q = `
            SELECT session_id, start_time::text, end_time::text, duration_minutes
            FROM pp.class_session
            WHERE classroom_id = $1
              AND session_date = $2
              AND to_char(start_time, 'HH24:MI:SS') = $3
            LIMIT 1
        `;

        const r = await pool.query(q, [
            classroom_id,
            session_date,
            normalizeTimeToDB(start_time)
        ]);

        res.status(200).json(r.rows[0] || { session_id: null });
    } catch (err) {
        res.status(500).json({ message: err.message });
    }
};


// --- FETCH ATTENDANCE ---
exports.fetchAttendance = async (req, res) => {
    try {
        const { session_id, batchId } = req.query;

        const q = `
            SELECT 
                sm.student_id,
                sm.student_name,
                sm.enr_id,
                sm.contact_no1,
                sm.student_email,
                sm.active_yn,
                sa.status as db_status
            FROM pp.student_master sm
            LEFT JOIN pp.student_attendance sa 
                ON sa.student_id = sm.student_id 
               AND sa.session_id = $1
            WHERE sm.batch_id = $2
              AND (sm.active_yn = 'ACTIVE' OR sa.session_id IS NOT NULL)
            ORDER BY sm.student_name
        `;

        const r = await pool.query(q, [session_id || null, batchId]);
        res.status(200).json(r.rows);

    } catch (err) {
        res.status(500).json({ message: err.message });
    }
};


// // --- CSV PREVIEW ---
// exports.previewCSVAttendance = async (req, res) => {
//     try {
//         if (!req.file) {
//             return res.status(400).json({ message: "No file uploaded" });
//         }

//         const results = await new Promise((resolve, reject) => {
//             const stream = fs.createReadStream(req.file.path);
//             Papa.parse(stream, {
//                 skipEmptyLines: true,
//                 complete: (r) => resolve(r.data),
//                 error: (e) => reject(e)
//             });
//         });

//         if (results.length < 2) {
//             return res.status(400).json({ message: "CSV missing data rows." });
//         }

//         const summaryDurationRaw = results[1][3];
//         const totalCSVDurationMins = parseDurationToMinutes(summaryDurationRaw);

//         const batchId = req.body.batch_id;

//         const dbStudentsRes = await pool.query(
//             `SELECT student_id, student_name, enr_id, active_yn 
//              FROM pp.student_master 
//              WHERE batch_id = $1`,
//             [batchId]
//         );

//         const dbStudents = dbStudentsRes.rows;

//         const csvMap = new Map();

//         for (let i = 2; i < results.length; i++) {
//             const row = results[i];
//             const rawName = String(row[0] || "").trim();
//             if (!rawName) continue;

//             const key = rawName.toLowerCase();
//             const duration = parseDurationToMinutes(row[3]);

//             if (!csvMap.has(key) || csvMap.get(key).duration_minutes < duration) {
//                 csvMap.set(key, {
//                     originalName: rawName,
//                     duration_minutes: duration,
//                     time_joined: row[4],
//                     time_exited: row[5]
//                 });
//             }
//         }

//         const previewData = [];

//         for (const student of dbStudents) {
//             const dbName = student.student_name.trim().toLowerCase();

//             let match = null;

//             for (let [k, v] of csvMap) {
//                 if (k.includes(dbName)) {
//                     match = v;
//                     break;
//                 }
//             }

//             if (match) {
//                 const pct = totalCSVDurationMins > 0
//                     ? (match.duration_minutes / totalCSVDurationMins) * 100
//                     : 0;

//                 previewData.push({
//                     student_id: student.student_id,
//                     student_name: student.student_name,
//                     enr_id: student.enr_id,
//                     duration_minutes: match.duration_minutes,
//                     time_joined: match.time_joined,
//                     time_exited: match.time_exited,
//                     status: pct >= 75 ? "PRESENT" : (pct >= 40 ? "LATE JOINED" : "ABSENT")
//                 });
//             } else {
//                 previewData.push({
//                     student_id: student.student_id,
//                     student_name: student.student_name,
//                     enr_id: student.enr_id,
//                     duration_minutes: 0,
//                     time_joined: "N/A",
//                     time_exited: "N/A",
//                     status: "ABSENT"
//                 });
//             }
//         }

//         fs.unlinkSync(req.file.path);

//         res.status(200).json({ previewData });

//     } catch (err) {
//         res.status(500).json({ message: err.message });
//     }
// };


exports.previewCSVAttendance = async (req, res) => {
    try {
        if (!req.file) {
            return res.status(400).json({ message: "No file uploaded" });
        }

        const results = await new Promise((resolve, reject) => {
            const stream = fs.createReadStream(req.file.path);
            Papa.parse(stream, {
                skipEmptyLines: true,
                complete: (r) => resolve(r.data),
                error: (e) => reject(e)
            });
        });

        if (results.length < 2) {
            return res.status(400).json({ message: "CSV missing data rows." });
        }

        const summaryDurationRaw = results[1][3];
        const totalCSVDurationMins = parseDurationToMinutes(summaryDurationRaw);

        const batchId = req.body.batch_id;

        const dbStudentsRes = await pool.query(
            `SELECT student_id, student_name, enr_id, active_yn 
             FROM pp.student_master 
             WHERE batch_id = $1`,
            [batchId]
        );

        const dbStudents = dbStudentsRes.rows;

        // ---------- CSV MAP ----------
        const csvMap = new Map();

        for (let i = 2; i < results.length; i++) {
            const row = results[i];
            const rawName = String(row[0] || "").trim();
            if (!rawName) continue;

            const key = rawName.toLowerCase();
            const duration = parseDurationToMinutes(row[3]);

            if (!csvMap.has(key) || csvMap.get(key).duration_minutes < duration) {
                csvMap.set(key, {
                    originalName: rawName,
                    duration_minutes: duration,
                    time_joined: row[4],
                    time_exited: row[5]
                });
            }
        }

        // ---------- LOGIC ----------
        const previewData = [];
        const unmatchedStudents = [];
        const inactiveStudents = [];
        const matchedCSVKeys = new Set();

        for (const student of dbStudents) {
            const dbName = student.student_name.trim().toLowerCase();

            let match = null;
            let matchedKey = null;

            for (let [k, v] of csvMap) {
                if (k.includes(dbName)) {
                    match = v;
                    matchedKey = k;
                    break;
                }
            }

            // 🔴 HANDLE INACTIVE
            if (student.active_yn !== 'ACTIVE') {
                if (match) {
                    inactiveStudents.push({
                        student_name: student.student_name,
                        duration_minutes: match.duration_minutes
                    });
                    matchedCSVKeys.add(matchedKey);
                }
                continue;
            }

            if (match) {
                const pct = totalCSVDurationMins > 0
                    ? (match.duration_minutes / totalCSVDurationMins) * 100
                    : 0;

                previewData.push({
                    student_id: student.student_id,
                    student_name: student.student_name,
                    enr_id: student.enr_id,
                    duration_minutes: match.duration_minutes,
                    time_joined: match.time_joined,
                    time_exited: match.time_exited,
                    status: pct >= 75 ? "PRESENT" : (pct >= 40 ? "LATE JOINED" : "ABSENT")
                });

                matchedCSVKeys.add(matchedKey);
            } else {
                previewData.push({
                    student_id: student.student_id,
                    student_name: student.student_name,
                    enr_id: student.enr_id,
                    duration_minutes: 0,
                    time_joined: "N/A",
                    time_exited: "N/A",
                    status: "ABSENT"
                });
            }
        }

        // 🔴 UNMATCHED CSV STUDENTS
        for (let [k, v] of csvMap) {
            if (!matchedCSVKeys.has(k)) {
                unmatchedStudents.push({
                    student_name: v.originalName,
                    duration_minutes: v.duration_minutes
                });
            }
        }

        previewData.sort((a, b) =>
            a.student_name.localeCompare(b.student_name)
        );

        fs.unlinkSync(req.file.path);

        res.status(200).json({
            previewData,
            unmatchedStudents,
            inactiveStudents
        });

    } catch (err) {
        res.status(500).json({ message: err.message });
    }
};

// --- COMMIT CSV ATTENDANCE (FIXED) ---
exports.commitCSVAttendance = async (req, res) => {
    const client = await pool.connect();

    try {
        const {
            previewData,
            session_date,
            classroom_id,
            start_time,
            end_time
        } = req.body;

        await client.query("BEGIN");

        const session_id = await getOrCreateSession(
            classroom_id,
            session_date,
            normalizeTimeToDB(start_time),
            normalizeTimeToDB(end_time)
        );

        for (const r of previewData) {
            if (!r.student_id) continue;

            const startMins = timeToMinutes(start_time);
            const endMins = timeToMinutes(end_time);

            const totalSessionMins =
                endMins > startMins ? (endMins - startMins) : 0;

            const attPct = totalSessionMins > 0
                ? (r.duration_minutes / totalSessionMins) * 100
                : 0;

            await client.query(
                `INSERT INTO pp.student_attendance 
                    (session_id, student_id, status, time_joined, time_exited, duration_minutes, attendance_percent)
                 VALUES ($1,$2,$3,$4,$5,$6,$7)
                 ON CONFLICT (session_id, student_id)
                 DO UPDATE SET 
                    status = EXCLUDED.status,
                    duration_minutes = EXCLUDED.duration_minutes,
                    time_joined = EXCLUDED.time_joined,
                    time_exited = EXCLUDED.time_exited,
                    attendance_percent = EXCLUDED.attendance_percent,
                    updated_at = NOW()`,
                [
                    session_id,
                    r.student_id,
                    r.status,
                    normalizeTimeToDB(r.time_joined),
                    normalizeTimeToDB(r.time_exited),
                    r.duration_minutes || 0,
                    Math.min(100, parseFloat(attPct.toFixed(2)))
                ]
            );
        }

        await client.query("COMMIT");

        res.status(200).json({ session_id });

    } catch (err) {
        await client.query("ROLLBACK");
        res.status(500).json({ message: err.message });
    } finally {
        client.release();
    }
};


// --- UNDO ---
exports.undoLastAttendanceCommit = async (req, res) => {
    try {
        await pool.query(`DELETE FROM pp.student_attendance WHERE session_id = $1`, [req.body.session_id]);
        await pool.query(`DELETE FROM pp.class_session WHERE session_id = $1`, [req.body.session_id]);

        res.status(200).json({ message: "Undo Successful" });

    } catch (err) {
        res.status(500).json({ message: err.message });
    }
};


// --- OVERLAP CHECK ---
exports.checkOverlap = async (req, res) => {
    const { classroomId, date, startTime, endTime } = req.query;

    const r = await pool.query(
        `SELECT session_id 
         FROM pp.class_session 
         WHERE classroom_id=$1 
           AND session_date=$2 
           AND (start_time, end_time) OVERLAPS ($3::time, $4::time)`,
        [classroomId, date, startTime, endTime]
    );

    res.json({ overlap: r.rows.length > 0 });
};


// --- DOWNLOAD CSV ---
exports.downloadSampleCSV = (req, res) => {
    const filePath = path.join(__dirname, "../../uploads/sample_attendance.csv");
    res.download(filePath);
};


// --- PLACEHOLDER ---
exports.submitBulkAttendance = async (req, res) => {
    res.status(200).json({ message: "Bulk submission logic active" });
};