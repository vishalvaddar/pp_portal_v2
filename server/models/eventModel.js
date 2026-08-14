const pool = require("../config/db");

/* =========================================================
   EVENT TYPE
========================================================= */

exports.createEventType = async (name) => {
  const query = `
    INSERT INTO pp.event_type (event_type_name)
    VALUES ($1)
    RETURNING *
  `;
  const { rows } = await pool.query(query, [name]);
  return rows[0];
};

exports.updateEventType = async (id, name) => {
  const query = `
    UPDATE pp.event_type
    SET event_type_name = $1
    WHERE event_type_id = $2
    RETURNING *
  `;
  const { rows } = await pool.query(query, [name, id]);
  return rows[0];
};

exports.getEventTypes = async () => {
  const query = `
    SELECT event_type_id, event_type_name
    FROM pp.event_type
    ORDER BY event_type_name ASC
  `;
  const { rows } = await pool.query(query);
  return rows;
};

exports.getEventTypeByName = async (name) => {
  const query = `
    SELECT *
    FROM pp.event_type
    WHERE event_type_name = $1
  `;
  const { rows } = await pool.query(query, [name]);
  return rows[0];
};

/* =========================================================
   EVENT MASTER
========================================================= */

exports.createEvent = async (client, values) => {
  const query = `
    INSERT INTO pp.event_master (
      event_type_id,
      event_title,
      event_description,
      event_start_date,
      event_end_date,
      event_district,
      event_block,
      event_location,
      pincode,
      cohort_number,
      boys_attended,
      girls_attended,
      parents_attended,
      created_by,
      updated_by
    )
    VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
    RETURNING event_id
  `;
  const { rows } = await client.query(query, values);
  return rows[0].event_id;
};

// UPDATE EVENT
exports.updateEvent = async (client, values) => {
  const query = `
    UPDATE pp.event_master
    SET
      event_type_id = $1,
      event_title = $2,
      event_description = $3,
      event_start_date = $4,
      event_end_date = $5,
      event_district = $6,
      event_block = $7,
      event_location = $8,
      pincode = $9,
      cohort_number = $10,
      boys_attended = $11,
      girls_attended = $12,
      parents_attended = $13,
      updated_by = $14,
      updated_at = CURRENT_TIMESTAMP
    WHERE event_id = $15
  `;
  await client.query(query, values);
};

// DELETE EVENT
exports.deleteEvent = async (eventId) => {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");

    // 1. Delete student attendance records first (Foreign Key dependency)
    await client.query(`DELETE FROM pp.event_students WHERE event_id = $1`, [eventId]);

    // 2. Delete photos and reports associated with the event
    await client.query(`DELETE FROM pp.event_photos WHERE event_id = $1`, [eventId]);
    await client.query(`DELETE FROM pp.event_reports WHERE event_id = $1`, [eventId]);

    // 3. Delete the main event record
    await client.query(`DELETE FROM pp.event_master WHERE event_id = $1`, [eventId]);

    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
};

/* =========================================================
   EVENT PHOTOS
========================================================= */

exports.insertPhoto = async (db, values) => {
  const query = `
    INSERT INTO pp.event_photos (event_id, file_path, file_name, uploaded_by)
    VALUES ($1, $2, $3, $4)
  `;
  await db.query(query, values);
};

exports.getEventPhotos = async (eventId) => {
  const query = `
    SELECT photo_id, file_path, file_name
    FROM pp.event_photos
    WHERE event_id = $1
  `;
  const { rows } = await pool.query(query, [eventId]);
  return rows;
};

/* =========================================================
   EVENT REPORTS
========================================================= */

exports.insertEventReport = async (db, values) => {
  const query = `
    INSERT INTO pp.event_reports (event_id, report_type, file_path, file_name, generated_by)
    VALUES ($1, $2, $3, $4, $5)
  `;
  await db.query(query, values);
};

exports.getEventReports = async (eventId) => {
  const query = `
    SELECT *
    FROM pp.event_reports
    WHERE event_id = $1
    ORDER BY generated_at DESC
  `;
  const { rows } = await pool.query(query, [eventId]);
  return rows;
};

/* =========================================================
   FETCH EVENTS
========================================================= */

exports.getAllEvents = async () => {
  const query = `
    SELECT
      m.event_id,
      m.event_title,
      m.event_description,
      m.event_start_date AS start_date,
      m.event_end_date AS end_date,
      m.event_location,
      m.cohort_number,
      m.boys_attended,
      m.girls_attended,
      m.parents_attended,
      t.event_type_name AS event_type,
      (
        SELECT p.file_path
        FROM pp.event_photos p
        WHERE p.event_id = m.event_id
        LIMIT 1
      ) AS cover_photo
    FROM pp.event_master m
    JOIN pp.event_type t ON t.event_type_id = m.event_type_id
    ORDER BY m.event_start_date DESC
  `;
  const { rows } = await pool.query(query);
  return rows;
};

exports.getEventById = async (eventId) => {
  const query = `
    SELECT
      m.*,
      t.event_type_name
    FROM pp.event_master m
    JOIN pp.event_type t ON t.event_type_id = m.event_type_id
    WHERE m.event_id = $1
  `;
  const { rows } = await pool.query(query, [eventId]);
  return rows[0];
};

/* =========================================================
   For Sammelan Event attendece fill
========================================================= */
exports.getSammelanEvents = async () => {
  const query = `
    SELECT em.event_id, em.event_title 
    FROM pp.event_master em
    JOIN pp.event_type et ON et.event_type_id = em.event_type_id
    WHERE et.event_type_name = 'Sammelan'
  `;
  const { rows } = await pool.query(query);
  return rows;
};

exports.getStates = async () => {
  const query = `SELECT juris_code, juris_name FROM pp.jurisdiction WHERE LOWER(juris_type) = 'state'`;
  const { rows } = await pool.query(query);
  return rows;
};

exports.getDivisionsByState = async (stateName) => {
  const query = `
    SELECT juris_code, juris_name FROM pp.jurisdiction 
    WHERE parent_juris IN (
      SELECT juris_code FROM pp.jurisdiction 
      WHERE LOWER(TRIM(juris_name)) = LOWER(TRIM($1)) AND LOWER(juris_type) = 'state'
    ) AND LOWER(juris_type) = 'division'`;
  const { rows } = await pool.query(query, [stateName]);
  return rows;
};

exports.getDistrictsByDivisions = async (divisionNames) => {
  // Ensure divisionNames is an array and lowercase every element
  const lowerDivisions = Array.isArray(divisionNames)
    ? divisionNames.map((d) => d.toLowerCase().trim())
    : [divisionNames.toLowerCase().trim()];

  const query = `
    SELECT juris_code, juris_name FROM pp.jurisdiction 
    WHERE parent_juris IN (
      SELECT juris_code FROM pp.jurisdiction 
      WHERE LOWER(TRIM(juris_name)) = ANY($1) 
      AND LOWER(juris_type) = 'division'
    ) AND LOWER(juris_type) = 'education district'`;

  const { rows } = await pool.query(query, [lowerDivisions]);
  return rows;
};

exports.getBlocksByMultiDistricts = async (
  stateName,
  divisionNames,
  districtNames,
) => {
  // 1. Ensure inputs are arrays and lowercase them to match LOWER(TRIM()) in SQL
  const lowerDivisions = Array.isArray(divisionNames)
    ? divisionNames.map((d) => d.toLowerCase().trim())
    : [divisionNames.toLowerCase().trim()];

  const lowerDistricts = Array.isArray(districtNames)
    ? districtNames.map((d) => d.toLowerCase().trim())
    : [districtNames.toLowerCase().trim()];

  const query = `
    SELECT j.juris_code, j.juris_name,
      CASE WHEN j.juris_code IN (
        SELECT sbj.juris_code FROM pp.shortlist_batch_jurisdiction AS sbj
        JOIN pp.shortlist_batch AS sb ON sbj.shortlist_batch_id = sb.shortlist_batch_id
        WHERE sb.frozen_yn = 'Y'
      ) THEN TRUE ELSE FALSE END AS is_frozen_block
    FROM pp.jurisdiction AS j
    WHERE LOWER(j.juris_type) = 'block'
      AND j.parent_juris IN (
        SELECT d.juris_code FROM pp.jurisdiction d
        WHERE LOWER(TRIM(d.juris_name)) = ANY($3) 
          AND LOWER(d.juris_type) = 'education district'
          AND d.parent_juris IN (
            SELECT div.juris_code FROM pp.jurisdiction div
            WHERE LOWER(TRIM(div.juris_name)) = ANY($2)
              AND LOWER(div.juris_type) = 'division'
              AND div.parent_juris IN (
                SELECT s.juris_code FROM pp.jurisdiction s
                WHERE LOWER(TRIM(s.juris_name)) = LOWER(TRIM($1))
                  AND LOWER(s.juris_type) = 'state'
              )
          )
      )
  `;

  // Use the processed lowercase arrays here
  const { rows } = await pool.query(query, [
    stateName,
    lowerDivisions,
    lowerDistricts,
  ]);
  return rows;
};

exports.getSammelanStudentList = async (filters) => {
  const {
    eventId,
    cohortNumber,
    stateName,
    districtNames,
    blockNames,
    searchName,
    limit = 15,
    offset = 0,
  } = filters;

  const query = `
    SELECT DISTINCT
        sm.student_id,
        sm.student_name,
        bl.juris_name AS block_name,
        d.juris_name AS district_name,
        (es.student_id IS NOT NULL) AS is_marked
    FROM pp.student_master sm
    JOIN pp.applicant_primary_info a ON sm.applicant_id = a.applicant_id
    LEFT JOIN pp.event_students es ON sm.student_id = es.student_id AND es.event_id = $1
    LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code
    LEFT JOIN pp.jurisdiction bl ON a.nmms_block = bl.juris_code
    LEFT JOIN pp.jurisdiction s ON a.app_state = s.juris_code
    LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
    WHERE sm.active_yn = 'ACTIVE'
      AND b.cohort_number = $2
      AND ($3::text IS NULL OR s.juris_name = $3)
      AND ($4::text[] IS NULL OR d.juris_name = ANY($4))
      AND ($5::text[] IS NULL OR bl.juris_name = ANY($5))
      AND ($6::text IS NULL OR sm.student_name ILIKE '%' || $6 || '%')
    ORDER BY sm.student_name
    LIMIT $7 OFFSET $8;
  `;

  const values = [
    eventId,
    cohortNumber,
    stateName,
    districtNames,
    blockNames,
    searchName || null,
    limit,
    offset
  ];

  const { rows } = await pool.query(query, values);
  return rows;
};



/* =========================================================
   SAMMELAN EDIT SPECIFIC QUERIES
========================================================= */

// 1. Fetch only the students currently marked as present for this event
exports.getMarkedSammelanStudents = async (
  eventTitle,
  limit = 15,
  offset = 0,
) => {
  const query = `
    SELECT DISTINCT
        sm.student_id,
        sm.student_name,
        bl.juris_name AS block_name,
        d.juris_name AS district_name
    FROM pp.event_students e
    JOIN pp.student_master sm ON e.student_id = sm.student_id
    JOIN pp.event_master em ON e.event_id = em.event_id
    JOIN pp.applicant_primary_info a ON sm.applicant_id = a.applicant_id
    LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code
    LEFT JOIN pp.jurisdiction bl ON a.nmms_block = bl.juris_code
    LEFT JOIN pp.jurisdiction s ON a.app_state = s.juris_code
    WHERE sm.active_yn = 'ACTIVE'
      AND em.event_title = $1
    ORDER BY sm.student_name
    LIMIT $2 OFFSET $3;
  `;
  const { rows } = await pool.query(query, [eventTitle, limit, offset]);
  return rows;
};

// 2. Remove students who were unchecked (Marked Absent)
exports.removeSammelanAttendance = async (
  client,
  eventId,
  studentIdsToRemove,
) => {
  if (!studentIdsToRemove || studentIdsToRemove.length === 0) return;
  const query = `
    DELETE FROM pp.event_students 
    WHERE event_id = $1 AND student_id = ANY($2::int[])
  `;
  await client.query(query, [eventId, studentIdsToRemove]);
};

// 3. Delete old report from DB (used when replacing)
exports.deleteOldReport = async (client, eventId) => {
  const fetchQuery = `SELECT file_path FROM pp.event_reports WHERE event_id = $1 AND report_type = 'SAMMELAN_REPORT'`;
  const { rows } = await client.query(fetchQuery, [eventId]);

  // Delete from DB
  await client.query(
    `DELETE FROM pp.event_reports WHERE event_id = $1 AND report_type = 'SAMMELAN_REPORT'`,
    [eventId],
  );

  return rows; // Return paths so controller can delete from disk
};
// Add this to the bottom of models/eventModel.js
exports.saveSammelanAttendance = async (db, eventId, presentStudentIds) => {
  const query = `
    INSERT INTO pp.event_students (event_id, student_id)
    SELECT $1, unnest($2::int[])
    ON CONFLICT (event_id, student_id) DO NOTHING
    RETURNING student_id;
  `;
  const { rows } = await db.query(query, [eventId, presentStudentIds]);
  return rows.length;
};

// 1. FETCH ONLY EXISTING: Used for the initial load of the Edit Page
exports.getExistingEventStudents = async (eventId) => {
  const query = `
        SELECT 
            sm.student_id,
            sm.student_name,
            d.juris_name AS district_name,
            bl.juris_name AS block_name
        FROM pp.event_students e
        JOIN pp.student_master sm ON e.student_id = sm.student_id
        JOIN pp.event_master em ON e.event_id = em.event_id
        JOIN pp.applicant_primary_info a ON sm.applicant_id = a.applicant_id
        LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code
        LEFT JOIN pp.jurisdiction bl ON a.nmms_block = bl.juris_code
        WHERE em.event_id = $1
          AND sm.active_yn = 'ACTIVE'
        ORDER BY sm.student_name;
    `;
  const { rows } = await pool.query(query, [eventId]);
  return rows;
};

// 2. ATTENDANCE SYNC: Wipes old records, inserts new ones, and updates Master Counts
exports.editSammelanAttendanceSync = async (
  client,
  eventId,
  presentStudentIds,
  parentsCount,
  userId // Added parameter
) => {
  // Wipe current attendees
  await client.query(`DELETE FROM pp.event_students WHERE event_id = $1`, [eventId]);

  if (presentStudentIds && presentStudentIds.length > 0) {
    // Re-insert students
    await client.query(`INSERT INTO pp.event_students (event_id, student_id) SELECT $1, unnest($2::numeric[])`, [eventId, presentStudentIds]);

    // Recalculate counts
    const { rows: genderCounts } = await client.query(`SELECT gender, COUNT(*) as count FROM pp.student_master WHERE student_id = ANY($1::numeric[]) GROUP BY gender`, [presentStudentIds]);

    let boys = 0, girls = 0;
    genderCounts.forEach((row) => {
      const g = row.gender?.toUpperCase();
      if (g === "MALE" || g === "M") boys = parseInt(row.count);
      if (g === "FEMALE" || g === "F") girls = parseInt(row.count);
    });

    // Update Master Table with audit tracking
    const updateQuery = `
            UPDATE pp.event_master 
            SET boys_attended = $1, 
                girls_attended = $2, 
                parents_attended = $3,
                updated_by = $4,
                updated_at = CURRENT_TIMESTAMP
            WHERE event_id = $5`;
    await client.query(updateQuery, [boys, girls, parentsCount, userId, eventId]);
  } else {
    // Reset if list is empty
    await client.query(
      `UPDATE pp.event_master SET boys_attended = 0, girls_attended = 0, parents_attended = $1, updated_by = $2, updated_at = CURRENT_TIMESTAMP WHERE event_id = $3`,
      [parentsCount, userId, eventId]
    );
  }
};
