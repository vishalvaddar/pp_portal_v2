const { Parser } = require('json2csv');
const {
  getJurisdictionsModel,
  uploadPhase1Model,
  uploadPhase2Model,
  getApplicationsModel,
  getResultsModel,
  getMergePreviewModel,
  resolveMatchModel,
  commitToPrimaryModel,
  getMergedDistrictsModel,
  moveMappedToStdModel,
  getStdDistrictStudentsModel,
  getDraftStdDistrictsModel,
  deleteDistrictDataModel,
  commitStatusModel,
  isMergedModel,
  getDistrictMergedDataModel,
  checkStdPrimaryModel,
  checkApplicantPrimaryModel
} = require("../models/mergeModel");

// -------------------- Helper for fuzzy suggestions --------------------
const getSuggestion = async (client, input, parentId = null) => {
  let query = `SELECT juris_name FROM pp.jurisdiction`;
  let values = [];

  if (parentId) {
    query += ` WHERE parent_juris = $1`;
    values.push(parentId);
  }

  const res = await client.query(query, values);

  let bestMatch = null;
  let bestScore = 0;

  const normalize = (str) => str.toUpperCase().replace(/\s+/g, "");
  const inputNorm = normalize(input);

  for (const row of res.rows) {
    const optionNorm = normalize(row.juris_name);
    let score = 0;
    for (let i = 0; i < Math.min(inputNorm.length, optionNorm.length); i++) {
      if (inputNorm[i] === optionNorm[i]) score++;
    }
    score = score / Math.max(inputNorm.length, optionNorm.length);
    if (score > bestScore) {
      bestScore = score;
      bestMatch = row.juris_name;
    }
  }

  return bestScore > 0.5 ? bestMatch : null;
};

// // -------------------- Delete District Data --------------------
// const deleteDistrictData = async (req, res) => {
//   const { district, phase, section } = req.body;
//   if (!district && section !== "merge") return res.status(400).json({ error: "District is required" });
//   if (!phase && section !== "merge") return res.status(400).json({ error: "Phase is required for upload sections" });

//   try {
//     let deletedCount = 0;

//     if (section === "merge") {
//       deletedCount = await deleteDistrictDataModel(district, "merge");
//       res.json({ message: `${deletedCount} records deleted from Primary Table for district ${district}` });
//     } else if (phase === "p1") {
//       deletedCount = await deleteDistrictDataModel(district, "p1");
//       res.json({ message: `${deletedCount} Phase 1 application records deleted for district ${district}` });
//     } else if (phase === "p2") {
//       deletedCount = await deleteDistrictDataModel(district, "p2");
//       res.json({ message: `${deletedCount} Phase 2 result records deleted for district ${district}` });
//     } else {
//       return res.status(400).json({ error: "Invalid phase or section" });
//     }
//   } catch (err) {
//     console.error("Delete district error:", err.message);
//     res.status(500).json({ error: err.message });
//   }
// };



const deleteDistrictData = async (req, res) => {
  const { district, phase, section, year } = req.body;

  if (!district) return res.status(400).json({ error: "District is required" });
  if (!year) return res.status(400).json({ error: "Year is required" });

  try {
    if (section === "merge") {
      const exists = await checkApplicantPrimaryModel(district, year);

      if (exists) {
        return res.status(400).json({
          error: "Deletion not allowed!! The currrent district merge process is already completed"
        });
      }
    } 
    else {
      const exists = await checkStdPrimaryModel(district, year);

      if (exists) {
        return res.status(400).json({
          error: "Deletion not allowed!! Data already merged. To continue with the deletion you need to delete the merged data"
        });
      }
    }

    let deletedCount = 0;

    if (section === "merge") {
      deletedCount = await deleteDistrictDataModel(district, "merge");

      return res.json({
        message: `${deletedCount} records deleted from Primary Table for district ${district}`
      });
    }

    if (phase === "p1") {
      deletedCount = await deleteDistrictDataModel(district, "p1");

      return res.json({
        message: `${deletedCount} Phase 1 application records deleted for district ${district}`
      });
    }

    if (phase === "p2") {
      deletedCount = await deleteDistrictDataModel(district, "p2");

      return res.json({
        message: `${deletedCount} Phase 2 result records deleted for district ${district}`
      });
    }

    return res.status(400).json({ error: "Invalid phase or section" });

  } catch (err) {
    console.error("Delete district error:", err.message);
    res.status(500).json({ error: err.message });
  }
};

// -------------------- Get Jurisdictions --------------------
const getJurisdictions = async (req, res) => {
  try {
    const { type, parent } = req.query;
    const data = await getJurisdictionsModel(type, parent);
    res.json(data);
  } catch (err) {
    console.error("Jurisdiction fetch error:", err);
    res.status(500).json({ error: "Failed to fetch jurisdictions" });
  }
};

// -------------------- Upload Phase 1 --------------------
const uploadPhase1 = async (req, res) => {
  try {
    const { year, state_id, district_id } = req.body;
    const file = req.file;
    if (!file) return res.status(400).json({ error: "No CSV file provided" });

    const result = await uploadPhase1Model({
      file: file.buffer,
      year,
      state_id,
      district_id,
      getSuggestion
    });

    if (!result.success) return res.status(400).json({ success: false, logs: result.logs });

    res.json({ success: true, logs: result.logs });
  } catch (err) {
    console.error("Upload P1 Error:", err);
    res.status(500).json({ logs: ["Critical Server Error during Application Upload"] });
  }
};

// -------------------- Upload Phase 2 --------------------
const uploadPhase2 = async (req, res) => {
  try {
    const { year, district_id } = req.body;
    const file = req.file;
    if (!file) return res.status(400).json({ error: "No CSV file provided" });

    const result = await uploadPhase2Model({
      file: file.buffer,
      year,
      district_id,
      getSuggestion
    });

    res.json(result);
  } catch (err) {
    console.error("Upload P2 Error:", err);
    res.status(500).json({ logs: ["Result upload failed"] });
  }
};

// -------------------- Get Applications --------------------
const getApplications = async (req, res) => {
  try {
    const { year, district, state, search, page = 1 } = req.query;
    const data = await getApplicationsModel({ year, district, state, search, page, limit: 50 });
    res.json(data);
  } catch (err) {
    res.status(500).json({ error: "Failed to fetch applications" });
  }
};

// -------------------- Get Results --------------------
const getResults = async (req, res) => {
  try {
    const { year, district, state, search, page = 1 } = req.query;
    const data = await getResultsModel({ year, district, state, search, page, limit: 50 });
    res.json(data);
  } catch (err) {
    res.status(500).json({ error: "Failed to fetch results" });
  }
};

// -------------------- Merge Preview --------------------
const getMergePreview = async (req, res) => {
  try {
    const { year, district } = req.body;
    const data = await getMergePreviewModel(year, district);
    res.json(data);
  } catch (err) {
    console.error("Merge preview error:", err);
    res.status(500).json({ error: "Merge preview failed" });
  }
};

// -------------------- Bulk Auto Map --------------------
const bulkAutoMap = async (req, res) => {
  try {
    const { year, district } = req.body;
    const user_id = req.user?.user_id || 1;
    await moveMappedToStdModel(district, year, user_id);
    res.json({ message: "Bulk mapping successful. Records copied to draft." });
  } catch (err) {
    console.error("Bulk auto-map error:", err);
    res.status(500).json({ error: "Failed to process bulk mapping" });
  }
};

// -------------------- Resolve Match --------------------
const resolveMatch = async (req, res) => {
  try {
    const { app_id, res_id } = req.body;
    const user_id = req.user?.user_id || 1;
    await resolveMatchModel(app_id, res_id, user_id);
    res.json({ message: "Mapped successfully" });
  } catch (err) {
    console.error("Mapping error:", err);
    res.status(500).json({ error: "Mapping failed" });
  }
};

// -------------------- Commit to Primary --------------------
const commitToPrimary = async (req, res) => {
  try {
    const { district, year } = req.body;
    await commitToPrimaryModel(district, year);
    res.json({ message: "Successfully committed to Primary Table." });
  } catch (err) {
    console.error("Final commit error:", err);
    res.status(500).json({ error: "Failed to finalize merge." });
  }
};

// -------------------- Draft Districts / Students --------------------
const getDraftStdDistricts = async (req, res) => {
  try {
    const rows = await getDraftStdDistrictsModel();
    res.json(rows);
  } catch (err) {
    console.error("Draft districts fetch error:", err);
    res.status(500).json({ error: "Failed to fetch draft districts" });
  }
};

const getDraftDistrictStudents = async (req, res) => {
  try {
    const { district, year } = req.query;
    const data = await getStdDistrictStudentsModel(district, year);
    res.json(data);
  } catch (err) {
    console.error("Draft students fetch error:", err);
    res.status(500).json({ error: "Failed to fetch details" });
  }
};

// -------------------- Get Merged Districts --------------------
const getMergedDistricts = async (req, res) => {
  try {
    const rows = await getMergedDistrictsModel();
    const data = rows.map(d => ({
      district_name: d.district_name,
      district_id: d.district_id,
      year: d.year,
      total_applicants: Number(d.total_applicants),
      total_merged_applicants: Number(d.total_merged_applicants),
      remaining_applicants: Number(d.remaining_applicants)
    }));
    res.json(data);
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: "Failed to fetch merged list" });
  }
};

// -------------------- Download CSV Template --------------------
const downloadTemplate = async (req, res) => {
  const { phase } = req.query;
  try {
    let fields = [];
    let csvData = [{}];

    if (phase === "p1") {
      fields = [
        "nmms_year","Exam","app_state","district","nmms_block",
        "current_institute_dise_code","students_sats_id","student_name",
        "father_name","institute_name","institute_type","category_name",
        "disability_status","contact_no1","contact_no2","date_of_application"
      ];
    } else if (phase === "p2") {
      fields = [
        "nmms_year","nmms_block","nmms_reg_number","student_name",
        "gmat_score","sat_score","total"
      ];
    } else {
      return res.status(400).json({ error: "Invalid phase" });
    }

    const parser = new Parser({ fields });
    const csv = parser.parse(csvData);

    res.header("Content-Type", "text/csv");
    res.attachment(`NMMS_${phase}_Template.csv`);
    res.send(csv);
  } catch (err) {
    console.error("Template download error:", err);
    res.status(500).json({ error: "Failed to generate template" });
  }
};

// -------------------- Commit Status --------------------
const commitStatusController = async (req, res) => {
  const { year } = req.query;
  if (!year) return res.status(400).json({ error: "Year is required" });

  try {
    const data = await commitStatusModel(year);
    res.json({ data });
  } catch (err) {
    console.error("Fetch merged status error:", err);
    res.status(500).json({ error: "Failed to fetch merged status" });
  }
};

const isMergedController = async (req, res) => {
  const { year } = req.query;
  if (!year) return res.status(400).json({ error: "Year is required" });

  try {
    const data = await isMergedModel(year);
    res.json({ data });
  } catch (err) {
    console.error("Fetch isMerged error:", err);
    res.status(500).json({ error: "Failed to fetch merged status" });
  }
};

// -------------------- Download District CSV --------------------
const downloadDistrictCSV = async (req, res) => {
  try {
    const { districtId } = req.params;
    const data = await getDistrictMergedDataModel(districtId);

    if (!data || data.length === 0) {
      return res.status(404).json({ message: "No data found for this district." });
    }

    const fields = Object.keys(data[0]);
    const parser = new Parser({ fields });
    const csv = parser.parse(data);

    res.header("Content-Type", "text/csv");
    res.attachment(`district_${districtId}_merged.csv`);
    res.send(csv);
  } catch (err) {
    console.error("CSV Download Error:", err);
    res.status(500).json({ message: "Error generating CSV" });
  }
};

// -------------------- Exports --------------------
module.exports = {
  getJurisdictions,
  uploadPhase1,
  uploadPhase2,
  getApplications,
  getResults,
  getMergePreview,
  bulkAutoMap,
  resolveMatch,
  commitToPrimary,
  getMergedDistricts,
  getDraftDistrictStudents,
  deleteDistrictData,
  getDraftStdDistricts,
  downloadTemplate,
  commitStatusController,
  isMergedController,
  downloadDistrictCSV
};