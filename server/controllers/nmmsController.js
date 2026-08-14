const pool = require("../config/db");
const csv = require("csv-parser");
const fs = require("fs");

// Helper: Standardize names for the VLOOKUP check
const clean = (val) => val ? val.toString().trim().toUpperCase() : null;

// 1. Fetch Districts
exports.getEducationDistricts = async (req, res) => {
    try {
        const result = await pool.query(
            "SELECT juris_code, juris_name FROM pp.jurisdiction WHERE juris_type = 'EDUCATION DISTRICT' ORDER BY juris_name"
        );
        res.json(result.rows);
    } catch (err) {
        res.status(500).json({ error: "Failed to fetch districts" });
    }
};

// 2. Upload Phase 1 (Applications) - Preserves all original mapping
exports.uploadPhase1 = async (req, res) => {
    const { year, district } = req.body;
    const filePath = req.file?.path;
    const client = await pool.connect();
    const report = { success: 0, failed: 0, errors: [] };
    try {
        const rows = [];
        await new Promise((resolve) => {
            fs.createReadStream(filePath)
                .pipe(csv({ mapHeaders: ({ header }) => header.trim() }))
                .on("data", (data) => rows.push(data))
                .on("end", resolve);
        });

        await client.query("BEGIN");
        for (let i = 0; i < rows.length; i++) {
            const row = rows[i];
            try {
                await client.query(
                    `INSERT INTO pp.stg_nmms_phase1_applications 
                    (nmms_year, exam, district, nmms_block, current_institute_dise_code, student_name, father_name, institute_name, contact_no1, medium)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
                    [year, row.exam, district, row.nmms_block, row.current_institute_dise_code, clean(row.student_name), clean(row.father_name), row.institute_name, row.contact_no1, row.medium]
                );
                report.success++;
            } catch (e) {
                report.failed++;
                report.errors.push({ row: i + 1, msg: "DB Error" });
            }
        }
        await client.query("COMMIT");
        res.json({ report });
    } catch (err) {
        await client.query("ROLLBACK");
        res.status(500).json({ error: err.message });
    } finally {
        client.release();
        if (fs.existsSync(filePath)) fs.unlinkSync(filePath);
    }
};

// 3. Upload Phase 2 (Results) - Preserves all original mapping
exports.uploadPhase2 = async (req, res) => {
    const { year, district } = req.body;
    const filePath = req.file?.path;
    const client = await pool.connect();
    const report = { success: 0, failed: 0, errors: [] };
    try {
        const rows = [];
        await new Promise((resolve) => {
            fs.createReadStream(filePath)
                .pipe(csv({ mapHeaders: ({ header }) => header.trim().toLowerCase() }))
                .on("data", (data) => rows.push(data))
                .on("end", resolve);
        });

        await client.query("BEGIN");
        for (let i = 0; i < rows.length; i++) {
            const row = rows[i];
            try {
                await client.query(
                    `INSERT INTO pp.stg_nmms_phase2_results 
                    (nmms_year, district, nmms_block, nmms_reg_number, student_name, gmat_score, sat_score)
                    VALUES ($1, $2, $3, $4, $5, $6, $7)`,
                    [year, district, row.nmms_block, row.nmms_reg_number, clean(row.student_name), row.gmat_score, row.sat_score]
                );
                report.success++;
            } catch (e) {
                report.failed++;
                report.errors.push({ row: i + 1, msg: "DB Error" });
            }
        }
        await client.query("COMMIT");
        res.json({ report });
    } catch (err) {
        await client.query("ROLLBACK");
        res.status(500).json({ error: err.message });
    } finally {
        client.release();
        if (fs.existsSync(filePath)) fs.unlinkSync(filePath);
    }
};

// 4. Generate Reconciliation Preview (THE NAME-BASED CONFLICT CHECKER)
exports.generateMergePreview = async (req, res) => {
    const { year, district } = req.body;
    try {
        const query = `
            SELECT 
                a.id as app_id, a.student_name, a.father_name, a.institute_name, a.nmms_block,
                (SELECT jsonb_agg(jsonb_build_object(
                    'res_id', r.result_stg_id, 'reg_no', r.nmms_reg_number,
                    'gmat', r.gmat_score, 'sat', r.sat_score, 'status', r.match_status
                )) FROM pp.stg_nmms_phase2_results r 
                   WHERE UPPER(TRIM(r.student_name)) = UPPER(TRIM(a.student_name))
                   AND UPPER(TRIM(r.nmms_block)) = UPPER(TRIM(a.nmms_block))
                   AND r.district = a.district AND r.nmms_year = a.nmms_year
                ) as score_candidates
            FROM pp.stg_nmms_phase1_applications a
            WHERE a.district = $1 AND a.nmms_year = $2
            ORDER BY a.nmms_block, a.student_name;
        `;

        const summaryQuery = `
            SELECT nmms_block, COUNT(*) as total_apps, 
            COUNT(*) FILTER (WHERE EXISTS (
                SELECT 1 FROM pp.stg_nmms_phase2_results r 
                WHERE (r.remarks = 'LINKED_APP_' || a.id::text)
                AND r.match_status = 'MATCHED'
            )) as matched_count 
            FROM pp.stg_nmms_phase1_applications a
            WHERE district = $1 AND nmms_year = $2 GROUP BY nmms_block;
        `;

        const reconcileRes = await pool.query(query, [district, year]);
        const summaryRes = await pool.query(summaryQuery, [district, year]);

        // Filter: Show only if NOT matched yet so user can resolve the "Pradeep" conflicts
        const blockWise = reconcileRes.rows.reduce((acc, row) => {
            const isMatched = row.score_candidates?.some(s => s.status === 'MATCHED');
            if (!isMatched) {
                const block = row.nmms_block || "Unknown";
                if (!acc[block]) acc[block] = [];
                acc[block].push(row);
            }
            return acc;
        }, {});

        res.json({ summary: summaryRes.rows, blockWise });
    } catch (err) {
        res.status(500).json({ error: "Preview failed" });
    }
};

// 5. Manual Resolve (Hondisuvudu Action)
exports.resolveConflict = async (req, res) => {
    const { result_stg_id, app_stg_id } = req.body;
    try {
        await pool.query(
            "UPDATE pp.stg_nmms_phase2_results SET match_status = 'MATCHED', remarks = $1 WHERE result_stg_id = $2",
            [`LINKED_APP_${app_stg_id}`, result_stg_id]
        );
        res.json({ success: true });
    } catch (err) {
        res.status(500).json({ error: "Resolution failed" });
    }
};

// 6. Wipe Staging
exports.wipeDistrictData = async (req, res) => {
    const { year, district } = req.body;
    await pool.query("DELETE FROM pp.stg_nmms_phase1_applications WHERE district = $1 AND nmms_year = $2", [district, year]);
    await pool.query("DELETE FROM pp.stg_nmms_phase2_results WHERE district = $1 AND nmms_year = $2", [district, year]);
    res.json({ success: true });
};

// 7. FINAL PUSH TO PRODUCTION
exports.commitToMain = async (req, res) => {
    const { year, district } = req.body;
    const client = await pool.connect();
    try {
        await client.query("BEGIN");
        
        const pushQuery = `
            INSERT INTO pp.std_applicant_primary_info (
                nmms_year, nmms_reg_number, district, nmms_block, 
                student_name, father_name, gmat_score, sat_score, 
                current_institute_dise_code, contact_no1, medium
            )
            SELECT 
                r.nmms_year::numeric, 
                r.nmms_reg_number::numeric, 
                r.district::numeric, 
                (SELECT juris_code FROM pp.jurisdiction WHERE UPPER(TRIM(juris_name)) = UPPER(TRIM(r.nmms_block)) AND juris_type = 'BLOCK' LIMIT 1),
                r.student_name, 
                a.father_name, 
                r.gmat_score::numeric, 
                r.sat_score::numeric, 
                a.current_institute_dise_code, 
                a.contact_no1,
                a.medium
            FROM pp.stg_nmms_phase2_results r
            JOIN pp.stg_nmms_phase1_applications a ON r.remarks = 'LINKED_APP_' || a.id::text
            WHERE r.district = $1 AND r.nmms_year = $2 AND r.match_status = 'MATCHED'
            ON CONFLICT (nmms_reg_number) DO UPDATE SET
                gmat_score = EXCLUDED.gmat_score,
                sat_score = EXCLUDED.sat_score;
        `;

        await client.query(pushQuery, [district, year]);
        await client.query("COMMIT");
        res.json({ success: true });
    } catch (err) {
        await client.query("ROLLBACK");
        res.status(500).json({ error: err.message });
    } finally {
        client.release();
    }
};