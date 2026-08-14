const pool = require("../config/db.js");

const clean = (val) => (val === "" || val === undefined ? null : val);

const formatDate = (val) => {
  if (!val) return null;
  try {
    return new Date(val).toISOString().split("T")[0];
  } catch (e) {
    return null;
  }
};


async function getAllBrands() {
  const query = `SELECT brand_id, brand_name, model_name FROM pp.tab_brand ORDER BY brand_name, model_name`;
  const { rows } = await pool.query(query);
  return rows;
}

async function createBrand(data) {
  const query = `
    INSERT INTO pp.tab_brand (brand_name, model_name, created_by, updated_by)
    VALUES ($1, $2, $3, $3)
    ON CONFLICT (brand_name, model_name) 
    DO UPDATE SET 
      updated_at = CURRENT_TIMESTAMP,
      updated_by = $3
    RETURNING *
  `;

  const values = [
    clean(data.brand_name),
    clean(data.model_name),
    data.created_by, // This should be the user_id passed from frontend
  ];

  const { rows } = await pool.query(query, values);
  return rows[0];
}


async function createTab(data) {
  const query = `
    INSERT INTO pp.tab_inventory (
      serial_number, 
      imei, 
      inventory_id, 
      brand_id, 
      tab_purchase_date, 
      remarks, 
      created_by, 
      updated_by
    )
    VALUES ($1, $2, $3, $4, $5, $6, $7, $7)
    RETURNING tab_id;
  `;

  const values = [
    clean(data.serial_number),
    clean(data.imei), // Added this
    clean(data.inventory_id), // Added this
    data.brand_id,
    formatDate(data.tab_purchase_date),
    clean(data.remarks),
    data.created_by,
  ];

  const { rows } = await pool.query(query, values);
  return rows[0];
}



async function changeTabStatus(tabId, data) {
  const { status, remarks, assignment_type, student_id, official_user_id, user_id, transaction_date } = data;
  const activeTxDate = transaction_date || new Date().toISOString().split("T")[0];
  const client = await pool.connect();

  try {
    await client.query("BEGIN");

    // 1. If returning/marking, close ONLY active assignment for this tab
    if (["RETURNED", "DAMAGED", "LOST"].includes(status)) {
      await client.query(`UPDATE pp.student_issue SET return_date = $1 WHERE tab_id = $2 AND return_date IS NULL`, [activeTxDate, tabId]);
      await client.query(`UPDATE pp.official_issue SET return_date = $1 WHERE tab_id = $2 AND return_date IS NULL`, [activeTxDate, tabId]);
    }

    // 2. Handle Assignment
    if (status === "ASSIGNED") {
      if (assignment_type === "STUDENT" && student_id) {
        // Upsert logic: If assigning to SAME student, nullify their previous return_date
        await client.query(
          `INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
           VALUES ($1, $2, $3, NULL, $4)
           ON CONFLICT (tab_id, student_id) 
           DO UPDATE SET return_date = NULL, assignment_date = $3`,
          [tabId, student_id, activeTxDate, user_id]
        );
      } else if (assignment_type === "OFFICIAL" && official_user_id) {
        await client.query(
          `INSERT INTO pp.official_issue (tab_id, user_id, assignment_date, return_date, remark, created_by)
           VALUES ($1, $2, $3, NULL, $4, $5)
           ON CONFLICT (tab_id, user_id) 
           DO UPDATE SET return_date = NULL, assignment_date = $3`,
          [tabId, official_user_id, activeTxDate, remarks, user_id]
        );
      }
    }

    // 3. Update Inventory
    await client.query(
      `UPDATE pp.tab_inventory SET status = $1, remarks = COALESCE($2, remarks), updated_at = CURRENT_TIMESTAMP WHERE tab_id = $3`,
      [status, remarks, tabId]
    );

    await client.query("COMMIT");
    return { success: true };
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
}

// --- UPDATED: Staff selection can also be filtered if needed ---
async function getAllUsers() {
  // Logic: Only show staff who DO NOT currently hold a tablet
  const query = `
    SELECT user_id, user_name 
    FROM pp."user" u
    WHERE locked_yn = 'N' 
    AND NOT EXISTS (
        SELECT 1 FROM pp.official_issue oi 
        WHERE oi.user_id = u.user_id AND oi.return_date IS NULL
    )
    ORDER BY user_name ASC`;
  const { rows } = await pool.query(query);
  return rows;
}

async function deleteTab(tabId) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");

    // 1. Delete from both new issue tables first
    await client.query(`DELETE FROM pp.student_issue WHERE tab_id = $1`, [
      tabId,
    ]);
    await client.query(`DELETE FROM pp.official_issue WHERE tab_id = $1`, [
      tabId,
    ]);

    // 2. Delete the actual tablet
    const { rows } = await client.query(
      `DELETE FROM pp.tab_inventory WHERE tab_id = $1 RETURNING tab_id`,
      [tabId],
    );

    await client.query("COMMIT");
    return rows[0];
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
}

async function getTabById(tabId) {
  const { rows } = await pool.query(
    `SELECT * FROM pp.tab_inventory WHERE tab_id = $1`,
    [tabId],
  );
  return rows[0] || null;
}

async function getAllTabs() {
  const query = `
    WITH latest_student_assignment AS (
      SELECT 
        si.tab_id, si.student_id, si.assignment_date, si.return_date, sm.student_name, sm.enr_id, b.batch_name, c.cohort_name,
        ROW_NUMBER() OVER(PARTITION BY si.tab_id ORDER BY si.assignment_date DESC, si.created_at DESC) as rn
      FROM pp.student_issue si
      JOIN pp.student_master sm ON si.student_id = sm.student_id
      LEFT JOIN pp.batch b ON sm.batch_id = b.batch_id
      LEFT JOIN pp.cohort c ON b.cohort_number = c.cohort_number
    ),
    latest_official_assignment AS (
      SELECT 
        oi.tab_id, oi.user_id, oi.assignment_date, oi.return_date, u.user_name as staff_name,
        ROW_NUMBER() OVER(PARTITION BY oi.tab_id ORDER BY oi.assignment_date DESC, oi.created_at DESC) as rn
      FROM pp.official_issue oi
      JOIN pp."user" u ON oi.user_id = u.user_id
    )
    SELECT 
      t.tab_id, t.serial_number, t.imei, t.inventory_id, tb.brand_name, tb.model_name AS model, 
      t.tab_purchase_date, t.status, t.remarks, t.updated_at,
      
      -- 🔥 FIX: Always grab latest historical details unless tablet is inside the office
      CASE 
        WHEN t.status = 'IN_OFFICE' THEN NULL
        ELSE COALESCE(sa.student_name, oa.staff_name)
      END AS assigned_to,

      CASE 
        WHEN t.status = 'IN_OFFICE' THEN NULL
        ELSE sa.enr_id
      END AS enr_id,

      CASE 
        WHEN t.status = 'IN_OFFICE' THEN NULL
        ELSE sa.student_name
      END AS student_name,

      CASE 
        WHEN t.status = 'IN_OFFICE' THEN NULL
        ELSE oa.staff_name
      END AS staff_name,

      CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.cohort_name END as cohort_name,
      CASE WHEN t.status = 'IN_OFFICE' THEN NULL ELSE sa.batch_name END as batch_name,

      -- 🔥 FIX: Tracks holder type categorization even for non-active status modes
      CASE 
        WHEN t.status = 'IN_OFFICE' THEN NULL
        WHEN sa.student_id IS NOT NULL AND (sa.assignment_date >= COALESCE(oa.assignment_date, '1970-01-01')) THEN 'STUDENT'
        WHEN oa.user_id IS NOT NULL THEN 'OFFICIAL'
        ELSE NULL 
      END AS assignment_category
    FROM pp.tab_inventory t
    LEFT JOIN pp.tab_brand tb ON t.brand_id = tb.brand_id
    LEFT JOIN latest_student_assignment sa ON t.tab_id = sa.tab_id AND sa.rn = 1
    LEFT JOIN latest_official_assignment oa ON t.tab_id = oa.tab_id AND oa.rn = 1
    ORDER BY t.created_at DESC;
  `;
  const { rows } = await pool.query(query);
  return rows;
}

async function getTabHistory(tabId) {
  const query = `
    SELECT 
      assignment_date, return_date, sm.student_name as name, sm.enr_id, 'Student' as category, NULL as staff_remark
    FROM pp.student_issue si
    JOIN pp.student_master sm ON si.student_id = sm.student_id
    WHERE si.tab_id = $1
    UNION ALL
    SELECT 
      assignment_date, return_date, u.user_name as name, NULL as enr_id, 'Staff' as category, remark as staff_remark
    FROM pp.official_issue oi
    JOIN pp."user" u ON oi.user_id = u.user_id
    WHERE oi.tab_id = $1
    ORDER BY assignment_date DESC`;
  const { rows } = await pool.query(query, [tabId]);
  return rows;
}

async function bulkCreateTabs(devices) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");

    // ✅ PASS 1: Pre-scan ALL rows for inventory_id, IMEI, enrolment ID and
    // single-holder-per-tab conflicts BEFORE making any changes.
    // This collects ALL errors at once instead of stopping at the first one.
    const allErrors = [];

    const STATUS_TYPO_MAP = {
      "ASIGNED": "ASSIGNED", "ASSIGEND": "ASSIGNED", "ASSIGED": "ASSIGNED",
      "RETUREND": "RETURNED", "RETRUNED": "RETURNED",
      "DAMGED": "DAMAGED", "DAMMAGED": "DAMAGED",
      "IN_OFICE": "IN_OFFICE", "INOFFICE": "IN_OFFICE",
    };

    // ✅ NEW: Seed a "who currently holds this tab" map straight from the
    // database. Without this, the pre-scan had no idea a tab was already
    // ASSIGNED to someone — so a row assigning it to a NEW student would
    // pass every check, and PASS 2's ON CONFLICT (tab_id, student_id) would
    // simply INSERT a second active row, leaving the SAME tab "ASSIGNED"
    // to two different students at once.
    const serialNumbers = [...new Set(
      devices
        .map((dev) => dev.serial_number?.toString().trim().toUpperCase())
        .filter(Boolean)
    )];

    // serial_number -> { enrId, studentId } if actively held, otherwise null
    const tabHolderMap = {};
    serialNumbers.forEach((sn) => { tabHolderMap[sn] = null; });

    if (serialNumbers.length > 0) {
      const holderRes = await client.query(
        `SELECT ti.serial_number, sm.enr_id, sm.student_id
         FROM pp.tab_inventory ti
         LEFT JOIN pp.student_issue si ON si.tab_id = ti.tab_id AND si.return_date IS NULL
         LEFT JOIN pp.student_master sm ON sm.student_id = si.student_id
         WHERE ti.serial_number = ANY($1)`,
        [serialNumbers]
      );
      holderRes.rows.forEach((row) => {
        if (row.enr_id) {
          tabHolderMap[row.serial_number] = { enrId: row.enr_id, studentId: row.student_id };
        }
      });
    }

    for (const dev of devices) {
      const serialNumber = dev.serial_number?.toString().trim().toUpperCase();
      if (!serialNumber) continue;

      let normalizedStatus = (dev.status || "IN_OFFICE").toUpperCase().trim().replace(/\s+/g, "_");
      normalizedStatus = STATUS_TYPO_MAP[normalizedStatus] || normalizedStatus;

      // Check inventory_id conflict against DB
      if (dev.inventory_id) {
        const existingTab = await client.query(
          `SELECT serial_number FROM pp.tab_inventory WHERE inventory_id = $1`,
          [dev.inventory_id]
        );
        if (existingTab.rows.length > 0 && existingTab.rows[0].serial_number !== serialNumber) {
          allErrors.push(
            `Row ${dev.rowNumber}: Inventory ID "${dev.inventory_id}" is already assigned to tablet ` +
            `"${existingTab.rows[0].serial_number}" in the database, but your file assigns it to "${serialNumber}". ` +
            `Either the Inventory ID or the Serial Number is wrong — please verify physically and correct your Excel file.`
          );
          continue; // Skip further checks for this row
        }
      }

      // Check IMEI conflict against DB (only for new tablets not already in DB)
      if (dev.imei) {
        const existingImei = await client.query(
          `SELECT serial_number FROM pp.tab_inventory WHERE imei = $1`,
          [dev.imei]
        );
        if (existingImei.rows.length > 0 && existingImei.rows[0].serial_number !== serialNumber) {
          allErrors.push(
            `Row ${dev.rowNumber}: IMEI "${dev.imei}" is already registered to tablet ` +
            `"${existingImei.rows[0].serial_number}" in the database, but your file assigns it to "${serialNumber}". ` +
            `Please check your Excel file for this IMEI.`
          );
          continue;
        }
      }

      // Check enrolment ID exists in DB
      const enrId = dev.enr_id?.toString().trim();
      if (enrId && enrId !== "") {
        const studentRes = await client.query(
          `SELECT student_id FROM pp.student_master WHERE enr_id = $1`,
          [enrId]
        );
        if (studentRes.rows.length === 0) {
          allErrors.push(
            `Row ${dev.rowNumber}: Enrolment ID "${enrId}" not found in the database. ` +
            `Please check the Enrolment ID is correct (Serial: ${serialNumber}).`
          );
          continue;
        }

        // ✅ NEW: Single-holder-per-tab check.
        // A tab can only be ASSIGNED to one student at a time. If this tab
        // is currently held (return_date IS NULL) by a DIFFERENT student
        // than the one in this row, block the upload instead of silently
        // creating a second active holder.
        if (normalizedStatus === "ASSIGNED") {
          const currentHolder = tabHolderMap[serialNumber];
          if (currentHolder && currentHolder.enrId !== enrId) {
            allErrors.push(
              `Row ${dev.rowNumber}: Tab "${serialNumber}" is currently ASSIGNED to Student ${currentHolder.enrId} ` +
              `and has not been returned, but this row assigns it to Student ${enrId}. ` +
              `A tablet can only be held by one student at a time — add a RETURNED row for Student ${currentHolder.enrId} ` +
              `(Tab: ${serialNumber}) before assigning it to Student ${enrId}.`
            );
            continue;
          }
          // Mark this student as the (provisional) holder for the rest of the pre-scan
          tabHolderMap[serialNumber] = { enrId, studentId: studentRes.rows[0].student_id };
        }
      }

      // A RETURNED / DAMAGED / LOST / IN_OFFICE row frees up the tab for the rest of the pre-scan
      if (["RETURNED", "DAMAGED", "LOST", "IN_OFFICE"].includes(normalizedStatus)) {
        tabHolderMap[serialNumber] = null;
      }
    }

    // ✅ If ANY errors found in pre-scan, rollback and return ALL errors at once
    if (allErrors.length > 0) {
      await client.query("ROLLBACK");
      // Return structured errors object instead of throwing
      return { success: false, errors: allErrors };
    }

    // ✅ PASS 2: All clear — now actually apply the changes
    for (const dev of devices) {
      const serialNumber = dev.serial_number?.toString().trim().toUpperCase();
      if (!serialNumber) continue;

      const STATUS_TYPO_MAP = {
        "ASIGNED": "ASSIGNED", "ASSIGEND": "ASSIGNED", "ASSIGED": "ASSIGNED",
        "RETUREND": "RETURNED", "RETRUNED": "RETURNED",
        "DAMGED": "DAMAGED", "DAMMAGED": "DAMAGED",
        "IN_OFICE": "IN_OFFICE", "INOFFICE": "IN_OFFICE",
      };
      let normalizedStatus = (dev.status || "IN_OFFICE").toUpperCase().trim().replace(/\s+/g, "_");
      normalizedStatus = STATUS_TYPO_MAP[normalizedStatus] || normalizedStatus;

      let tabRes = await client.query(
        `SELECT tab_id FROM pp.tab_inventory WHERE serial_number = $1`,
        [serialNumber]
      );
      let tabId;

      if (tabRes.rows.length === 0) {
        const brandRes = await client.query(
          `INSERT INTO pp.tab_brand (brand_name, model_name, created_by)
           VALUES ($1, $2, $3)
           ON CONFLICT (brand_name, model_name) DO UPDATE SET updated_at = CURRENT_TIMESTAMP
           RETURNING brand_id`,
          [dev.brand_name || "Unknown", dev.model_name || "Unknown", dev.created_by]
        );
        const insertRes = await client.query(
          `INSERT INTO pp.tab_inventory (serial_number, imei, inventory_id, brand_id, status, remarks, created_by)
           VALUES ($1, $2, $3, $4, $5, $6, $7)
           RETURNING tab_id`,
          [serialNumber, dev.imei, dev.inventory_id, brandRes.rows[0].brand_id, normalizedStatus, dev.remarks, dev.created_by]
        );
        tabId = insertRes.rows[0].tab_id;
      } else {
        tabId = tabRes.rows[0].tab_id;
        await client.query(
          `UPDATE pp.tab_inventory SET status = $1, remarks = $2, updated_at = CURRENT_TIMESTAMP WHERE tab_id = $3`,
          [normalizedStatus, dev.remarks, tabId]
        );
      }

      const enrId = dev.enr_id?.toString().trim();
      if (enrId && enrId !== "") {
        const studentRes = await client.query(
          `SELECT student_id FROM pp.student_master WHERE enr_id = $1`,
          [enrId]
        );
        const studentId = studentRes.rows[0].student_id;
        const assignedDate = dev.assigned_date || new Date().toISOString().split("T")[0];

        if (normalizedStatus === "ASSIGNED") {
          // ✅ NEW: Before creating/updating this student's active assignment,
          // close out any OTHER student's still-open assignment for the same
          // tab. The PASS 1 check above guarantees there is at most one such
          // student (and that it's a legitimate hand-over), so this simply
          // marks their record as returned (today) instead of leaving it
          // open and ending up with two active holders for one tab.
          await client.query(
            `UPDATE pp.student_issue
               SET return_date = CURRENT_DATE, updated_at = CURRENT_TIMESTAMP
             WHERE tab_id = $1 AND return_date IS NULL AND student_id != $2`,
            [tabId, studentId]
          );

          await client.query(
            `INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
             VALUES ($1, $2, $3, NULL, $4)
             ON CONFLICT (tab_id, student_id)
             DO UPDATE SET assignment_date = EXCLUDED.assignment_date, return_date = NULL, updated_at = CURRENT_TIMESTAMP`,
            [tabId, studentId, assignedDate, dev.created_by]
          );
        } else if (["RETURNED", "DAMAGED", "LOST"].includes(normalizedStatus)) {
          const returnDate = dev.return_date || new Date().toISOString().split("T")[0];
          await client.query(
            `INSERT INTO pp.student_issue (tab_id, student_id, assignment_date, return_date, created_by)
             VALUES ($1, $2, $3, $4, $5)
             ON CONFLICT (tab_id, student_id)
             DO UPDATE SET
               assignment_date = COALESCE(pp.student_issue.assignment_date, EXCLUDED.assignment_date),
               return_date = EXCLUDED.return_date,
               updated_at = CURRENT_TIMESTAMP`,
            [tabId, studentId, assignedDate, returnDate, dev.created_by]
          );
        }
      } else {
        if (["RETURNED", "DAMAGED", "LOST"].includes(normalizedStatus)) {
          const returnDate = dev.return_date || new Date().toISOString().split("T")[0];
          await client.query(
            `UPDATE pp.student_issue SET return_date = $1, updated_at = CURRENT_TIMESTAMP WHERE tab_id = $2 AND return_date IS NULL`,
            [returnDate, tabId]
          );
        }
      }
    }

    await client.query("COMMIT");
    return { success: true, count: devices.length };
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
}

async function getEligibleStudents() {
  const query = `
    SELECT s.student_id, s.applicant_id, s.student_name,s.enr_id
    FROM pp.student_master s
    WHERE s.active_yn = 'ACTIVE' 
    AND NOT EXISTS (
        SELECT 1 FROM pp.student_issue si
        WHERE si.student_id = s.student_id AND si.return_date IS NULL
    )
  `;
  const { rows } = await pool.query(query);
  return rows;
}
async function getTabStats() {
  const query = `
    SELECT 
      COUNT(*) as total,
      COUNT(*) FILTER (WHERE status = 'IN_OFFICE') as in_office,
      COUNT(*) FILTER (WHERE status = 'DAMAGED') as damaged,
      COUNT(*) FILTER (WHERE status = 'LOST') as lost,
      COUNT(*) FILTER (WHERE status = 'RETURNED') as returned_awaiting,
      -- Count currently active student assignments
      (SELECT COUNT(*) FROM pp.student_issue WHERE return_date IS NULL) as student_assigned,
      -- Count currently active official/staff assignments
      (SELECT COUNT(*) FROM pp.official_issue WHERE return_date IS NULL) as official_assigned
    FROM pp.tab_inventory;
  `;
  const { rows } = await pool.query(query);
  return rows[0];
}

async function getAllCohorts() {
  const query = `SELECT cohort_number, cohort_name FROM pp.cohort ORDER BY cohort_name ASC`;
  const { rows } = await pool.query(query);
  return rows;
}
async function getTabMovementReport(fromCohort, toCohort) {
  let query = `
    WITH sequential_issues AS (
      SELECT 
        si.tab_id, si.student_id, si.assignment_date, si.return_date, sm.student_name, c.cohort_name,
        LEAD(sm.student_name) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as next_holder,
        LEAD(c.cohort_name) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as next_cohort,
        LEAD(si.assignment_date) OVER (PARTITION BY si.tab_id ORDER BY si.assignment_date ASC) as transfer_date
      FROM pp.student_issue si
      JOIN pp.student_master sm ON si.student_id = sm.student_id
      JOIN pp.batch b ON sm.batch_id = b.batch_id
      JOIN pp.cohort c ON b.cohort_number = c.cohort_number
    )
    SELECT 
      t.serial_number, t.inventory_id, tb.brand_name, tb.model_name as model,
      si.student_name AS previous_holder, si.cohort_name AS from_cohort,
      si.next_holder AS new_holder, si.next_cohort AS to_cohort, si.transfer_date AS moved_at
    FROM sequential_issues si
    JOIN pp.tab_inventory t ON si.tab_id = t.tab_id
    JOIN pp.tab_brand tb ON t.brand_id = tb.brand_id
    WHERE si.next_cohort IS NOT NULL
  `;

  const values = [];
  let paramIndex = 1;

  if (fromCohort && fromCohort !== "ALL") {
    query += ` AND si.cohort_name = $${paramIndex}`;
    values.push(fromCohort);
    paramIndex++;
  }
  if (toCohort && toCohort !== "ALL") {
    query += ` AND si.next_cohort = $${paramIndex}`;
    values.push(toCohort);
    paramIndex++;
  }

  query += ` ORDER BY si.transfer_date DESC;`;
  const { rows } = await pool.query(query, values);
  return rows;
}

module.exports = {
  getAllBrands,
  createBrand,
  createTab,
  changeTabStatus,
  deleteTab,
  getAllTabs,
  getTabById,
  getTabHistory,
  getEligibleStudents,
  bulkCreateTabs,
  getAllUsers,
  getTabStats,
  getAllCohorts,
  getTabMovementReport,
};






