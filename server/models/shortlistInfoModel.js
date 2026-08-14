const pool = require("../config/db");

const shortlistInfoModel = {
  async getAllShortlistNames(year) {
    try {
      const { rows } = await pool.query(
        `SELECT shortlist_batch_name FROM pp.shortlist_batch WHERE shortlisted_year = $1`,
        [year]
      );
      return rows.map(row => row.shortlist_batch_name);
    } catch (error) {
      console.error("Error fetching all shortlist names:", error);
      throw error;
    }
  },

  async getNonFrozenShortlistNames(year) {
    try {
      const { rows } = await pool.query(
        `SELECT shortlist_batch_name, shortlist_batch_id FROM pp.shortlist_batch 
         WHERE shortlisted_year = $1 AND frozen_yn = 'N';`,
        [year]
      );
      return rows.map(row => ({ name: row.shortlist_batch_name, id: row.shortlist_batch_id }));
    } catch (error) {
      console.error("Error fetching non-frozen shortlist names:", error);
      throw error;
    }
  },

  async getShortlistInfo(shortlistName, year) {
    try {
      const { rows } = await pool.query(
        `SELECT shortlist_batch_id, description, criteria_id, shortlist_batch_name, frozen_yn
         FROM pp.shortlist_batch WHERE shortlist_batch_name = $1 AND shortlisted_year = $2;`,
        [shortlistName, year]
      );
      if (rows.length === 0) return null;

      const { shortlist_batch_id: id, description, criteria_id, shortlist_batch_name: name, frozen_yn } = rows[0];

      const criteriaRes = await pool.query(`SELECT criteria FROM pp.shortlist_criteria WHERE criteria_id = $1;`, [criteria_id]);
      const blocksRes = await pool.query(
        `SELECT j.juris_name FROM pp.jurisdiction j JOIN pp.shortlist_batch_jurisdiction sbj ON j.juris_code = sbj.juris_code WHERE sbj.shortlist_batch_id = $1;`,
        [id]
      );
      const totalStudentsRes = await pool.query(
        `SELECT COUNT(*) AS total_students FROM pp.applicant_primary_info WHERE nmms_year = $1 AND nmms_block IN (SELECT juris_code FROM pp.shortlist_batch_jurisdiction WHERE shortlist_batch_id = $2);`,
        [year, id]
      );
      const shortlistedRes = await pool.query(
        `SELECT COUNT(*) AS shortlisted_count FROM pp.applicant_shortlist_info asi WHERE asi.shortlisted_yn = 'Y' AND asi.shortlist_batch_id = $1;`,
        [id]
      );

      return {
        id, name, description,
        criteria: criteriaRes.rows[0]?.criteria || "N/A",
        blocks: blocksRes.rows.map(row => row.juris_name),
        totalStudents: parseInt(totalStudentsRes.rows[0]?.total_students || "0", 10),
        shortlistedCount: parseInt(shortlistedRes.rows[0]?.shortlisted_count || "0", 10),
        isFrozen: frozen_yn === 'Y' ? 'Yes' : 'No'
      };
    } catch (error) {
      console.error(`Error fetching detailed shortlist info:`, error);
      throw error;
    }
  },

  async getTotalApplicantCount(year) {
    try {
      const { rows } = await pool.query(`SELECT COUNT(*) AS total_applicants FROM pp.applicant_primary_info WHERE nmms_year = $1;`, [year]);
      return parseInt(rows[0]?.total_applicants || "0", 10);
    } catch (error) { throw error; }
  },

  // async getTotalShortlistedCount(year) {
  //   try {
  //     const { rows } = await pool.query(
  //       `SELECT COUNT(*) AS total_shortlisted FROM pp.applicant_shortlist_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id WHERE api.nmms_year = $1 AND asi.shortlisted_yn = 'Y';`,
  //       [year]
  //     );
  //     return parseInt(rows[0]?.total_shortlisted || "0", 10);
  //   } catch (error) { throw error; }
  // },
  async getTotalShortlistedCount(year) {
    try {
      const { rows } = await pool.query(
        `
      SELECT COUNT(*) AS total_shortlisted
      FROM pp.applicant_shortlist_info asi
      JOIN pp.applicant_primary_info api 
        ON asi.applicant_id = api.applicant_id
      JOIN pp.shortlist_batch sb 
        ON asi.shortlist_batch_id = sb.shortlist_batch_id
      WHERE api.nmms_year = $1
        AND asi.shortlisted_yn = 'Y'
        AND sb.frozen_yn = 'Y';
      `,
        [year]
      );
      return parseInt(rows[0]?.total_shortlisted || "0", 10);
    } catch (error) {
      throw error;
    }
  },

  async freezeShortlist(shortlistBatchId) {
    try {
      const { rowCount } = await pool.query(`UPDATE pp.shortlist_batch SET frozen_yn = 'Y' WHERE shortlist_batch_id = $1;`, [shortlistBatchId]);
      return rowCount > 0;
    } catch (error) { throw error; }
  },

  async deleteShortlist(shortlistBatchId, year) {
    try {
      await pool.query(`DELETE FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = $1;`, [shortlistBatchId]);
      await pool.query(`DELETE FROM pp.shortlist_batch_jurisdiction WHERE shortlist_batch_id = $1;`, [shortlistBatchId]);
      const { rowCount } = await pool.query(`DELETE FROM pp.shortlist_batch WHERE shortlist_batch_id = $1;`, [shortlistBatchId]);
      return rowCount > 0;
    } catch (error) { throw error; }
  },

  async getShortlistedApplicantsForShow(shortlistBatchId, year) {
    try {
      const { rows } = await pool.query(
        `SELECT api.applicant_id, api.nmms_reg_number, api.nmms_block, api.student_name, api.gmat_score, api.sat_score, api.medium, (api.gmat_score * 0.70 + api.sat_score * 0.30) AS weighted_score
         FROM pp.applicant_primary_info api WHERE api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlisted_yn = 'Y' AND shortlist_batch_id = $1) ORDER BY api.student_name ASC;`,
        [shortlistBatchId]
      );
      return rows;
    } catch (error) { throw error; }
  },

  async getShortlistedApplicantsForDownload(shortlistBatchId, year) {
    try {
      const { rows } = await pool.query(
        `SELECT api.nmms_reg_number AS "NMMS Registration No", api.student_name AS "Student Name", api.contact_no1 AS "Contact No 1", cur_inst.institute_name AS "Current School Name", api.medium As Medium, d.juris_name AS District, b.juris_name AS Block, gmat_score AS "GMAT Score", sat_score AS "SAT Score"
         FROM pp.applicant_primary_info api LEFT JOIN pp.institute cur_inst ON api.current_institute_dise_code = cur_inst.dise_code LEFT JOIN pp.jurisdiction d on api.district=d.juris_code Left join pp.jurisdiction b on api.nmms_block=b.juris_code
         WHERE api.applicant_id IN (SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlisted_yn = 'Y' AND shortlist_batch_id = $1) ORDER BY "Student Name" ASC;`,
        [shortlistBatchId]
      );
      return rows;
    } catch (error) { throw error; }
  },

  // async autoUpdateSingleMediumStudents(batchId) {
  //   try {
  //     await pool.query(
  //       `UPDATE pp.applicant_primary_info api
  //            SET medium = im.single_med, updated_at = CURRENT_TIMESTAMP
  //            FROM (
  //               -- Find every school that has EXACTLY ONE medium in the database
  //               SELECT dise_code, MAX(medium) as single_med
  //               FROM pp.institute_medium
  //               GROUP BY dise_code
  //               HAVING COUNT(DISTINCT medium) = 1
  //            ) im
  //            WHERE api.current_institute_dise_code = im.dise_code
  //              AND api.applicant_id IN (
  //                 SELECT applicant_id FROM pp.applicant_shortlist_info WHERE shortlist_batch_id = $1
  //              )
  //              AND (api.medium IS NULL OR api.medium = '');`,
  //       [batchId]
  //     );
  //   } catch (error) {
  //     console.error("AutoUpdate Error:", error);
  //     throw error;
  //   }
  // },

  async autoUpdateSingleMediumStudents(batchId) {
  try {
    await pool.query(
      `UPDATE pp.applicant_primary_info api
       SET medium = im.single_med,
           updated_at = CURRENT_TIMESTAMP
       FROM (
          SELECT dise_code, MAX(medium) as single_med
          FROM pp.institute_medium
          GROUP BY dise_code
          HAVING COUNT(DISTINCT medium) = 1
       ) im
       WHERE api.current_institute_dise_code = im.dise_code
         AND api.applicant_id IN (
            SELECT applicant_id 
            FROM pp.applicant_shortlist_info 
            WHERE shortlist_batch_id = $1
         )
         AND (api.medium IS NULL OR api.medium = '')`,
      [batchId]
    );

    // 🔥 ADD THIS BLOCK (IMPORTANT)
    await pool.query(
      `UPDATE pp.applicant_shortlist_info asi
       SET shortlisted_yn = 'N'
       FROM pp.applicant_primary_info api
       JOIN pp.institute i 
         ON TRIM(CAST(api.current_institute_dise_code AS TEXT)) 
            = TRIM(CAST(i.dise_code AS TEXT))
       WHERE asi.applicant_id = api.applicant_id
         AND asi.shortlist_batch_id = $1
         AND (
           
           -- ❌ ENGLISH rule
           (
             TRIM(UPPER(api.medium)) = 'ENGLISH'
             AND TRIM(UPPER(i.management_type)) <> 'GOVERNMENT'
           )

           -- ❌ KANNADA rule
           OR (
             TRIM(UPPER(api.medium)) = 'KANNADA'
             AND TRIM(UPPER(i.management_type)) 
                 NOT IN ('GOVERNMENT', 'PRIVATE AIDED')
           )

           -- ❌ MARATHI rule
           OR (
             TRIM(UPPER(api.medium)) = 'MARATHI'
             AND TRIM(UPPER(i.management_type)) 
                 NOT IN ('GOVERNMENT', 'PRIVATE AIDED')
           )
         )`
      ,
      [batchId]
    );

  } catch (error) {
    console.error("AutoUpdate Error:", error);
    throw error;
  }
},


  async getInvalidMediumStudents(shortlistBatchId, allowedMediums) {
    try {
      const { rows } = await pool.query(
        `SELECT 
                api.applicant_id, 
                api.student_name, 
                inst.institute_name, 
                inst.dise_code, 
                api.contact_no1, 
                api.contact_no2,
                api.medium as selected_medium,
                -- Subquery to get ALL mediums for the school (for the UI dropdown)
                (SELECT ARRAY_AGG(DISTINCT m.medium) 
                 FROM pp.institute_medium m 
                 WHERE m.dise_code = inst.dise_code) as supported_mediums
            FROM pp.applicant_primary_info api
            JOIN pp.applicant_shortlist_info asi ON api.applicant_id = asi.applicant_id
            JOIN pp.institute inst ON api.current_institute_dise_code = inst.dise_code
            WHERE asi.shortlist_batch_id = $1
              -- 🔥 THE ULTIMATE FILTER:
              -- We ONLY want to show a student in the conflict table if:
              AND (
                -- 1. They are in a school with MORE THAN ONE medium total (True Conflict)
                (SELECT COUNT(DISTINCT medium) FROM pp.institute_medium WHERE dise_code = inst.dise_code) > 1
                
                OR 
                
                -- 2. Their current medium (after auto-update) is NOT in your UI selection
                -- (This catches the "Marathi" or "Urdu" schools that Step 1 skipped)
                (api.medium IS NULL OR api.medium = '' OR api.medium != ANY($2))
              )
              -- 🛑 CRITICAL EXCLUSION:
              -- If a school has only 1 medium AND the student is already set to that medium,
              -- then they are NOT a conflict. REMOVE THEM from this result.
              AND NOT (
                (SELECT COUNT(DISTINCT medium) FROM pp.institute_medium WHERE dise_code = inst.dise_code) = 1
                AND api.medium = ANY($2)
              )
            GROUP BY api.applicant_id, api.student_name, inst.institute_name, inst.dise_code, api.contact_no1, api.contact_no2 , api.medium
            ORDER BY INST.INSTITUTE_NAME;`,
        [shortlistBatchId, allowedMediums]
      );
      return rows;
    } catch (error) {
      console.error("Error in getInvalidMediumStudents:", error);
      throw error;
    }
  },

  // async bulkUpdateMediumsAndStatus(updates, batchId) {
  //   const client = await pool.connect();
  //   try {
  //     await client.query('BEGIN');
  //     for (const student of updates) {
  //       await client.query(`UPDATE pp.applicant_primary_info SET medium = $1 WHERE applicant_id = $2`, [student.selected_medium, student.applicant_id]);
  //       await client.query(`UPDATE pp.applicant_shortlist_info SET shortlisted_yn = $1 WHERE applicant_id = $2 AND shortlist_batch_id = $3`, [student.status, student.applicant_id, batchId]);
  //     }
  //     await client.query('COMMIT');
  //     return true;
  //   } catch (error) {
  //     await client.query('ROLLBACK');
  //     throw error;
  //   } finally { client.release(); }
  // },


  // async bulkUpdateMediumsAndStatus(updates, batchId, allowedMediums) {
  //   const client = await pool.connect();
  //   try {
  //     await client.query('BEGIN');

  //     //Step 1: Apply manual selections from the User Interface
  //     for (const student of updates) {
  //       await client.query(
  //         `UPDATE pp.applicant_primary_info SET medium = $1 WHERE applicant_id = $2`,
  //         [student.selected_medium, student.applicant_id]
  //       );
  //       await client.query(
  //         `UPDATE pp.applicant_shortlist_info 
  //                SET shortlisted_yn = $1 
  //                WHERE applicant_id = $2 AND shortlist_batch_id = $3`,
  //         [student.status, student.applicant_id, batchId]
  //       );
  //     }

  //     // Step 2: 🔥 CORRECTED AUTOMATIC REJECTION
  //     // Check if the applicant_primary_info medium falls in selected mediums.
  //     // If it DOES NOT fall in the list, set shortlisted_yn to 'N'.
  //     if (allowedMediums && allowedMediums.length > 0) {
  //       await client.query(
  //         `UPDATE pp.applicant_shortlist_info asi
  //                SET shortlisted_yn = 'N'
  //                FROM pp.applicant_primary_info api
  //                WHERE asi.applicant_id = api.applicant_id
  //                  AND asi.shortlist_batch_id = $1
  //                  AND (
  //                    api.medium IS NULL 
  //                    OR api.medium = '' ***
  //                    OR NOT (TRIM(UPPER(api.medium)) = ANY(
  //                       SELECT TRIM(UPPER(m)) FROM unnest($2::text[]) m
  //                    ))
  //                  )`,
  //         [batchId, allowedMediums]
  //       );
  //     }

  //     // Step 3: Finalize Flags
  //     await client.query(
  //       `UPDATE pp.shortlist_batch 
  //            SET frozen_yn = 'Y',
  //                medium_filtered_yn = 'Y' 
  //            WHERE shortlist_batch_id = $1`,
  //       [batchId]
  //     );

  //     await client.query('COMMIT');
  //     return true;
  //   } catch (error) {
  //     await client.query('ROLLBACK');
  //     console.error("Bulk Commit Error:", error);
  //     throw error;
  //   } finally {
  //     client.release();
  //   }
  // },

  async bulkUpdateMediumsAndStatus(updates, batchId, allowedMediums) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // ================= STEP 1: APPLY USER CHANGES =================
    for (const student of updates) {
      await client.query(
        `UPDATE pp.applicant_primary_info 
         SET medium = $1 
         WHERE applicant_id = $2`,
        [student.selected_medium, student.applicant_id]
      );

      await client.query(
        `UPDATE pp.applicant_shortlist_info 
         SET shortlisted_yn = $1 
         WHERE applicant_id = $2 
           AND shortlist_batch_id = $3`,
        [student.status, student.applicant_id, batchId]
      );
    }

    // ================= STEP 2: 🔥 FINAL VALIDATION =================
    if (allowedMediums && allowedMediums.length > 0) {
      await client.query(
        `UPDATE pp.applicant_shortlist_info asi
         SET shortlisted_yn = 'N'
         FROM pp.applicant_primary_info api
         JOIN pp.institute i 
           ON TRIM(CAST(api.current_institute_dise_code AS TEXT)) 
              = TRIM(CAST(i.dise_code AS TEXT))
         WHERE asi.applicant_id = api.applicant_id
           AND asi.shortlist_batch_id = $1
           AND (
             
             -- ❌ Medium missing
             api.medium IS NULL
             OR TRIM(api.medium) = ''

             -- ❌ Not in allowed mediums (UI filter)
             OR NOT (TRIM(UPPER(api.medium)) = ANY(
                  SELECT TRIM(UPPER(m)) FROM unnest($2::text[]) m
             ))

             -- ❌ ENGLISH rule → ONLY GOVERNMENT
             OR (
               TRIM(UPPER(api.medium)) = 'ENGLISH'
               AND TRIM(UPPER(i.management_type)) <> 'GOVERNMENT'
             )

             -- ❌ KANNADA rule
             OR (
               TRIM(UPPER(api.medium)) = 'KANNADA'
               AND TRIM(UPPER(i.management_type)) 
                   NOT IN ('GOVERNMENT', 'PRIVATE AIDED')
             )

             -- ❌ MARATHI rule
             OR (
               TRIM(UPPER(api.medium)) = 'MARATHI'
               AND TRIM(UPPER(i.management_type)) 
                   NOT IN ('GOVERNMENT', 'PRIVATE AIDED')
             )
           )`,
        [batchId, allowedMediums]
      );
    }

    // ================= STEP 3: FINALIZE =================
    await client.query(
      `UPDATE pp.shortlist_batch 
       SET frozen_yn = 'Y',
           medium_filtered_yn = 'Y' 
       WHERE shortlist_batch_id = $1`,
      [batchId]
    );

    await client.query('COMMIT');
    return true;

  } catch (error) {
    await client.query('ROLLBACK');
    console.error("Bulk Commit Error:", error);
    throw error;
  } finally {
    client.release();
  }
},

  async resetMediumFiltering(shortlistBatchId) {
    try {
      // const result = await pool.query(
      //   `UPDATE pp.applicant_primary_info SET medium = NULL WHERE applicant_id IN (SELECT ASI.applicant_id FROM pp.applicant_shortlist_info AS ASI , PP.SHORTLIST_BATCH AS SB WHERE ASI.shortlist_batch_id = $1 AND ASI.SHORTLIST_BATCH_ID = SB.SHORTLIST_BATCH_ID AND MEDIUM_FILTERED_YN = 'N' ) `,
      //   [shortlistBatchId]
      // );
      const result = await pool.query(
        `UPDATE pp.applicant_primary_info 
   SET medium = NULL 
   WHERE applicant_id IN (
     SELECT ASI.applicant_id 
     FROM pp.applicant_shortlist_info AS ASI, PP.SHORTLIST_BATCH AS SB 
     WHERE ASI.shortlist_batch_id = $1 
       AND ASI.shortlist_batch_id = SB.shortlist_batch_id 
       AND SB.MEDIUM_FILTERED_YN = 'N'
   )`,
        [shortlistBatchId]
      );
      return result.rowCount > 0;
    } catch (error) { throw error; }
  }
};

module.exports = shortlistInfoModel;