const pool = require("../config/db.js");
const { parse } = require("csv-parse/sync");
const stringSimilarity = require("string-similarity");

// ================= VALIDATION HELPERS =================
const isYearValid = (year) => /^\d{4}$/.test(year);
const isDiseValid = (code) => /^\d{11}$/.test(code);
const isSatsValid = (id) => /^\d{8,12}$/.test(id);
const isNameValid = (name) => /^[A-Za-z\s.]+$/.test(name);
const isPhoneValid = (phone) => /^[6-9]\d{9}$/.test(phone);
const isScoreValid = (score) => !isNaN(score) || score === 'A';

const normalizeText = (text) => text?.toUpperCase().replace(/[^A-Z]/g, "");

const loadBlocks = async (client, districtId) => {
  const { rows } = await client.query(
    `SELECT juris_code, juris_name FROM pp.jurisdiction WHERE parent_juris = $1`,
    [districtId]
  );
  const blockMap = new Map();
  rows.forEach(r => blockMap.set(normalizeText(r.juris_name), r.juris_code));
  return blockMap;
};

const suggestValue = (input, options) => {
  const key = normalizeText(input);
  let best = null;
  let score = 0;
  for (const option of options) {
    const optionKey = normalizeText(option);
    let match = 0;
    for (let i = 0; i < Math.min(optionKey.length, key.length); i++) {
      if (optionKey[i] === key[i]) match++;
    }
    const ratio = match / Math.max(optionKey.length, key.length);
    if (ratio > score) {
      score = ratio;
      best = option;
    }
  }
  return score > 0.4 ? best : null;
};

exports.getSuggestion = async (client, input, parentId = null) => {
  let query = `SELECT juris_name FROM pp.jurisdiction`;
  let values = [];
  if (parentId) {
    query += ` WHERE parent_juris = $1`;
    values.push(parentId);
  }
  const res = await client.query(query, values);
  const validNames = res.rows.map(r => r.juris_name);
  const { bestMatch } = stringSimilarity.findBestMatch(input, validNames);
  return bestMatch.rating > 0.5 ? bestMatch.target : null;
};

const generateStudentNameKey = (name) =>
  (name || "").toLowerCase().replace(/[^a-z0-9]/g, "");

// ================= EXPORTED MODELS =================

exports.getJurisdictionsModel = async (type, parentId) => {
  let query = `SELECT DISTINCT juris_code, juris_name FROM pp.jurisdiction WHERE juris_type = $1`;
  let params = [type];
  if (parentId) {
    query += ` AND parent_juris = $2`;
    params.push(parentId);
  }
  query += ` ORDER BY juris_name ASC`;
  const { rows } = await pool.query(query, params);
  return rows;
};

exports.getApplicationsModel = async ({ year, district, search, page, limit }) => {
  const offset = (page - 1) * limit;
  let q = `SELECT a.*, d.juris_name as district_name, b.juris_name as nmms_block_name FROM pp.stg_nmms_phase1_applications a LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code LEFT JOIN pp.jurisdiction b ON a.nmms_block = b.juris_code WHERE a.nmms_year = $1 AND a.district = $2`;
  let params = [year, district];
  if (search) { q += ` AND a.student_name ILIKE $3`; params.push(`%${search}%`); }
  const { rows } = await pool.query(q + ` LIMIT $${params.length + 1} OFFSET $${params.length + 2}`, [...params, limit, offset]);
  const countRes = await pool.query(`SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE nmms_year = $1 AND district = $2`, [year, district]);
  return { rows, totalPages: Math.ceil(parseInt(countRes.rows[0].count) / limit) };
};

exports.getResultsModel = async ({ year, district, search, page, limit }) => {
  const offset = (page - 1) * limit;
  let q = `SELECT r.*, d.juris_name as district_name, b.juris_name as nmms_block_name FROM pp.stg_nmms_phase2_results r LEFT JOIN pp.jurisdiction d ON r.district = d.juris_code LEFT JOIN pp.jurisdiction b ON r.nmms_block = b.juris_code WHERE r.nmms_year = $1 AND r.district = $2`;
  let params = [year, district];
  if (search) { q += ` AND r.student_name ILIKE $3`; params.push(`%${search}%`); }
  const { rows } = await pool.query(q + ` LIMIT $${params.length + 1} OFFSET $${params.length + 2}`, [...params, limit, offset]);
  const countRes = await pool.query(`SELECT COUNT(*) FROM pp.stg_nmms_phase2_results WHERE nmms_year = $1 AND district = $2`, [year, district]);
  return { rows, totalPages: Math.ceil(parseInt(countRes.rows[0].count) / limit) };
};

// exports.resolveMatchModel = async (appId, resId, userId) => {
//   const client = await pool.connect();
//   try {
//     await client.query("BEGIN");
//     const appRes = await client.query(`SELECT * FROM pp.stg_nmms_phase1_applications WHERE id = $1`, [appId]);
//     const resRes = await client.query(`SELECT * FROM pp.stg_nmms_phase2_results WHERE result_stg_id = $1`, [resId]);

//     if (appRes.rows.length === 0 || resRes.rows.length === 0) throw new Error("Records not found.");
//     const app = appRes.rows[0];
//     const res = resRes.rows[0];

//     await client.query(`
//       INSERT INTO pp.std_applicant_primary_info (
//         nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, contact_no1, current_institute_dise_code, created_by
//       ) VALUES ($1::numeric, $2::numeric, NULLIF(regexp_replace($3, '\\D', '', 'g'), '')::numeric, $4, $5, $6::numeric, $7::numeric, $8::numeric, $9::numeric, $10::numeric, $11, $12, $13::numeric)`,
//       [app.nmms_year, res.nmms_reg_number, app.students_sats_id, app.student_name, app.father_name, app.app_state, app.district, app.nmms_block, (res.gmat_score === 'AB' ? '0' : res.gmat_score), (res.sat_score === 'AB' ? '0' : res.sat_score), app.contact_no1, app.current_institute_dise_code, userId]
//     );

//     await client.query(`UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED' WHERE result_stg_id = $1`, [resId]);

//     const remainingApp = await client.query(`
//       SELECT * FROM pp.stg_nmms_phase1_applications WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE($1, '[^a-zA-Z0-9]', '', 'g')) AND nmms_block = $2`, [app.student_name, app.nmms_block]
//     );
//     const remainingRes = await client.query(`
//       SELECT * FROM pp.stg_nmms_phase2_results WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE($1, '[^a-zA-Z0-9]', '', 'g')) AND nmms_block = $2 AND match_status != 'MATCHED'`, [app.student_name, app.nmms_block]
//     );

//     if (remainingApp.rows.length === 1 && remainingRes.rows.length === 1) {
//       const autoApp = remainingApp.rows[0];
//       const autoRes = remainingRes.rows[0];
//       await client.query(`
//         INSERT INTO pp.std_applicant_primary_info (
//           nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, contact_no1, current_institute_dise_code, created_by
//         ) VALUES ($1::numeric, $2::numeric, NULLIF(regexp_replace($3, '\\D', '', 'g'), '')::numeric, $4, $5, $6::numeric, $7::numeric, $8::numeric, $9::numeric, $10::numeric, $11, $12, $13::numeric)`,
//         [autoApp.nmms_year, autoRes.nmms_reg_number, autoApp.students_sats_id, autoApp.student_name, autoApp.father_name, autoApp.app_state, autoApp.district, autoApp.nmms_block, (autoRes.gmat_score === 'AB' ? '0' : autoRes.gmat_score), (autoRes.sat_score === 'AB' ? '0' : autoRes.sat_score), autoApp.contact_no1, autoApp.current_institute_dise_code, userId]
//       );
//       await client.query(`UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED' WHERE result_stg_id = $1`, [autoRes.result_stg_id]);
//     }

//     await client.query("COMMIT");
//     return { success: true };
//   } catch (e) {
//     await client.query("ROLLBACK");
//     console.error("Resolve Match Error:", e);
//     throw e;
//   } finally {
//     client.release();
//   }
// };

exports.resolveMatchModel = async (appId, resId, userId) => {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");

    const appRes = await client.query(
      `SELECT * FROM pp.stg_nmms_phase1_applications WHERE id = $1`,
      [appId]
    );

    const resRes = await client.query(
      `SELECT * FROM pp.stg_nmms_phase2_results WHERE result_stg_id = $1`,
      [resId]
    );

    if (appRes.rows.length === 0 || resRes.rows.length === 0) {
      throw new Error("Records not found.");
    }

    const app = appRes.rows[0];
    const res = resRes.rows[0];

    // ✅ MAIN INSERT (manual match)
    await client.query(
      `
      INSERT INTO pp.std_applicant_primary_info (
        nmms_year,
        nmms_reg_number,
        students_sats_id,
        student_name,
        father_name,
        app_state,
        district,
        nmms_block,
        gmat_score,
        sat_score,
        contact_no1,
        contact_no2,  -- ✅ ADDED
        current_institute_dise_code,
        created_by
      )
      VALUES (
        $1::numeric,
        $2::numeric,
        NULLIF(regexp_replace($3, '\\D', '', 'g'), '')::numeric,
        $4,
        $5,
        $6::numeric,
        $7::numeric,
        $8::numeric,
        $9::numeric,
        $10::numeric,
        $11,
        $12,  -- ✅ NEW
        $13,
        $14::numeric
      )
      `,
      [
        app.nmms_year,
        res.nmms_reg_number,
        app.students_sats_id,
        app.student_name,
        app.father_name,
        app.app_state,
        app.district,
        app.nmms_block,
        res.gmat_score === 'AB' ? '0' : res.gmat_score,
        res.sat_score === 'AB' ? '0' : res.sat_score,
        app.contact_no1,
        app.contact_no2, // ✅ NEW
        app.current_institute_dise_code,
        userId
      ]
    );

    await client.query(
      `UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED' WHERE result_stg_id = $1`,
      [resId]
    );

    // 🔁 DOMINO EFFECT CHECK
    const remainingApp = await client.query(
      `
      SELECT * FROM pp.stg_nmms_phase1_applications
      WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) =
            LOWER(REGEXP_REPLACE($1, '[^a-zA-Z0-9]', '', 'g'))
      AND nmms_block = $2
      `,
      [app.student_name, app.nmms_block]
    );

    const remainingRes = await client.query(
      `
      SELECT * FROM pp.stg_nmms_phase2_results
      WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) =
            LOWER(REGEXP_REPLACE($1, '[^a-zA-Z0-9]', '', 'g'))
      AND nmms_block = $2
      AND match_status != 'MATCHED'
      `,
      [app.student_name, app.nmms_block]
    );

    // ✅ AUTO MATCH INSERT (domino case)
    if (remainingApp.rows.length === 1 && remainingRes.rows.length === 1) {
      const autoApp = remainingApp.rows[0];
      const autoRes = remainingRes.rows[0];

      await client.query(
        `
        INSERT INTO pp.std_applicant_primary_info (
          nmms_year,
          nmms_reg_number,
          students_sats_id,
          student_name,
          father_name,
          app_state,
          district,
          nmms_block,
          gmat_score,
          sat_score,
          contact_no1,
          contact_no2,  -- ✅ ADDED
          current_institute_dise_code,
          created_by
        )
        VALUES (
          $1::numeric,
          $2::numeric,
          NULLIF(regexp_replace($3, '\\D', '', 'g'), '')::numeric,
          $4,
          $5,
          $6::numeric,
          $7::numeric,
          $8::numeric,
          $9::numeric,
          $10::numeric,
          $11,
          $12,  -- ✅ NEW
          $13,
          $14::numeric
        )
        `,
        [
          autoApp.nmms_year,
          autoRes.nmms_reg_number,
          autoApp.students_sats_id,
          autoApp.student_name,
          autoApp.father_name,
          autoApp.app_state,
          autoApp.district,
          autoApp.nmms_block,
          autoRes.gmat_score === 'AB' ? '0' : autoRes.gmat_score,
          autoRes.sat_score === 'AB' ? '0' : autoRes.sat_score,
          autoApp.contact_no1,
          autoApp.contact_no2, // ✅ NEW
          autoApp.current_institute_dise_code,
          userId
        ]
      );

      await client.query(
        `UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED' WHERE result_stg_id = $1`,
        [autoRes.result_stg_id]
      );
    }

    await client.query("COMMIT");
    return { success: true };

  } catch (e) {
    await client.query("ROLLBACK");
    console.error("Resolve Match Error:", e);
    throw e;
  } finally {
    client.release();
  }
};

exports.moveMappedToStdModel = async (districtId, year, userId) => {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query(`
      INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, contact_no1, contact_no2, current_institute_dise_code, created_by)
      SELECT a.nmms_year::numeric, r.nmms_reg_number::numeric, NULLIF(regexp_replace(a.students_sats_id, '\\D', '', 'g'), '')::numeric, a.student_name, a.father_name, a.app_state::numeric, a.district::numeric, a.nmms_block::numeric, (CASE WHEN r.gmat_score = 'AB' OR r.gmat_score IS NULL THEN '0' ELSE r.gmat_score END)::numeric, (CASE WHEN r.sat_score = 'AB' OR r.sat_score IS NULL THEN '0' ELSE r.sat_score END)::numeric, a.contact_no1, a.contact_no2, a.current_institute_dise_code, $3::numeric
      FROM pp.stg_nmms_phase1_applications a
      JOIN pp.stg_nmms_phase2_results r ON LOWER(REGEXP_REPLACE(a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(r.student_name, '[^a-zA-Z0-9]', '', 'g')) AND a.nmms_block = r.nmms_block
      WHERE a.district = $1 AND a.nmms_year = $2 AND r.match_status != 'MATCHED'
      AND a.id IN (
        SELECT sub_a.id FROM pp.stg_nmms_phase1_applications sub_a JOIN pp.stg_nmms_phase2_results sub_r ON LOWER(REGEXP_REPLACE(sub_a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(sub_r.student_name, '[^a-zA-Z0-9]', '', 'g')) AND sub_a.nmms_block = sub_r.nmms_block WHERE sub_a.district = $1 GROUP BY sub_a.id HAVING COUNT(*) = 1
      )
      ON CONFLICT (nmms_reg_number) DO NOTHING`, [districtId, year, userId]);

    await client.query(`UPDATE pp.stg_nmms_phase2_results r SET match_status = 'MATCHED' FROM pp.std_applicant_primary_info s WHERE r.nmms_reg_number::numeric = s.nmms_reg_number AND r.district = $1`, [districtId]);
    await client.query("COMMIT");
    return { success: true };
  } catch (err) { await client.query("ROLLBACK"); throw err; }
  finally { client.release(); }
};

exports.commitToPrimaryModel = async (districtId, year) => {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query(`
      INSERT INTO pp.applicant_primary_info (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, created_by , current_institute_dise_code , contact_no1 , contact_no2)
      SELECT nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, created_by ,current_institute_dise_code , contact_no1 , contact_no2
      FROM pp.std_applicant_primary_info WHERE district = $1 AND nmms_year = $2
      ON CONFLICT (nmms_reg_number) DO NOTHING`, [districtId, year]);
    await client.query("COMMIT");
    return { success: true };
  } catch (e) { await client.query("ROLLBACK"); console.error("Commit Error:", e); throw e; }
  finally { client.release(); }
};

exports.getDraftStdDistrictsModel = async () => {
  const { rows } = await pool.query(`
    SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year, COUNT(*) AS total_applicants, COALESCE(m.total_merged, 0) AS total_merged_applicants, COUNT(*) - COALESCE(m.total_merged, 0) AS remaining_applicants
    FROM pp.stg_nmms_phase1_applications s LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_merged FROM pp.std_applicant_primary_info GROUP BY district, nmms_year) m ON s.district::numeric = m.district::numeric AND s.nmms_year::text = m.nmms_year::text
    JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric GROUP BY j.juris_name, s.district, s.nmms_year, m.total_merged ORDER BY j.juris_name`);
  return rows.map(d => ({ district_name: d.district_name, district_id: Number(d.district_id), year: Number(d.year), total_applicants: Number(d.total_applicants), total_merged_applicants: Number(d.total_merged_applicants), remaining_applicants: Number(d.remaining_applicants) }));
};

exports.getStdDistrictStudentsModel = async (districtId, year) => {
  const { rows } = await pool.query(`SELECT ROW_NUMBER() OVER (ORDER BY s.student_name) AS sl_no, s.student_name, j1.juris_name AS district_name, j2.juris_name AS block_name, s.current_institute_dise_code, s.nmms_reg_number, s.gmat_score, s.sat_score FROM pp.std_applicant_primary_info s LEFT JOIN pp.jurisdiction j1 ON s.district = j1.juris_code LEFT JOIN pp.jurisdiction j2 ON s.nmms_block = j2.juris_code WHERE s.district = $1 AND s.nmms_year = $2 ORDER BY s.student_name`, [districtId, year]);
  return rows;
};

exports.getMergedDistrictsModel = async () => {
  const { rows } = await pool.query(`SELECT j.juris_name AS district_name, a.district AS district_id, a.nmms_year AS year, COUNT(a.applicant_id) AS student_count FROM pp.applicant_primary_info a JOIN pp.jurisdiction j ON a.district = j.juris_code GROUP BY j.juris_name, a.district, a.nmms_year ORDER BY j.juris_name`);
  return rows;
};

exports.deleteDistrictDataModel = async (district, type) => {
  let table = type === "p1" ? "pp.stg_nmms_phase1_applications" : type === "p2" ? "pp.stg_nmms_phase2_results" : type === "merge" ? "pp.std_applicant_primary_info" : null;
  if (!table) throw new Error("Invalid type for deletion");
  const delRes = await pool.query(`DELETE FROM ${table} WHERE district = $1`, [district]);
  return delRes.rowCount;
};

exports.commitStatusModel = async (year) => {
  const { rows } = await pool.query(`SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year, COUNT(*) AS total_applicants, COALESCE(c.total_committed, 0) AS total_committed, COALESCE(c.total_committed, 0) = COUNT(*) AS is_committed
    FROM pp.stg_nmms_phase1_applications s JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_committed FROM pp.applicant_primary_info GROUP BY district, nmms_year) c ON s.district::numeric = c.district::numeric AND s.nmms_year::text = c.nmms_year::text
    WHERE s.nmms_year = $1 GROUP BY j.juris_name, s.district, s.nmms_year, c.total_committed ORDER BY j.juris_name`, [year]);
  return rows;
};

exports.isMergedModel = async (year) => {
  const { rows } = await pool.query(`SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year, COUNT(*) AS total_applicants, COALESCE(m.total_merged, 0) AS total_merged_applicants, COUNT(*) - COALESCE(m.total_merged, 0) AS remaining_applicants, CASE WHEN COALESCE(m.total_merged, 0) = COUNT(*) THEN true ELSE false END AS ismerged
    FROM pp.stg_nmms_phase1_applications s LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_merged FROM pp.std_applicant_primary_info GROUP BY district, nmms_year) m ON s.district::numeric = m.district::numeric AND s.nmms_year::text = m.nmms_year::text
    JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric WHERE s.nmms_year = $1 GROUP BY j.juris_name, s.district, s.nmms_year, m.total_merged ORDER BY j.juris_name`, [year]);
  return rows.map(d => ({ district_name: d.district_name, district_id: Number(d.district_id), year: Number(d.year), total_applicants: Number(d.total_applicants), total_merged_applicants: Number(d.total_merged_applicants), remaining_applicants: Number(d.remaining_applicants), ismerged: d.ismerged }));
};

exports.getDistrictMergedDataModel = async (districtId) => {
  const { rows } = await pool.query(`SELECT s.student_name, s.father_name, s.nmms_reg_number, s.students_sats_id, d.juris_name AS district_name, b.juris_name AS block_name, s.gmat_score, s.sat_score, s.contact_no1 FROM pp.std_applicant_primary_info s LEFT JOIN pp.jurisdiction d ON s.district = d.juris_code LEFT JOIN pp.jurisdiction b ON s.nmms_block = b.juris_code WHERE s.district = $1 ORDER BY s.student_name`, [districtId]);
  return rows;
};

exports.checkStdPrimaryModel = async (districtId, year) => {
  const { rows } = await pool.query(
    `SELECT 1 FROM pp.std_applicant_primary_info WHERE district = $1 AND nmms_year = $2 LIMIT 1`,
    [districtId, year]
  );
  return rows.length > 0;
};

exports.checkApplicantPrimaryModel = async (districtId, year) => {
  const { rows } = await pool.query(`SELECT 1 FROM pp.applicant_primary_info WHERE district=$1 AND nmms_year=$2 LIMIT 1`, [districtId, year]);
  return rows.length > 0;
};

exports.uploadPhase1Model = async ({ file, year, state_id, district_id }) => {
  const client = await pool.connect();
  const logs = [];
  const validRows = [];
  const reportedBlocks = new Set();
  const reportedDistricts = new Set();
  const reportedStates = new Set();

  try {
    const records = parse(file, {
      columns: header => header.map(h => h.trim().replace(/^\uFEFF/, '')),
      skip_empty_lines: true
    });

    await client.query("BEGIN");

    const checkDuplicate = await client.query(
      `SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE district = $1 AND nmms_year = $2`,
      [district_id, String(year)]
    );

    if (parseInt(checkDuplicate.rows[0].count) > 0) {
      await client.query("ROLLBACK");
      return { success: false, logs: [`Upload Rejected: Data for Year ${year} already uploaded for this district.`] };
    }

    const stateRes = await client.query(`SELECT juris_name FROM pp.jurisdiction WHERE juris_code=$1`, [state_id]);
    const districtRes = await client.query(`SELECT juris_name FROM pp.jurisdiction WHERE juris_code=$1`, [district_id]);
    const selectedStateName = stateRes.rows[0]?.juris_name;
    const selectedDistrictName = districtRes.rows[0]?.juris_name;

    const blockMap = await loadBlocks(client, district_id);
    const blockNames = [...blockMap.keys()];

    const diseInFile = new Set();
    records.forEach(r => {
      const c = String(r.current_institute_dise_code || "").replace(/[^0-9]/g, "").trim();
      if (c) diseInFile.add(c);
    });
    const diseRes = await client.query(`SELECT dise_code FROM pp.institute WHERE dise_code = ANY($1)`, [Array.from(diseInFile)]);
    const validDiseSet = new Set(diseRes.rows.map(r => String(r.dise_code)));

    for (let i = 0; i < records.length; i++) {
      const row = records[i];
      const rowNum = i + 1;
      let rowError = false;

      const cleanRowYear = String(row.nmms_year || "").trim();
      if (cleanRowYear !== String(year)) {
        logs.push(`Row ${rowNum}: Year Mismatch (File has "${cleanRowYear || 'Empty'}", expected "${year}")`);
        rowError = true;
      }

      const inputState = String(row.app_state || "").trim();
      if (normalizeText(inputState) !== normalizeText(selectedStateName)) {
        if (!reportedStates.has(inputState)) {
          logs.push(`Row ${rowNum}: State Mismatch (File: "${inputState}", Expected: "${selectedStateName}")`);
          reportedStates.add(inputState);
        }
        rowError = true;
      }

      const inputDist = String(row.district || "").replace(/\./g, '').trim();
      if (normalizeText(inputDist) !== normalizeText(selectedDistrictName)) {
        if (!reportedDistricts.has(inputDist)) {
          logs.push(`Row ${rowNum}: District Mismatch (File: "${inputDist}", Expected: "${selectedDistrictName}")`);
          reportedDistricts.add(inputDist);
        }
        rowError = true;
      }

      const rawBlock = String(row.nmms_block || "").trim();
      const blockKey = normalizeText(rawBlock);
      const blockId = blockMap.get(blockKey);

      if (!blockId) {
        if (!reportedBlocks.has(blockKey)) {
          const suggestion = suggestValue(rawBlock, blockNames);
          logs.push(`Row ${rowNum}: Block "${rawBlock}" not found. ${suggestion ? 'Did you mean "' + suggestion + '"?' : 'Please check spelling.'}`);
          reportedBlocks.add(blockKey);
        }
        rowError = true;
      }

      const cleanDise = String(row.current_institute_dise_code || "").replace(/[^0-9]/g, "").trim();
      if (!validDiseSet.has(cleanDise)) {
        logs.push(`Row ${rowNum}: Invalid DISE Code "${cleanDise}"`);
        rowError = true;
      }

      if (!rowError) {
        validRows.push({ row, blockId, cleanDise });
      }
    }

    if (logs.length > 0) {
      await client.query("ROLLBACK");
      return { success: false, logs };
    }

    const BATCH_SIZE = 5000;
    for (let i = 0; i < validRows.length; i += BATCH_SIZE) {
      const batch = validRows.slice(i, i + BATCH_SIZE);
      const values = [];
      const placeholders = batch.map((item, idx) => {
        const r = item.row;
        const offset = idx * 13;
        values.push(year, r.exam, district_id, state_id, item.blockId, item.cleanDise, r.students_sats_id, r.student_name, r.father_name, r.institute_name, r.contact_no1, r.contact_no2, generateStudentNameKey(r.student_name));
        return `($${offset + 1}, $${offset + 2}, $${offset + 3}, $${offset + 4}, $${offset + 5}, $${offset + 6}, $${offset + 7}, $${offset + 8}, $${offset + 9}, $${offset + 10}, $${offset + 11}, $${offset + 12}, $${offset + 13})`;
      }).join(",");

      await client.query(`INSERT INTO pp.stg_nmms_phase1_applications (nmms_year, exam, district, app_state, nmms_block, current_institute_dise_code, students_sats_id, student_name, father_name, institute_name, contact_no1, contact_no2, student_name_key) VALUES ${placeholders}`, values);
    }

    await client.query("COMMIT");
    return { success: true, logs: [`Successfully inserted ${validRows.length} records.`] };
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
};

exports.uploadPhase2Model = async ({ file, year, state_id, district_id }) => {
  const client = await pool.connect();
  const logs = [];
  const validRows = [];
  const reportedBlocks = new Set();

  try {
    const records = parse(file, { columns: true, skip_empty_lines: true });
    await client.query("BEGIN");

    const checkDuplicate = await client.query(`SELECT COUNT(*) FROM pp.stg_nmms_phase2_results WHERE district = $1 AND nmms_year = $2`, [district_id, year]);

    if (parseInt(checkDuplicate.rows[0].count) > 0) {
      await client.query("ROLLBACK");
      return { success: false, logs: [`Upload Rejected: Results for Year ${year} have already been uploaded for this district.`] };
    }

    const districtRes = await client.query(`SELECT juris_name FROM pp.jurisdiction WHERE juris_code=$1`, [district_id]);
    const selectedDistrictName = districtRes.rows[0]?.juris_name;

    const blockMap = await loadBlocks(client, district_id);
    const blockNames = [...blockMap.keys()];

    for (let i = 0; i < records.length; i++) {
      const row = records[i];
      const rowNum = i + 1;
      let rowError = false;

      const blockKey = normalizeText(row.nmms_block);
      const blockId = blockMap.get(blockKey);

      if (!blockId) {
        if (!reportedBlocks.has(blockKey)) {
          const suggestion = suggestValue(row.nmms_block, blockNames);
          logs.push(`Row ${rowNum}: Block "${row.nmms_block}" invalid for ${selectedDistrictName}.`);
          reportedBlocks.add(blockKey);
        }
        rowError = true;
      }

      if (!/^\d{8,12}$/.test(row.nmms_reg_number)) { rowError = true; }
      if (!isNameValid(row.student_name)) { rowError = true; }

      if (!rowError) validRows.push({ row, blockId });
    }

    if (logs.length > 0) {
      await client.query("ROLLBACK");
      return { success: false, logs };
    }

    let inserted = 0;
    const BATCH_SIZE = 5000;

    for (let i = 0; i < validRows.length; i += BATCH_SIZE) {
      const batch = validRows.slice(i, i + BATCH_SIZE);
      const values = [];
      const placeholders = [];

      batch.forEach((item, index) => {
        const r = item.row;
        const baseIndex = index * 8;
        placeholders.push(`($${baseIndex + 1}, $${baseIndex + 2}, $${baseIndex + 3}, $${baseIndex + 4}, $${baseIndex + 5}, $${baseIndex + 6}, $${baseIndex + 7},$${baseIndex + 8})`);
        values.push(year, district_id, item.blockId, r.nmms_reg_number, r.student_name, r.gmat_score, r.sat_score, generateStudentNameKey(r.student_name));
      });

      await client.query(`INSERT INTO pp.stg_nmms_phase2_results (nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score ,student_name_key) VALUES ${placeholders.join(",")}`, values);
      inserted += batch.length;
    }

    await client.query("COMMIT");
    return { success: true, logs: [`Successfully inserted ${inserted} results.`] };
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
};

exports.getMergePreviewModel = async (year, districtId) => {
  const { rows } = await pool.query(`
    SELECT
      a.id AS phase1_id, a.student_name, a.father_name, a.students_sats_id, 
      a.contact_no1, a.institute_name, a.nmms_block, j.juris_name AS block_name,
      r.result_stg_id, r.nmms_reg_number, r.gmat_score, r.sat_score, 
      r.student_name AS result_student_name
    FROM pp.stg_nmms_phase1_applications a
    LEFT JOIN pp.jurisdiction j ON a.nmms_block = j.juris_code
    LEFT JOIN pp.stg_nmms_phase2_results r
      ON a.student_name_key = r.student_name_key
      AND a.nmms_block = r.nmms_block
      AND a.district = r.district
      AND a.nmms_year = r.nmms_year
      AND r.match_status IS DISTINCT FROM 'MATCHED'
    WHERE a.nmms_year = $1 AND a.district = $2
    ORDER BY a.student_name ASC
  `, [year, districtId]);

  const studentMap = {};
  rows.forEach(row => {
    if (!studentMap[row.phase1_id]) {
      studentMap[row.phase1_id] = {
        phase1_id: row.phase1_id, student_name: row.student_name, father_name: row.father_name, students_sats_id: row.students_sats_id,
        contact_no1: row.contact_no1, institute_name: row.institute_name, nmms_block: row.nmms_block, block_name: row.block_name,
        candidates: []
      };
    }
    if (row.result_stg_id) {
      studentMap[row.phase1_id].candidates.push({
        result_stg_id: row.result_stg_id, nmms_reg_number: row.nmms_reg_number, student_name: row.result_student_name, gmat_score: row.gmat_score, sat_score: row.sat_score
      });
    }
  });

  const blockWise = {};
  let totalStudents = 0, mapped = 0, conflicts = 0;
  Object.values(studentMap).forEach(app => {
    totalStudents++;
    if (app.candidates.length === 1) mapped++;
    else if (app.candidates.length > 1) conflicts++;
    if (!blockWise[app.block_name]) blockWise[app.block_name] = [];
    blockWise[app.block_name].push(app);
  });
  return { summary: { total_students: totalStudents, mapped, conflicts }, blockWise };
};


// import db from "../config/db.js";
// import { parse } from "csv-parse/sync";
// import stringSimilarity from "string-similarity";

// // ================= VALIDATION HELPERS =================
// const isYearValid = (year) => /^\d{4}$/.test(year);
// const isDiseValid = (code) => /^\d{11}$/.test(code);
// const isSatsValid = (id) => /^\d{8,12}$/.test(id);
// const isNameValid = (name) => /^[A-Za-z\s.]+$/.test(name);
// const isPhoneValid = (phone) => /^[6-9]\d{9}$/.test(phone);
// const isScoreValid = (score) => !isNaN(score) || score === 'A';

// const normalizeText = (text) => text?.toUpperCase().replace(/[^A-Z]/g, "");

// const loadBlocks = async (client, districtId) => {
//   const { rows } = await client.query(
//     `SELECT juris_code, juris_name FROM pp.jurisdiction WHERE parent_juris = $1`,
//     [districtId]
//   );
//   const blockMap = new Map();
//   rows.forEach(r => blockMap.set(normalizeText(r.juris_name), r.juris_code));
//   return blockMap;
// };

// const suggestValue = (input, options) => {
//   const key = normalizeText(input);
//   let best = null;
//   let score = 0;
//   for (const option of options) {
//     const optionKey = normalizeText(option);
//     let match = 0;
//     for (let i = 0; i < Math.min(optionKey.length, key.length); i++) {
//       if (optionKey[i] === key[i]) match++;
//     }
//     const ratio = match / Math.max(optionKey.length, key.length);
//     if (ratio > score) {
//       score = ratio;
//       best = option;
//     }
//   }
//   return score > 0.4 ? best : null;
// };

// const getSuggestion = async (client, input, parentId = null) => {
//   let query = `SELECT juris_name FROM pp.jurisdiction`;
//   let values = [];
//   if (parentId) {
//     query += ` WHERE parent_juris = $1`;
//     values.push(parentId);
//   }
//   const res = await client.query(query, values);
//   const validNames = res.rows.map(r => r.juris_name);
//   const { bestMatch } = stringSimilarity.findBestMatch(input, validNames);
//   return bestMatch.rating > 0.5 ? bestMatch.target : null;
// };


// // ================= EXPORTED MODELS =================

// export const getJurisdictionsModel = async (type, parentId) => {
//   let query = `SELECT DISTINCT juris_code, juris_name FROM pp.jurisdiction WHERE juris_type = $1`;
//   let params = [type];
//   if (parentId) {
//     query += ` AND parent_juris = $2`;
//     params.push(parentId);
//   }
//   query += ` ORDER BY juris_name ASC`;
//   const { rows } = await db.query(query, params);
//   return rows;
// };

// export const getApplicationsModel = async ({ year, district, search, page, limit }) => {
//   const offset = (page - 1) * limit;
//   let q = `SELECT a.*, d.juris_name as district_name, b.juris_name as nmms_block_name FROM pp.stg_nmms_phase1_applications a LEFT JOIN pp.jurisdiction d ON a.district = d.juris_code LEFT JOIN pp.jurisdiction b ON a.nmms_block = b.juris_code WHERE a.nmms_year = $1 AND a.district = $2`;
//   let params = [year, district];
//   if (search) { q += ` AND a.student_name ILIKE $3`; params.push(`%${search}%`); }
//   const { rows } = await db.query(q + ` LIMIT $${params.length + 1} OFFSET $${params.length + 2}`, [...params, limit, offset]);
//   const countRes = await db.query(`SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications WHERE nmms_year = $1 AND district = $2`, [year, district]);
//   return { rows, totalPages: Math.ceil(parseInt(countRes.rows[0].count) / limit) };
// };

// export const getResultsModel = async ({ year, district, search, page, limit }) => {
//   const offset = (page - 1) * limit;
//   let q = `SELECT r.*, d.juris_name as district_name, b.juris_name as nmms_block_name FROM pp.stg_nmms_phase2_results r LEFT JOIN pp.jurisdiction d ON r.district = d.juris_code LEFT JOIN pp.jurisdiction b ON r.nmms_block = b.juris_code WHERE r.nmms_year = $1 AND r.district = $2`;
//   let params = [year, district];
//   if (search) { q += ` AND r.student_name ILIKE $3`; params.push(`%${search}%`); }
//   const { rows } = await db.query(q + ` LIMIT $${params.length + 1} OFFSET $${params.length + 2}`, [...params, limit, offset]);
//   const countRes = await db.query(`SELECT COUNT(*) FROM pp.stg_nmms_phase2_results WHERE nmms_year = $1 AND district = $2`, [year, district]);
//   return { rows, totalPages: Math.ceil(parseInt(countRes.rows[0].count) / limit) };
// };

// export const resolveMatchModel = async (appId, resId, userId) => {
//   const client = await db.connect();
//   try {
//     await client.query("BEGIN");
//     const appRes = await client.query(`SELECT * FROM pp.stg_nmms_phase1_applications WHERE id = $1`, [appId]);
//     const resRes = await client.query(`SELECT * FROM pp.stg_nmms_phase2_results WHERE result_stg_id = $1`, [resId]);

//     if (appRes.rows.length === 0 || resRes.rows.length === 0) throw new Error("Records not found.");
//     const app = appRes.rows[0];
//     const res = resRes.rows[0];

//     await client.query(`
//       INSERT INTO pp.std_applicant_primary_info (
//         nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, contact_no1, current_institute_dise_code, created_by
//       ) VALUES ($1::numeric, $2::numeric, NULLIF(regexp_replace($3, '\\D', '', 'g'), '')::numeric, $4, $5, $6::numeric, $7::numeric, $8::numeric, $9::numeric, $10::numeric, $11, $12, $13::numeric)`,
//       [app.nmms_year, res.nmms_reg_number, app.students_sats_id, app.student_name, app.father_name, app.app_state, app.district, app.nmms_block, (res.gmat_score === 'AB' ? '0' : res.gmat_score), (res.sat_score === 'AB' ? '0' : res.sat_score), app.contact_no1, app.current_institute_dise_code, userId]
//     );

//     await client.query(`UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED' WHERE result_stg_id = $1`, [resId]);

//     // Domino Effect Check
//     const remainingApp = await client.query(`
//       SELECT * FROM pp.stg_nmms_phase1_applications WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE($1, '[^a-zA-Z0-9]', '', 'g')) AND nmms_block = $2`, [app.student_name, app.nmms_block]
//     );
//     const remainingRes = await client.query(`
//       SELECT * FROM pp.stg_nmms_phase2_results WHERE LOWER(REGEXP_REPLACE(student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE($1, '[^a-zA-Z0-9]', '', 'g')) AND nmms_block = $2 AND match_status != 'MATCHED'`, [app.student_name, app.nmms_block]
//     );

//     if (remainingApp.rows.length === 1 && remainingRes.rows.length === 1) {
//       const autoApp = remainingApp.rows[0];
//       const autoRes = remainingRes.rows[0];
//       await client.query(`
//         INSERT INTO pp.std_applicant_primary_info (
//           nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, contact_no1, current_institute_dise_code, created_by
//         ) VALUES ($1::numeric, $2::numeric, NULLIF(regexp_replace($3, '\\D', '', 'g'), '')::numeric, $4, $5, $6::numeric, $7::numeric, $8::numeric, $9::numeric, $10::numeric, $11, $12, $13::numeric)`,
//         [autoApp.nmms_year, autoRes.nmms_reg_number, autoApp.students_sats_id, autoApp.student_name, autoApp.father_name, autoApp.app_state, autoApp.district, autoApp.nmms_block, (autoRes.gmat_score === 'AB' ? '0' : autoRes.gmat_score), (autoRes.sat_score === 'AB' ? '0' : autoRes.sat_score), autoApp.contact_no1, autoApp.current_institute_dise_code, userId]
//       );
//       await client.query(`UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED' WHERE result_stg_id = $1`, [autoRes.result_stg_id]);
//     }

//     await client.query("COMMIT");
//     return { success: true };
//   } catch (e) {
//     await client.query("ROLLBACK");
//     console.error("Resolve Match Error:", e);
//     throw e;
//   } finally {
//     client.release();
//   }
// };

// export const moveMappedToStdModel = async (districtId, year, userId) => {
//   const client = await db.connect();
//   try {
//     await client.query("BEGIN");
//     await client.query(`
//       INSERT INTO pp.std_applicant_primary_info (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, contact_no1, current_institute_dise_code, created_by)
//       SELECT a.nmms_year::numeric, r.nmms_reg_number::numeric, NULLIF(regexp_replace(a.students_sats_id, '\\D', '', 'g'), '')::numeric, a.student_name, a.father_name, a.app_state::numeric, a.district::numeric, a.nmms_block::numeric, (CASE WHEN r.gmat_score = 'AB' OR r.gmat_score IS NULL THEN '0' ELSE r.gmat_score END)::numeric, (CASE WHEN r.sat_score = 'AB' OR r.sat_score IS NULL THEN '0' ELSE r.sat_score END)::numeric, a.contact_no1, a.current_institute_dise_code, $3::numeric
//       FROM pp.stg_nmms_phase1_applications a
//       JOIN pp.stg_nmms_phase2_results r ON LOWER(REGEXP_REPLACE(a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(r.student_name, '[^a-zA-Z0-9]', '', 'g')) AND a.nmms_block = r.nmms_block
//       WHERE a.district = $1 AND a.nmms_year = $2 AND r.match_status != 'MATCHED'
//       AND a.id IN (
//         SELECT sub_a.id FROM pp.stg_nmms_phase1_applications sub_a JOIN pp.stg_nmms_phase2_results sub_r ON LOWER(REGEXP_REPLACE(sub_a.student_name, '[^a-zA-Z0-9]', '', 'g')) = LOWER(REGEXP_REPLACE(sub_r.student_name, '[^a-zA-Z0-9]', '', 'g')) AND sub_a.nmms_block = sub_r.nmms_block WHERE sub_a.district = $1 GROUP BY sub_a.id HAVING COUNT(*) = 1
//       )
//       ON CONFLICT (nmms_reg_number) DO NOTHING`, [districtId, year, userId]);

//     await client.query(`UPDATE pp.stg_nmms_phase2_results r SET match_status = 'MATCHED' FROM pp.std_applicant_primary_info s WHERE r.nmms_reg_number::numeric = s.nmms_reg_number AND r.district = $1`, [districtId]);
//     await client.query("COMMIT");
//     return { success: true };
//   } catch (err) { await client.query("ROLLBACK"); throw err; }
//   finally { client.release(); }
// };

// export const commitToPrimaryModel = async (districtId, year) => {
//   const client = await db.connect();
//   try {
//     await client.query("BEGIN");
//     await client.query(`
//       INSERT INTO pp.applicant_primary_info (nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, created_by , current_institute_dise_code , contact_no1 , contact_no2)
//       SELECT nmms_year, nmms_reg_number, students_sats_id, student_name, father_name, app_state, district, nmms_block, gmat_score, sat_score, created_by ,current_institute_dise_code , contact_no1 , contact_no2
//       FROM pp.std_applicant_primary_info WHERE district = $1 AND nmms_year = $2
//       ON CONFLICT (nmms_reg_number) DO NOTHING`, [districtId, year]);
//     await client.query("COMMIT");
//     return { success: true };
//   } catch (e) { await client.query("ROLLBACK"); console.error("Commit Error:", e); throw e; }
//   finally { client.release(); }
// };

// export const getDraftStdDistrictsModel = async () => {
//   const { rows } = await db.query(`
//     SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year, COUNT(*) AS total_applicants, COALESCE(m.total_merged, 0) AS total_merged_applicants, COUNT(*) - COALESCE(m.total_merged, 0) AS remaining_applicants
//     FROM pp.stg_nmms_phase1_applications s LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_merged FROM pp.std_applicant_primary_info GROUP BY district, nmms_year) m ON s.district::numeric = m.district::numeric AND s.nmms_year::text = m.nmms_year::text
//     JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric GROUP BY j.juris_name, s.district, s.nmms_year, m.total_merged ORDER BY j.juris_name`);
//   return rows.map(d => ({ district_name: d.district_name, district_id: Number(d.district_id), year: Number(d.year), total_applicants: Number(d.total_applicants), total_merged_applicants: Number(d.total_merged_applicants), remaining_applicants: Number(d.remaining_applicants) }));
// };

// export const getStdDistrictStudentsModel = async (districtId, year) => {
//   const { rows } = await db.query(`SELECT ROW_NUMBER() OVER (ORDER BY s.student_name) AS sl_no, s.student_name, j1.juris_name AS district_name, j2.juris_name AS block_name, s.current_institute_dise_code, s.nmms_reg_number, s.gmat_score, s.sat_score FROM pp.std_applicant_primary_info s LEFT JOIN pp.jurisdiction j1 ON s.district = j1.juris_code LEFT JOIN pp.jurisdiction j2 ON s.nmms_block = j2.juris_code WHERE s.district = $1 AND s.nmms_year = $2 ORDER BY s.student_name`, [districtId, year]);
//   return rows;
// };

// export const getMergedDistrictsModel = async () => {
//   const { rows } = await db.query(`SELECT j.juris_name AS district_name, a.district AS district_id, a.nmms_year AS year, COUNT(a.applicant_id) AS student_count FROM pp.applicant_primary_info a JOIN pp.jurisdiction j ON a.district = j.juris_code GROUP BY j.juris_name, a.district, a.nmms_year ORDER BY j.juris_name`);
//   return rows;
// };

// export const deleteDistrictDataModel = async (district, type) => {
//   let table = type === "p1" ? "pp.stg_nmms_phase1_applications" : type === "p2" ? "pp.stg_nmms_phase2_results" : type === "merge" ? "pp.std_applicant_primary_info" : null;
//   if (!table) throw new Error("Invalid type for deletion");
//   const delRes = await db.query(`DELETE FROM ${table} WHERE district = $1`, [district]);
//   return delRes.rowCount;
// };

// export const commitStatusModel = async (year) => {
//   const { rows } = await db.query(`SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year, COUNT(*) AS total_applicants, COALESCE(c.total_committed, 0) AS total_committed, COALESCE(c.total_committed, 0) = COUNT(*) AS is_committed
//     FROM pp.stg_nmms_phase1_applications s JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_committed FROM pp.applicant_primary_info GROUP BY district, nmms_year) c ON s.district::numeric = c.district::numeric AND s.nmms_year::text = c.nmms_year::text
//     WHERE s.nmms_year = $1 GROUP BY j.juris_name, s.district, s.nmms_year, c.total_committed ORDER BY j.juris_name`, [year]);
//   return rows;
// };

// export const isMergedModel = async (year) => {
//   const { rows } = await db.query(`SELECT j.juris_name AS district_name, s.district AS district_id, s.nmms_year AS year, COUNT(*) AS total_applicants, COALESCE(m.total_merged, 0) AS total_merged_applicants, COUNT(*) - COALESCE(m.total_merged, 0) AS remaining_applicants, CASE WHEN COALESCE(m.total_merged, 0) = COUNT(*) THEN true ELSE false END AS ismerged
//     FROM pp.stg_nmms_phase1_applications s LEFT JOIN (SELECT district, nmms_year, COUNT(*) AS total_merged FROM pp.std_applicant_primary_info GROUP BY district, nmms_year) m ON s.district::numeric = m.district::numeric AND s.nmms_year::text = m.nmms_year::text
//     JOIN pp.jurisdiction j ON s.district::numeric = j.juris_code::numeric WHERE s.nmms_year = $1 GROUP BY j.juris_name, s.district, s.nmms_year, m.total_merged ORDER BY j.juris_name`, [year]);
//   return rows.map(d => ({ district_name: d.district_name, district_id: Number(d.district_id), year: Number(d.year), total_applicants: Number(d.total_applicants), total_merged_applicants: Number(d.total_merged_applicants), remaining_applicants: Number(d.remaining_applicants), ismerged: d.ismerged }));
// };

// export const getDistrictMergedDataModel = async (districtId) => {
//   const { rows } = await db.query(`SELECT s.student_name, s.father_name, s.nmms_reg_number, s.students_sats_id, d.juris_name AS district_name, b.juris_name AS block_name, s.gmat_score, s.sat_score, s.contact_no1 FROM pp.std_applicant_primary_info s LEFT JOIN pp.jurisdiction d ON s.district = d.juris_code LEFT JOIN pp.jurisdiction b ON s.nmms_block = b.juris_code WHERE s.district = $1 ORDER BY s.student_name`, [districtId]);
//   return rows;
// };


// export const checkStdPrimaryModel = async (districtId, year) => {

//   const { rows } = await db.query(
//     `SELECT 1
//      FROM pp.std_applicant_primary_info
//      WHERE district = $1
//      AND nmms_year = $2
//      LIMIT 1`,
//     [districtId, year]
//   );

//   return rows.length > 0;
// };


// export const checkApplicantPrimaryModel = async (districtId, year) => {
//   const { rows } = await db.query(`SELECT 1 FROM pp.applicant_primary_info WHERE district=$1 AND nmms_year=$2 LIMIT 1`, [districtId, year]);
//   return rows.length > 0;
// };

// const generateStudentNameKey = (name) =>
//   (name || "").toLowerCase().replace(/[^a-z0-9]/g, "");

// export const uploadPhase1Model = async ({ file, year, state_id, district_id }) => {
//   const client = await db.connect();
//   const logs = [];
//   const validRows = [];
//   const reportedBlocks = new Set();
//   const reportedDistricts = new Set();
//   const reportedStates = new Set();

//   try {
//     // 1. Parse with BOM and whitespace handling for headers
//     const records = parse(file, {
//       columns: header => header.map(h => h.trim().replace(/^\uFEFF/, '')),
//       skip_empty_lines: true
//     });

//     await client.query("BEGIN");

//     const checkDuplicate = await client.query(
//       `SELECT COUNT(*) FROM pp.stg_nmms_phase1_applications 
//    WHERE district = $1 AND nmms_year = $2`,
//       [district_id, String(year)]
//     );

//     if (parseInt(checkDuplicate.rows[0].count) > 0) {
//       await client.query("ROLLBACK");
//       return {
//         success: false,
//         logs: [`Upload Rejected: Data for Year ${year} already uploaded for this district.`]
//       };
//     }

//     // 2. Fetch Metadata for Validation
//     const stateRes = await client.query(`SELECT juris_name FROM pp.jurisdiction WHERE juris_code=$1`, [state_id]);
//     const districtRes = await client.query(`SELECT juris_name FROM pp.jurisdiction WHERE juris_code=$1`, [district_id]);
//     const selectedStateName = stateRes.rows[0]?.juris_name;
//     const selectedDistrictName = districtRes.rows[0]?.juris_name;

//     // Load blocks for the specific district to provide suggestions
//     const blockMap = await loadBlocks(client, district_id);
//     const blockNames = [...blockMap.keys()];

//     // 3. Pre-fetch valid DISE codes
//     const diseInFile = new Set();
//     records.forEach(r => {
//       const c = String(r.current_institute_dise_code || "").replace(/[^0-9]/g, "").trim();
//       if (c) diseInFile.add(c);
//     });
//     const diseRes = await client.query(`SELECT dise_code FROM pp.institute WHERE dise_code = ANY($1)`, [Array.from(diseInFile)]);
//     const validDiseSet = new Set(diseRes.rows.map(r => String(r.dise_code)));

//     // 4. Validation Loop
//     for (let i = 0; i < records.length; i++) {
//       const row = records[i];
//       const rowNum = i + 1;
//       let rowError = false;

//       // --- Year Check ---
//       const cleanRowYear = String(row.nmms_year || "").trim();
//       if (cleanRowYear !== String(year)) {
//         logs.push(`Row ${rowNum}: Year Mismatch (File has "${cleanRowYear || 'Empty'}", expected "${year}")`);
//         rowError = true;
//       }

//       // --- State Check ---
//       const inputState = String(row.app_state || "").trim();
//       if (normalizeText(inputState) !== normalizeText(selectedStateName)) {
//         if (!reportedStates.has(inputState)) {
//           logs.push(`Row ${rowNum}: State Mismatch (File: "${inputState}", Expected: "${selectedStateName}")`);
//           reportedStates.add(inputState);
//         }
//         rowError = true;
//       }

//       // --- District Check ---
//       const inputDist = String(row.district || "").replace(/\./g, '').trim();
//       if (normalizeText(inputDist) !== normalizeText(selectedDistrictName)) {
//         if (!reportedDistricts.has(inputDist)) {
//           logs.push(`Row ${rowNum}: District Mismatch (File: "${inputDist}", Expected: "${selectedDistrictName}")`);
//           reportedDistricts.add(inputDist);
//         }
//         rowError = true;
//       }

//       // --- Block Check (WITH SUGGESTIONS) ---
//       const rawBlock = String(row.nmms_block || "").trim();
//       const blockKey = normalizeText(rawBlock);
//       const blockId = blockMap.get(blockKey);

//       if (!blockId) {
//         if (!reportedBlocks.has(blockKey)) {
//           // RESTORED SUGGESTION LOGIC
//           const suggestion = suggestValue(rawBlock, blockNames);
//           logs.push(`Row ${rowNum}: Block "${rawBlock}" not found. ${suggestion ? 'Did you mean "' + suggestion + '"?' : 'Please check spelling.'}`);
//           reportedBlocks.add(blockKey);
//         }
//         rowError = true;
//       }

//       // --- DISE Code Check ---
//       const cleanDise = String(row.current_institute_dise_code || "").replace(/[^0-9]/g, "").trim();
//       if (!validDiseSet.has(cleanDise)) {
//         logs.push(`Row ${rowNum}: Invalid DISE Code "${cleanDise}"`);
//         rowError = true;
//       }

//       if (!rowError) {
//         validRows.push({ row, blockId, cleanDise });
//       }
//     }

//     if (logs.length > 0) {
//       await client.query("ROLLBACK");
//       return { success: false, logs };
//     }

//     // 5. Batch Insert
//     const BATCH_SIZE = 5000;
//     for (let i = 0; i < validRows.length; i += BATCH_SIZE) {
//       const batch = validRows.slice(i, i + BATCH_SIZE);
//       const values = [];
//       const placeholders = batch.map((item, idx) => {
//         const r = item.row;
//         const offset = idx * 12;
//         values.push(
//           year, r.exam, district_id, state_id, item.blockId,
//           item.cleanDise, r.students_sats_id, r.student_name,
//           r.father_name, r.institute_name, r.contact_no1, generateStudentNameKey(r.student_name)
//         );
//         return `($${offset + 1}, $${offset + 2}, $${offset + 3}, $${offset + 4}, $${offset + 5}, $${offset + 6}, $${offset + 7}, $${offset + 8}, $${offset + 9}, $${offset + 10}, $${offset + 11}, $${offset + 12})`;
//       }).join(",");

//       await client.query(
//         `INSERT INTO pp.stg_nmms_phase1_applications 
//          (nmms_year, exam, district, app_state, nmms_block, current_institute_dise_code, 
//           students_sats_id, student_name, father_name, institute_name, contact_no1,student_name_key) 
//          VALUES ${placeholders}`,
//         values
//       );
//     }

//     await client.query("COMMIT");
//     return { success: true, logs: [`Successfully inserted ${validRows.length} records.`] };

//   } catch (err) {
//     await client.query("ROLLBACK");
//     throw err;
//   } finally {
//     client.release();
//   }
// };



// export const uploadPhase2Model = async ({ file, year, state_id, district_id }) => {
//   const client = await db.connect();
//   const logs = [];
//   const validRows = [];
//   const reportedBlocks = new Set();

//   try {
//     const records = parse(file, { columns: true, skip_empty_lines: true });
//     await client.query("BEGIN");

//     const checkDuplicate = await client.query(
//       `SELECT COUNT(*) FROM pp.stg_nmms_phase2_results 
//        WHERE district = $1 AND nmms_year = $2`,
//       [district_id, year]
//     );

//     if (parseInt(checkDuplicate.rows[0].count) > 0) {
//       await client.query("ROLLBACK");
//       return {
//         success: false,
//         logs: [`Upload Rejected: Results for Year ${year} have already been uploaded for this district.`]
//       };
//     }

//     const districtRes = await client.query(`SELECT juris_name FROM pp.jurisdiction WHERE juris_code=$1`, [district_id]);
//     const selectedDistrictName = districtRes.rows[0]?.juris_name;

//     const blockMap = await loadBlocks(client, district_id);
//     const blockNames = [...blockMap.keys()];

//     for (let i = 0; i < records.length; i++) {
//       const row = records[i];
//       const rowNum = i + 1;
//       let rowError = false;

//       const blockKey = normalizeText(row.nmms_block);
//       const blockId = blockMap.get(blockKey);

//       if (!blockId) {
//         if (!reportedBlocks.has(blockKey)) {
//           const suggestion = suggestValue(row.nmms_block, blockNames);
//           logs.push(`Row ${rowNum}: Block "${row.nmms_block}" invalid for ${selectedDistrictName}.`);
//           reportedBlocks.add(blockKey);
//         }
//         rowError = true;
//       }

//       if (!/^\d{8,12}$/.test(row.nmms_reg_number)) { rowError = true; }
//       if (!isNameValid(row.student_name)) { rowError = true; }

//       if (!rowError) validRows.push({ row, blockId });
//     }

//     if (logs.length > 0) {
//       await client.query("ROLLBACK");
//       return { success: false, logs };
//     }

//     // ✅ BATCH INSERT START
//     let inserted = 0;
//     const BATCH_SIZE = 5000;

//     for (let i = 0; i < validRows.length; i += BATCH_SIZE) {
//       const batch = validRows.slice(i, i + BATCH_SIZE);

//       const values = [];
//       const placeholders = [];

//       batch.forEach((item, index) => {
//         const r = item.row;
//         const baseIndex = index * 8;

//         placeholders.push(
//           `($${baseIndex + 1}, $${baseIndex + 2}, $${baseIndex + 3}, 
//             $${baseIndex + 4}, $${baseIndex + 5}, $${baseIndex + 6}, $${baseIndex + 7},$${baseIndex + 8})`
//         );

//         values.push(
//           year,
//           district_id,
//           item.blockId,
//           r.nmms_reg_number,
//           r.student_name,
//           r.gmat_score,
//           r.sat_score,
//           generateStudentNameKey(r.student_name)
//         );
//       });

//       await client.query(
//         `INSERT INTO pp.stg_nmms_phase2_results
//         (nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score ,student_name_key)
//         VALUES ${placeholders.join(",")}`,
//         values
//       );

//       inserted += batch.length;
//     }

//     await client.query("COMMIT");
//     return { success: true, logs: [`Successfully inserted ${inserted} results.`] };

//   } catch (err) {
//     await client.query("ROLLBACK");
//     throw err;
//   } finally {
//     client.release();
//   }
// };

// // ================= OPTIMIZED MERGE PREVIEW =================
// export const getMergePreviewModel = async (year, districtId) => {
//   /**
//    * Optimized Join:
//    * By including nmms_year, district, and nmms_block in the JOIN ON clause,
//    * PostgreSQL can utilize the Composite Index we created.
//    */
//   const { rows } = await db.query(`
//     SELECT
//       a.id AS phase1_id, a.student_name, a.father_name, a.students_sats_id, 
//       a.contact_no1, a.institute_name, a.nmms_block, j.juris_name AS block_name,
//       r.result_stg_id, r.nmms_reg_number, r.gmat_score, r.sat_score, 
//       r.student_name AS result_student_name
//     FROM pp.stg_nmms_phase1_applications a
//     LEFT JOIN pp.jurisdiction j ON a.nmms_block = j.juris_code
//     LEFT JOIN pp.stg_nmms_phase2_results r
//       ON a.student_name_key = r.student_name_key
//       AND a.nmms_block = r.nmms_block
//       AND a.district = r.district
//       AND a.nmms_year = r.nmms_year
//       AND r.match_status IS DISTINCT FROM 'MATCHED'
//     WHERE a.nmms_year = $1 AND a.district = $2
//     ORDER BY a.student_name ASC
//   `, [year, districtId]);

//   const studentMap = {};
//   rows.forEach(row => {
//     if (!studentMap[row.phase1_id]) {
//       studentMap[row.phase1_id] = {
//         phase1_id: row.phase1_id, student_name: row.student_name, father_name: row.father_name, students_sats_id: row.students_sats_id,
//         contact_no1: row.contact_no1, institute_name: row.institute_name, nmms_block: row.nmms_block, block_name: row.block_name,
//         candidates: []
//       };
//     }
//     if (row.result_stg_id) {
//       studentMap[row.phase1_id].candidates.push({
//         result_stg_id: row.result_stg_id, nmms_reg_number: row.nmms_reg_number, student_name: row.result_student_name, gmat_score: row.gmat_score, sat_score: row.sat_score
//       });
//     }
//   });

//   const blockWise = {};
//   let totalStudents = 0, mapped = 0, conflicts = 0;
//   Object.values(studentMap).forEach(app => {
//     totalStudents++;
//     if (app.candidates.length === 1) mapped++;
//     else if (app.candidates.length > 1) conflicts++;
//     if (!blockWise[app.block_name]) blockWise[app.block_name] = [];
//     blockWise[app.block_name].push(app);
//   });
//   return { summary: { total_students: totalStudents, mapped, conflicts }, blockWise };
// };


