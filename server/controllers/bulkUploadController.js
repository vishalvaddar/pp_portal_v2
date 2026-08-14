const fs = require("fs").promises;
const fsSync = require("fs");
const path = require("path");
const {
  parseCSV,
  parseExcel,
  validateAndSanitizeRow,
  insertApplicants,
} = require("../models/bulkuploadModel");

/* =====================================================
   LOG FILE GENERATOR
===================================================== */
const generateLogFile = async (logData) => {
  const name = `upload_log_${Date.now()}.txt`;
  const dir = path.join(__dirname, "../logs");
  await fs.mkdir(dir, { recursive: true });

  let content = `File Upload Summary\n`;
  content += `============================\n`;
  content += `File: ${logData.fileName}\n`;
  content += `Status: ${logData.status}\n\n`;

  if (logData.validationErrors.length) {
    content += `Validation Errors:\n`;
    logData.validationErrors.forEach((e) => {
      content += `Row ${e.row}: ${e.message}\n`;
    });
  }

  if (logData.databaseErrors.length) {
    content += `\nDatabase Errors:\n`;
    logData.databaseErrors.forEach((e) => {
      content += `• ${e.message}\n`;
    });
  }

  await fs.writeFile(path.join(dir, name), content);
  return name;
};

/* =====================================================
   MAIN CONTROLLER (ATOMIC UPLOAD)
===================================================== */
const uploadFile = async (req, res) => {
  if (!req.file) {
    return res.status(400).json({
      message:
        'No file received. Ensure multipart/form-data and field name is "file".',
    });
  }

  const filePath = req.file.path;
  const ext = path.extname(req.file.originalname).toLowerCase();

  const logData = {
    fileName: req.file.originalname,
    validationErrors: [],
    databaseErrors: [],
    status: "processing",
  };

  try {
    /* =========================
       PARSE FILE
    ========================= */
    const parsed =
      ext === ".csv"
        ? await parseCSV(filePath)
        : await parseExcel(filePath);

    /* =========================
       VALIDATE & SANITIZE
    ========================= */
    const results = parsed.data.map((row, index) =>
      validateAndSanitizeRow(row, index)
    );

    const validRows = [];
    results.forEach((r) => {
      if (r.errors.length) logData.validationErrors.push(...r.errors);
      else validRows.push(r.row);
    });

    /* =========================
       IF VALIDATION FAILS → STOP
    ========================= */
    if (logData.validationErrors.length > 0) {
      logData.status = "failed";
      const logFile = await generateLogFile(logData);

      return res.status(400).json({
        totalRecords: parsed.data.length,
        insertedRecords: 0,
        validationErrors: logData.validationErrors.length,
        dbErrors: 0,
        status: logData.status,
        logFile,
      });
    }

    /* =========================
       INSERT (ATOMIC – COPY)
    ========================= */
    const inserted = await insertApplicants(
      validRows,
      logData.databaseErrors
    );

    /* =========================
       STATUS (ALL OR NOTHING)
    ========================= */
    if (logData.databaseErrors.length > 0 || inserted.length === 0) {
      logData.status = "failed";
    } else {
      logData.status = "success";
    }

    const logFile = await generateLogFile(logData);

    return res.status(logData.status === "success" ? 200 : 500).json({
      totalRecords: parsed.data.length,
      insertedRecords: inserted.length,
      validationErrors: logData.validationErrors.length,
      dbErrors: logData.databaseErrors.length,
      status: logData.status,
      logFile,
    });
  } catch (err) {
    logData.status = "failed";
    logData.databaseErrors.push({
      message: `CRITICAL ERROR: ${err.message}`,
    });

    const logFile = await generateLogFile(logData);

    return res.status(500).json({
      message: "Bulk upload failed",
      status: "failed",
      logFile,
    });
  } finally {
    /* =========================
       CLEANUP TEMP FILE
    ========================= */
    try {
      if (fsSync.existsSync(filePath)) {
        await fs.unlink(filePath);
      }
    } catch (e) {
      console.error("Temp file cleanup failed:", e.message);
    }
  }
};

module.exports = { uploadFile };


