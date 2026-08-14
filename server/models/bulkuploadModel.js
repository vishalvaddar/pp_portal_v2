const fs = require("fs");
const path = require("path");
const Papa = require("papaparse");
const xlsx = require("xlsx");
const moment = require("moment");
const pool = require("../config/db");
const multer = require("multer");

/* ===================== UPLOAD CONFIG ===================== */

const uploadDir = path.join(__dirname, "..", "uploads", "temp");
fs.mkdirSync(uploadDir, { recursive: true });

const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, uploadDir),
  filename: (req, file, cb) => {
    const safeName = file.originalname.replace(/[^a-zA-Z0-9.\-_]/g, "_");
    cb(null, `${Date.now()}-${safeName}`);
  },
});

const upload = multer({ storage });
const multerSingle = upload.single("file");

const handleUploadErrors = (err, req, res, next) => {
  if (!err) return next();
  return res.status(400).json({ message: err.message });
};

/* ===================== VALIDATION ===================== */

const validateField = (field, value, rowIndex) => {
  const errors = [];
  const required = [
    "nmms_year",
    "nmms_reg_number",
    "student_name",
    "father_name",
    "gmat_score",
    "sat_score",
  ];

  if (
    required.includes(field) &&
    (!value || value.toString().trim() === "")
  ) {
    errors.push("This field is required.");
  }

  return errors.length
    ? { row: rowIndex + 1, field, message: errors.join(", ") }
    : null;
};

const sanitizeValue = (v, type = "text") => {
  if (v === null || v === undefined || v === "") return null;
  if (type === "numeric") return isNaN(v) ? null : Number(v);
  if (type === "gender") return v.toUpperCase();
  return v.toString().trim();
};

const sanitizeDate = (v) => {
  const m = moment(v, ["DD-MM-YYYY", "YYYY-MM-DD"], true);
  return m.isValid() ? m.format("YYYY-MM-DD") : null;
};

const validateAndSanitizeRow = (row, index) => {
  const errors = [];
  const out = { originalRowIndex: index };

  for (const [k, v] of Object.entries(row)) {
    const err = validateField(k, v, index);
    if (err) errors.push(err);

    if (k === "dob") out[k] = sanitizeDate(v);
    else if (["nmms_year", "gmat_score", "sat_score"].includes(k))
      out[k] = sanitizeValue(v, "numeric");
    else out[k] = sanitizeValue(v);
  }

  return { row: out, errors };
};

/* ===================== PARSERS ===================== */

const parseCSV = (filePath) =>
  new Promise((resolve, reject) => {
    Papa.parse(fs.createReadStream(filePath), {
      header: true,
      skipEmptyLines: true,
      transformHeader: (h) => h.toLowerCase().trim().replace(/ /g, "_"),
      complete: (r) => resolve({ data: r.data }),
      error: reject,
    });
  });

const parseExcel = (filePath) => {
  const wb = xlsx.readFile(filePath);
  const sheet = wb.Sheets[wb.SheetNames[0]];
  const data = xlsx.utils.sheet_to_json(sheet, { defval: "" });
  return {
    data: data.map((r) =>
      Object.fromEntries(
        Object.entries(r).map(([k, v]) => [
          k.toLowerCase().trim().replace(/ /g, "_"),
          v,
        ])
      )
    ),
  };
};

/* ===================== JURISDICTION LOOKUP (UNCHANGED QUERY) ===================== */

const getJurisdictionIdByName = async (
  client,
  jurisName,
  jurisType,
  parentId = null
) => {
  if (!jurisName) return null;
  const cleanName = jurisName.trim().replace(/[.,]+$/, "").toUpperCase();

  let query = `
    SELECT juris_code
    FROM pp.jurisdiction
    WHERE juris_name ILIKE $1
      AND juris_type = $2
  `;
  const values = [cleanName, jurisType];

  if (parentId) {
    query += ` AND parent_juris = $3`;
    values.push(parentId);
  }

  let result = await client.query(query, values);

  if (!result.rows.length) {
    result = await client.query(
      `SELECT juris_code FROM pp.jurisdiction WHERE UPPER(juris_name) = $1`,
      [cleanName]
    );
  }

  if (!result.rows.length)
    throw new Error(`Location not found: ${jurisType} ${cleanName}`);

  return result.rows[0].juris_code;
};

/* ===================== INSERT (BATCH + ROW-WISE ERRORS) ===================== */

const insertApplicants = async (validData, databaseErrors, createdById = 1) => {
  const client = await pool.connect();
  const BATCH_SIZE = 50000;
  const inserted = [];
  const cache = new Map();

  const cachedLookup = async (name, type, parent) => {
    const key = `${type}:${name}:${parent || 0}`;
    if (cache.has(key)) return cache.get(key);
    const id = await getJurisdictionIdByName(client, name, type, parent);
    cache.set(key, id);
    return id;
  };

  try {
    for (let i = 0; i < validData.length; i += BATCH_SIZE) {
      const batch = validData.slice(i, i + BATCH_SIZE);

      try {
        await client.query("BEGIN");

        for (const row of batch) {
          try {
            if (!row.nmms_reg_number) {
              databaseErrors.push({
                message: `Row ${row.originalRowIndex + 1}: NMMS Registration Number is missing`,
              });
              throw new Error("Missing NMMS Reg No");
            }

            const stateId = await cachedLookup(row.app_state, "STATE");
            const districtId = await cachedLookup(
              row.district,
              "EDUCATION DISTRICT",
              stateId
            );
            const blockId = await cachedLookup(
              row.nmms_block,
              "BLOCK",
              districtId
            );

            await client.query(
              `
              INSERT INTO pp.applicant_primary_info (
                nmms_year, nmms_reg_number, app_state, district, nmms_block,
                student_name, father_name, mother_name, gender, dob, aadhaar,
                gmat_score, sat_score, medium, home_address, family_income_total,
                contact_no1, contact_no2, current_institute_dise_code,
                previous_institute_dise_code, created_by, updated_by
              ) VALUES (
                $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,
                $11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22
              )
            `,
              [
                row.nmms_year,
                row.nmms_reg_number,
                stateId,
                districtId,
                blockId,
                row.student_name,
                row.father_name,
                row.mother_name,
                row.gender,
                row.dob,
                row.aadhaar,
                row.gmat_score,
                row.sat_score,
                row.medium,
                row.home_address,
                row.family_income_total,
                row.contact_no1,
                row.contact_no2,
                row.current_institute_dise_code,
                row.previous_institute_dise_code,
                createdById,
                createdById,
              ]
            );

            inserted.push(row.nmms_reg_number);
          } catch (rowErr) {
            databaseErrors.push({
              message: `Row ${row.originalRowIndex + 1} (Reg No: ${
                row.nmms_reg_number || "N/A"
              }) failed. ${rowErr.message}`,
            });
            throw rowErr; // rollback batch
          }
        }

        await client.query("COMMIT");
      } catch {
        await client.query("ROLLBACK");
      }
    }
  } finally {
    client.release();
  }

  return inserted;
};

/* ===================== EXPORTS ===================== */

module.exports = {
  parseCSV,
  parseExcel,
  validateAndSanitizeRow,
  insertApplicants,
  multerSingle,
  handleUploadErrors,
};



//modification




