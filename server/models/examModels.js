const pool = require("../config/db");

// Exam Centre Models
async function getExamCentres() {
    const result = await pool.query(
        `SELECT pp_exam_centre_id, pp_exam_centre_name
FROM pp.pp_exam_centre
WHERE active_yn = 'Y'
ORDER BY pp_exam_centre_name ASC;`
    );
    return result.rows;
}

async function addExamCentre(data) {
  const {
    pp_exam_centre_code,
    pp_exam_centre_name,
    address,
    village,
    pincode,
    contact_person,
    contact_phone,
    contact_email,
    sitting_capacity,
    latitude,
    longitude,
    created_by,
  } = data;

  const created_at = new Date();

  const query = `
    INSERT INTO pp.pp_exam_centre (
      pp_exam_centre_code,
      pp_exam_centre_name,
      address,
      village,
      pincode,
      contact_person,
      contact_phone,
      contact_email,
      sitting_capacity,
      latitude,
      longitude,
      created_at,
      created_by,
      active_yn
    )
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
    RETURNING *;
  `;

  const values = [
    pp_exam_centre_code || null,
    pp_exam_centre_name,
    address || null,
    village || null,
    pincode || null,
    contact_person || null,
    contact_phone || null,
    contact_email || null,
    sitting_capacity ? parseInt(sitting_capacity) : null,
    latitude ? parseFloat(latitude) : null,
    longitude ? parseFloat(longitude) : null,
    created_at,
    created_by || null,
    'Y' // active_yn default to 'Y'
  ];

  try {
    const result = await pool.query(query, values);
    return result.rows[0];
  } catch (error) {
    console.error("Database insert error:", error);
    throw error;
  }
}

async function deleteExamCentre(id) {
    // Check if centre is used
    const result = await pool.query(
        `SELECT exam_name 
         FROM pp.examination 
         WHERE pp_exam_centre_id = $1 
         LIMIT 1`,
        [id]
    );

    if (result.rows.length > 0) {
        throw new Error(
            `Centre already used in exam: ${result.rows[0].exam_name}`
        );
    }

    // If not used → delete
    await pool.query(
        "DELETE FROM pp.pp_exam_centre WHERE pp_exam_centre_id = $1",
        [id]
    );
}

// Location Models
async function getDivisionsByState(stateId) {
  const result = await pool.query(`
    SELECT JURIS_CODE AS id, JURIS_NAME AS name
    FROM PP.JURISDICTION
    WHERE JURIS_TYPE = 'DIVISION'
      AND PARENT_JURIS = $1
    ORDER BY JURIS_NAME
  `, [stateId]);
  return result.rows;
}

// 2️⃣ Education Districts by Division
async function getEducationDistrictsByDivision(divisionId) {
  const result = await pool.query(`
    SELECT JURIS_CODE AS id, JURIS_NAME AS name
    FROM PP.JURISDICTION
    WHERE JURIS_TYPE = 'EDUCATION DISTRICT'
      AND PARENT_JURIS = $1
    ORDER BY JURIS_NAME
  `, [divisionId]);
  return result.rows;
}

// 3️⃣ Blocks by Education District
async function getBlocksByDistrict(districtId) {
  const result = await pool.query(`
    SELECT JURIS_CODE AS id, JURIS_NAME AS name
    FROM PP.JURISDICTION
    WHERE JURIS_TYPE = 'BLOCK'
      AND PARENT_JURIS = $1
    ORDER BY JURIS_NAME
  `, [districtId]);
  return result.rows;
}

// 4️⃣ Clusters by Block
async function getClustersByBlock(blockId) {
  const result = await pool.query(`
    SELECT JURIS_CODE AS id, JURIS_NAME AS name
    FROM PP.JURISDICTION
    WHERE JURIS_TYPE = 'CLUSTER'
      AND PARENT_JURIS = $1
    ORDER BY JURIS_NAME
  `, [blockId]);
  return result.rows;
}

//bsed on the year
async function getUsedBlocks(year) {
  const result = await pool.query(
    `
    SELECT DISTINCT api.nmms_block
    FROM pp.applicant_primary_info api

    INNER JOIN pp.applicant_exam ae 
      ON api.applicant_id = ae.applicant_id

    INNER JOIN pp.examination e 
      ON ae.exam_id = e.exam_id

    WHERE e.exam_year = $1   -- ✅ KEY FIX
    `,
    [year] // "2025"
  );

  return result.rows.map(row => Number(row.nmms_block));
}

// Exam Models
async function getAllExams(year) {
  const result = await pool.query(
    `
    SELECT 
      e.exam_id,
      e.exam_name,
      e.exam_date,
      e.frozen_yn,
      e.pp_exam_centre_id,
      c.pp_exam_centre_name,
      e.exam_start_time,
      e.exam_end_time,
      ARRAY_AGG(DISTINCT jd.juris_code) AS district_ids,
      ARRAY_AGG(DISTINCT jd.juris_name) AS district_names,
      ARRAY_AGG(DISTINCT jb.juris_code) AS block_ids,
      ARRAY_AGG(DISTINCT jb.juris_name) AS block_names
    FROM pp.examination e
    LEFT JOIN pp.pp_exam_centre c 
      ON e.pp_exam_centre_id = c.pp_exam_centre_id
    JOIN pp.applicant_exam ae 
      ON ae.exam_id = e.exam_id
    JOIN pp.applicant_primary_info api 
      ON ae.applicant_id = api.applicant_id
    LEFT JOIN pp.jurisdiction jd 
      ON api.district = jd.juris_code
    LEFT JOIN pp.jurisdiction jb 
      ON api.nmms_block = jb.juris_code
    WHERE 
      e.exam_year = $1   -- ✅ FILTER BY YEAR
    GROUP BY 
      e.exam_id, e.exam_name, e.exam_date, 
      e.pp_exam_centre_id, c.pp_exam_centre_name
    ORDER BY e.exam_date DESC
    `,
    [year] // "2025"
  );

  return result.rows;
}

////done dusted for the exam not assignes students
async function getAllExamsnotassigned(year) {
  const result = await pool.query(
    `
    SELECT
      e.exam_id,
      e.exam_name,
      e.exam_date,
      e.frozen_yn,
      e.pp_exam_centre_id,
      c.pp_exam_centre_name,
      e.exam_start_time,
      e.exam_end_time
    FROM pp.examination e
    LEFT JOIN pp.pp_exam_centre c 
      ON e.pp_exam_centre_id = c.pp_exam_centre_id
    WHERE 
      e.exam_year = $1
      AND NOT EXISTS (
        SELECT 1
        FROM pp.applicant_exam ae
        WHERE ae.exam_id = e.exam_id
      )
    ORDER BY e.exam_date DESC
    `,
    [year] // ✅ "2025"
  );

  return result.rows;
}

async function addcreateExamonly({
  centreId,
  examName,
  date,
  startTime,
  endTime,
  examYear,
}) {
  const client = await pool.connect();

  try {
    await client.query("BEGIN");

    // ✅ Check conflicts
    const existingExams = await client.query(
      `SELECT exam_id, exam_name, exam_start_time, exam_end_time 
       FROM pp.examination 
       WHERE pp_exam_centre_id = $1 
         AND exam_date = $2
         AND exam_year = $3`,
      [centreId, date, examYear]
    );

    for (const existingExam of existingExams.rows) {
      const existingStart = existingExam.exam_start_time;
      const existingEnd = existingExam.exam_end_time;

      const isOverlapping =
        (startTime >= existingStart && startTime < existingEnd) ||
        (endTime > existingStart && endTime <= existingEnd) ||
        (startTime <= existingStart && endTime >= existingEnd);

      if (isOverlapping) {
        await client.query("ROLLBACK");

        return {
          conflict: true,
          message: `Exam exists from ${existingStart} to ${existingEnd}`,
        };
      }
    }

    // ✅ Insert exam
    const insertResult = await client.query(
      `INSERT INTO pp.examination 
       (exam_name, exam_date, pp_exam_centre_id, exam_start_time, exam_end_time, exam_year)
       VALUES ($1, $2, $3, $4, $5, $6)
       RETURNING exam_id`,
      [examName, date, centreId, startTime, endTime, examYear]
    );

    await client.query("COMMIT");

    return {
      conflict: false,
      examId: insertResult.rows[0].exam_id,
    };
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}




async function deleteExamById(examId) {
    await pool.query("BEGIN");
    await pool.query("DELETE FROM pp.applicant_exam WHERE exam_id = $1", [examId]);
    await pool.query("DELETE FROM pp.examination WHERE exam_id = $1", [examId]);
    await pool.query("COMMIT");
}

async function getexamcentresview(){
  const res = await pool.query(`select *from pp.pp_exam_centre`);
  return res.rows;
}

module.exports = {
    getExamCentres,
    addExamCentre,
    deleteExamCentre,
    getDivisionsByState,
  getEducationDistrictsByDivision,
  getBlocksByDistrict,
  getClustersByBlock,
    getUsedBlocks,
    getAllExams,
    getAllExamsnotassigned,
    deleteExamById,
    getexamcentresview,
    addcreateExamonly
};