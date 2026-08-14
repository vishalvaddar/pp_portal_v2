// const express = require("express");
// const router = express.Router();
// const multer = require("multer");

// // Use memory storage to process CSV files as buffers
// const upload = multer({ storage: multer.memoryStorage() });

// // Import the controllers (Ensure the file name matches your project)
// const {
//   getJurisdictions,
//   uploadPhase1,
//   uploadPhase2,
//   getApplications,
//   getResults,
//   getMergePreview,
//   resolveMatch,
//   commitToPrimary,
//   getMergedDistricts ,
//   getDraftStdDistricts
// } = require("../controllers/mergeController");

// /* ===========================================================
//    1) JURISDICTION & DROPDOWNS
//    =========================================================== */
// router.get("/jurisdiction", getJurisdictions);
// router.get("/merged-status", getMergedDistricts);

// /* ===========================================================
//    2) BULK UPLOADS (Phase 1 & Phase 2)
//    =========================================================== */
// router.post("/upload-p1", upload.single("file"), uploadPhase1);
// router.post("/upload-p2", upload.single("file"), uploadPhase2);

// /* ===========================================================
//    3) VIEW DATA (Applications & Results)
//    =========================================================== */
// router.get("/applications", getApplications);
// router.get("/results", getResults);

// /* ===========================================================
//    4) MERGE & RECONCILIATION
//    =========================================================== */
// router.post("/preview-merge", getMergePreview);
// router.post("/resolve-lively", resolveMatch);
// router.post("/commit-to-primary", commitToPrimary);
// router.get("/draft-districts", getDraftStdDistricts);
// module.exports = router;


const express = require("express");
const router = express.Router();
const multer = require("multer");

// Use memory storage to process CSV files as buffers
const upload = multer({ storage: multer.memoryStorage() });

// Import the controllers
const {
  getJurisdictions,
  uploadPhase1,
  uploadPhase2,
  getApplications,
  getResults,
  getMergePreview,
  bulkAutoMap,      // Added for Manual Bulk Submit
  resolveMatch,
  commitToPrimary,
  getMergedDistricts,
  getDraftStdDistricts,
  getDraftDistrictStudents, // Added for the View Details Modal
  deleteDistrictData,
  downloadTemplate,
  commitStatusController,
  isMergedController,
  downloadDistrictCSV
} = require("../controllers/mergeController");

/* ===========================================================
   1) JURISDICTION & DROPDOWNS
   =========================================================== */
router.get("/jurisdiction", getJurisdictions);
router.get("/merged-status", getMergedDistricts);
router.get('/district/:districtId/download-csv', downloadDistrictCSV);

/* ===========================================================
   2) BULK UPLOADS (Phase 1 & Phase 2)
   =========================================================== */
router.post("/upload-p1", upload.single("file"), uploadPhase1);
router.post("/upload-p2", upload.single("file"), uploadPhase2);

/* ===========================================================
   3) VIEW DATA (Applications & Results)
   =========================================================== */
router.get("/applications", getApplications);
router.get("/results", getResults);

/* ===========================================================
   4) MERGE & RECONCILIATION
   =========================================================== */

// Step 1: Just looks up counts and conflicts
router.post("/preview-merge", getMergePreview);

// Step 2: Manually trigger the bulk move of the 10,742 students
router.post("/bulk-auto-map", bulkAutoMap); 

// Step 3: Resolve specific manual conflicts
router.post("/resolve-lively", resolveMatch);

// Step 4: Final freeze to Primary table
router.post("/commit-to-primary", commitToPrimary);

/* ===========================================================
   5) DRAFT AREA (Section 3 UI)
   =========================================================== */

// List districts currently in the draft area
router.get("/draft-districts", getDraftStdDistricts);

// Get student details for a specific district in the draft area (for Modal)
router.get("/draft-district-students", getDraftDistrictStudents);

router.delete("/delete-district-data", deleteDistrictData);

router.get("/download-template", downloadTemplate);

// Bulk uploads deletion

//

router.get("/commit-status", commitStatusController);
router.get("/merge-status", isMergedController);


module.exports = router;