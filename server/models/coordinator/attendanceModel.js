const pool = require("../../config/db");
const fs = require("fs");
const { parse } = require("csv-parse");

// ----------------------------
// Get attendance by filters
// ----------------------------
const getAttendanceByFilters = async (filters = {}) => {
  const { cohortNumber, batchId, classroomId, date } = filters;

  const query = `
    SELECT 
      sm.student_id,
      sm.student_name,
      b.batch_id,
      b.batch_name,
      c.cohort_number,
      c.cohort_name,
      cs.session_date,
      cs.start_time,
      cs.end_time,
      sa.status,
      sa.attendance_percent
    FROM pp.student_master sm
    JOIN pp.batch b ON sm.batch_id = b.batch_id
    JOIN pp.cohort c ON b.cohort_number = c.cohort_number
    LEFT JOIN pp.student_attendance sa ON sm.student_id = sa.student_id
    LEFT JOIN pp.class_session cs ON sa.session_id = cs.session_id
    WHERE ($1::int IS NULL OR c.cohort_number = $1)
      AND ($2::int IS NULL OR b.batch_id = $2)
      AND ($3::date IS NULL OR cs.session_date = $3)
      AND ($4::int IS NULL OR cs.classroom_id = $4)
    ORDER BY sm.student_name
  `;

  const values = [
    cohortNumber || null,
    batchId || null,
    date || null,
    classroomId || null
  ];

  const result = await pool.query(query, values);
  return result.rows;
};

// ----------------------------
// Get or Create Session (FINAL FIXED)
// ----------------------------
async function getOrCreateSession(classroom_id, date, start_time, end_time) {
  // Get teacher_id from classroom
  const teacherRes = await pool.query(
    `SELECT teacher_id FROM pp.classroom WHERE classroom_id = $1`,
    [classroom_id]
  );

  const teacher_id = teacherRes.rows[0]?.teacher_id;

  // NOTE: teacher_id can be null if not assigned
  const query = `
    INSERT INTO pp.class_session 
      (classroom_id, session_date, start_time, end_time, teacher_id)
    VALUES ($1, $2, $3, $4, $5)
    ON CONFLICT (classroom_id, session_date, start_time, end_time)
    DO UPDATE SET 
      teacher_id = EXCLUDED.teacher_id,
      updated_at = CURRENT_TIMESTAMP
    RETURNING session_id;
  `;

  const { rows } = await pool.query(query, [
    classroom_id,
    date,
    start_time,
    end_time,
    teacher_id
  ]);

  return rows[0].session_id;
}

// ----------------------------
// Create / Update Attendance
// ----------------------------
async function createAttendance(data) {
  const {
    session_id,
    student_id,
    status,
    time_joined,
    time_exited,
    duration_minutes,
    attendance_percent,
    remarks = ""
  } = data;

  const query = `
    INSERT INTO pp.student_attendance
      (session_id, student_id, status, time_joined, time_exited, duration_minutes, attendance_percent, remarks)
    VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
    ON CONFLICT (session_id, student_id)
    DO UPDATE SET 
      status = EXCLUDED.status,
      time_joined = EXCLUDED.time_joined,
      time_exited = EXCLUDED.time_exited,
      duration_minutes = EXCLUDED.duration_minutes,
      attendance_percent = EXCLUDED.attendance_percent,
      remarks = EXCLUDED.remarks,
      updated_at = CURRENT_TIMESTAMP
    RETURNING *;
  `;

  const values = [
    session_id,
    student_id,
    status,
    time_joined,
    time_exited,
    duration_minutes,
    attendance_percent,
    remarks
  ];

  const result = await pool.query(query, values);
  return result.rows[0];
}

// ----------------------------
// Bulk Insert Attendance
// ----------------------------
async function createBulkAttendance(records) {
  if (!records || records.length === 0) return [];

  const values = [];
  const valueStrings = records.map((r, i) => {
    const offset = i * 8;
    values.push(
      r.session_id,
      r.student_id,
      r.status,
      r.time_joined || null,
      r.time_exited || null,
      r.duration_minutes || 0,
      r.attendance_percent || null,
      r.remarks || ""
    );

    return `($${offset + 1},$${offset + 2},$${offset + 3},$${offset + 4},$${offset + 5},$${offset + 6},$${offset + 7},$${offset + 8})`;
  });

  const query = `
    INSERT INTO pp.student_attendance
      (session_id, student_id, status, time_joined, time_exited, duration_minutes, attendance_percent, remarks)
    VALUES ${valueStrings.join(", ")}
    ON CONFLICT (session_id, student_id)
    DO UPDATE SET 
      status = EXCLUDED.status,
      time_joined = EXCLUDED.time_joined,
      time_exited = EXCLUDED.time_exited,
      duration_minutes = EXCLUDED.duration_minutes,
      attendance_percent = EXCLUDED.attendance_percent,
      remarks = EXCLUDED.remarks,
      updated_at = CURRENT_TIMESTAMP
    RETURNING *;
  `;

  const result = await pool.query(query, values);
  return result.rows;
}

// ----------------------------
// Get students in classroom
// ----------------------------
async function getStudentsByClassroom(classroomId) {
  const query = `
    SELECT sm.student_id, sm.student_name
    FROM pp.student_master sm
    JOIN pp.classroom_batch cb ON sm.batch_id = cb.batch_id
    WHERE cb.classroom_id = $1
    ORDER BY sm.student_name;
  `;
  const result = await pool.query(query, [classroomId]);
  return result.rows;
}

// ----------------------------
// CSV Processing (Session + Attendance)
// ----------------------------
async function processCSVAttendance(filePath, classroom_id, date) {
  const errors = [];
  const records = [];

  const parseTime = (t) => {
    if (!t) return null;
    const d = new Date(`1970-01-01 ${t}`);
    if (isNaN(d)) return null;
    return d.toTimeString().split(" ")[0];
  };

  return new Promise((resolve, reject) => {
    const rows = [];

    fs.createReadStream(filePath)
      .pipe(parse({ columns: true, trim: true, skip_empty_lines: true }))
      .on("data", (row) => rows.push(row))
      .on("end", async () => {
        try {
          if (!rows.length) return reject(new Error("CSV empty"));

          const teacherRow = rows[0];

          const start_time = parseTime(teacherRow["TIME JOINED"]);
          const end_time = parseTime(teacherRow["TIME EXITED"]);

          const session_id = await getOrCreateSession(
            classroom_id,
            date,
            start_time,
            end_time
          );

          const allStudents = await getStudentsByClassroom(classroom_id);
          const processed = new Set();

          for (const row of rows) {
            const name = (row["STUDENT NAME"] || "").trim();
            if (!name) continue;

            const res = await pool.query(
              `SELECT sm.student_id 
               FROM pp.student_master sm
               JOIN pp.classroom_batch cb ON sm.batch_id = cb.batch_id
               WHERE lower(sm.student_name) = lower($1)
                 AND cb.classroom_id = $2
               LIMIT 1`,
              [name, classroom_id]
            );

            if (!res.rows.length) {
              errors.push({ name, error: "Not found" });
              continue;
            }

            const student_id = res.rows[0].student_id;
            processed.add(student_id);

            records.push({
              session_id,
              student_id,
              status: "PRESENT",
              attendance_percent: 100
            });
          }

          // Mark absentees
          for (const s of allStudents) {
            if (!processed.has(s.student_id)) {
              records.push({
                session_id,
                student_id: s.student_id,
                status: "ABSENT",
                attendance_percent: 0
              });
            }
          }

          const inserted = await createBulkAttendance(records);
          resolve({ inserted, errors });
        } catch (err) {
          reject(err);
        }
      })
      .on("error", reject);
  });
}

// ----------------------------
// Update Attendance
// ----------------------------
async function updateAttendance(attendance_id, updates) {
  const fields = [];
  const values = [];
  let i = 1;

  for (const key in updates) {
    fields.push(`${key} = $${i}`);
    values.push(updates[key]);
    i++;
  }

  values.push(attendance_id);

  const query = `
    UPDATE pp.student_attendance 
    SET ${fields.join(", ")}, updated_at = CURRENT_TIMESTAMP
    WHERE attendance_id = $${i}
    RETURNING *;
  `;

  const result = await pool.query(query, values);
  return result.rows[0];
}

// ----------------------------
// Delete Attendance
// ----------------------------
async function deleteAttendance(attendance_id) {
  await pool.query(
    `DELETE FROM pp.student_attendance WHERE attendance_id = $1`,
    [attendance_id]
  );
  return { message: "Attendance deleted" };
}

// ----------------------------
// Weekly Average Attendance
// ----------------------------
async function getWeeklyBatchAverage(batch_id, fromDate, toDate) {
  const query = `
    SELECT 
      AVG(
        CASE 
          WHEN sa.status = 'PRESENT' THEN 100
          WHEN sa.status = 'LATE JOINED' THEN 50
          ELSE 0
        END
      ) AS avg_attendance
    FROM pp.student_attendance sa
    JOIN pp.student_master sm ON sa.student_id = sm.student_id
    JOIN pp.class_session cs ON sa.session_id = cs.session_id
    WHERE sm.batch_id = $1
      AND cs.session_date BETWEEN $2 AND $3;
  `;

  const { rows } = await pool.query(query, [batch_id, fromDate, toDate]);
  return Number(rows[0].avg_attendance || 0);
}

// ----------------------------
// EXPORTS
// ----------------------------
module.exports = {
  getAttendanceByFilters,
  getOrCreateSession,
  createAttendance,
  createBulkAttendance,
  updateAttendance,
  deleteAttendance,
  processCSVAttendance,
  getStudentsByClassroom,
  getWeeklyBatchAverage
};