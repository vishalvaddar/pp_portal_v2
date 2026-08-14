

// const express = require("express");
// const router = express.Router();

// const authenticate = require("../middleware/authMiddleware");

// const {
//   getStudentProfile,
//   getInactiveHistoryController,
//   getMySchedule
// } = require("../controllers/coordinator/studentController");

// /* HOME */
// router.get("/", (req, res) => {
//   res.send("Student API Working");
// });

// /* PROFILE */
// router.get("/profile", authenticate, getStudentProfile);

// /* INACTIVE HISTORY */
// router.get(
//   "/:id/inactive-history",
//   authenticate,
//   getInactiveHistoryController
// );

// router.get("/timetable", authenticate, getMySchedule);

// module.exports = router;




const express = require("express");
const router = express.Router();

const authenticate = require("../middleware/authMiddleware");

const {
  getStudentProfile,
  getInactiveHistoryController,
  getMySchedule,

  getStudentSummary,
  getStudentSubjectPerformance,
  getStudentMonthlyAttendance,
  getStudentWeeklyAttendance,
  getStudentCustomAttendance

} = require("../controllers/coordinator/studentController");

/* HOME */
router.get("/", (req, res) => {
  res.send("Student API Working");
});

/* PROFILE */
router.get("/profile", authenticate, getStudentProfile);

/* TIMETABLE */
router.get("/timetable", authenticate, getMySchedule);

/* ===========================================================
   🔥 PERFORMANCE ROUTES
=========================================================== */

/* 🔥 IMPORTANT: ADD THIS (fix your error) */
router.get("/performance", authenticate, getStudentSubjectPerformance);

/* SUMMARY */
router.get("/summary", authenticate, getStudentSummary);

/* SUBJECTS */
router.get("/subjects", authenticate, getStudentSubjectPerformance);

/* MONTHLY */
router.get("/monthly", authenticate, getStudentMonthlyAttendance);

/* WEEKLY */
router.get("/weekly", authenticate, getStudentWeeklyAttendance);

/* CUSTOM */
router.get("/custom", authenticate, getStudentCustomAttendance);

/* ===========================================================
   ⚠️ ALWAYS KEEP DYNAMIC ROUTES LAST
=========================================================== */

/* INACTIVE HISTORY */
router.get("/:id/inactive-history", authenticate, getInactiveHistoryController);

module.exports = router;


