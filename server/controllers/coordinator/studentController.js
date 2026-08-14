
// const {
//   getStudentsByCohortAndBatch,
//   getStudentsByCoordinator,
//   updateStudentModel,
//   markStudentInactiveModel,
//   getInactiveHistoryByStudentId,
//   getActiveStudentsForAttendance,
//   getStudentProfileByUserId,
//   getStudentTimetableModel
// } = require("../../models/coordinator/studentModel");

// /* ===========================================================
//    1) GET STUDENTS (Filtered for coordinator)
//    =========================================================== */
// const getStudentsController = async (req, res) => {
//   try {
//     const { cohortNumber, batchId, classroomId, isAttendance } = req.query; // ✅ Catch isAttendance flag
//     const user_id = req.user.user_id;

//     let students = [];

//     // 1️⃣ Logic for Attendance Page (Strictly ACTIVE)
//     if (isAttendance === 'true' && cohortNumber && batchId) {
//       students = await getActiveStudentsForAttendance(cohortNumber, batchId, classroomId);
//       return res.json(students);
//     }

//     // 2️⃣ Cohort + Batch selected (General Management - Shows All Statuses)
//     if (cohortNumber && batchId) {
//       students = await getStudentsByCohortAndBatch(cohortNumber, batchId);
//       return res.json(students);
//     }

//     // 3️⃣ Only cohort selected
//     if (cohortNumber && !batchId) {
//       const rows = await getStudentsByCoordinator(user_id);
//       students = rows.filter(s => String(s.cohort_number) === String(cohortNumber));
//       return res.json(students);
//     }

//     // 4️⃣ Default: coordinator's students
//     students = await getStudentsByCoordinator(user_id);
//     return res.json(students);

//   } catch (err) {
//     console.error("Error fetching students:", err);
//     return res.status(500).json({ error: "Failed to fetch students" });
//   }
// };

// /* ===========================================================
//    2) UPDATE STUDENT (EDIT + INACTIVE HANDLING)
//    =========================================================== */
// const updateStudentController = async (req, res) => {
//   try {
//     const student_id = req.params.id;
//     const payload = req.body;
//     const user_id = req.user.user_id;

//     /* -------------------------------------------------------
//        HANDLE INACTIVE FLOW
//     ------------------------------------------------------- */
//     if (
//       payload.active_yn &&
//       payload.active_yn.toUpperCase() === "INACTIVE" &&
//       payload.inactive_reason
//     ) {
//       await markStudentInactiveModel(
//         student_id,
//         payload.inactive_reason,
//         user_id
//       );

//       return res.json({
//         message: "Student marked inactive successfully"
//       });
//     }

//     /* -------------------------------------------------------
//        NORMAL UPDATE
//     ------------------------------------------------------- */
//     await updateStudentModel(student_id, payload);

//     return res.json({ message: "Student updated successfully" });

//   } catch (err) {
//     console.error("Update student failed:", err);
//     return res.status(500).json({ error: "Failed to update student" });
//   }
// };

// /* ===========================================================
//    3) MARK STUDENT INACTIVE (DIRECT CALL – OPTIONAL)
//    =========================================================== */
// const markInactiveController = async (req, res) => {
//   try {
//     const student_id = req.params.id;
//     const { inactive_reason } = req.body;
//     const user_id = req.user.user_id;

//     if (!inactive_reason || inactive_reason.trim() === "") {
//       return res.status(400).json({ error: "Inactive reason is required" });
//     }

//     await markStudentInactiveModel(student_id, inactive_reason, user_id);

//     return res.json({ message: "Student marked inactive successfully" });
//   } catch (err) {
//     console.error("Error marking inactive:", err);
//     return res.status(500).json({ error: "Failed to mark student inactive" });
//   }
// };

// /* ===========================================================
//    4) GET INACTIVE HISTORY FOR A STUDENT
//    =========================================================== */
// const getInactiveHistoryController = async (req, res) => {
//   try {
//     const student_id = req.params.id;

//     const rows = await getInactiveHistoryByStudentId(student_id);

//     return res.json(rows);
//   } catch (err) {
//     console.error("Error fetching inactive history:", err);
//     return res.status(500).json({ error: "Failed to fetch inactive history" });
//   }
// };


// const getStudentProfile = async (req, res) => {
//   try {
//     const user_id = req.user.user_id; // from auth middleware

//     const student = await getStudentProfileByUserId(user_id);

//     if (!student) {
//       return res.status(404).json({
//         message: "Student profile not found"
//       });
//     }

//     res.json(student);

//   } catch (error) {
//     console.error("Error fetching student profile:", error);
//     res.status(500).json({ error: "Server error" });
//   }
// };

// const getMySchedule = async (req, res) => {
//   try {
//     const userId = req.user.user_id; 

//     // 1. Fetch profile using the direct function name (no studentModel. prefix)
//     const profile = await getStudentProfileByUserId(userId);
    
//     if (!profile) {
//       return res.status(404).json({ message: "Student profile not found." });
//     }

//     if (!profile.batch_id) {
//       return res.status(400).json({ message: "No batch assigned to this student." });
//     }

//     // 2. Fetch the timetable using the direct function name
//     const timetable = await getStudentTimetableModel(profile.batch_id);
    
//     res.status(200).json(timetable); 
//   } catch (error) {
//     console.error("Error in getMySchedule:", error.message);
//     res.status(500).json({ message: "Internal Server Error" });
//   }
// };

// module.exports = {
//   getStudentsController,
//   updateStudentController,
//   markInactiveController,
//   getInactiveHistoryController,
//   getStudentProfile,
//   getMySchedule
// };



const {
  getStudentsByCohortAndBatch,
  getStudentsByCoordinator,
  updateStudentModel,
  markStudentInactiveModel,
  getInactiveHistoryByStudentId,
  getActiveStudentsForAttendance,
  getStudentProfileByUserId,
  getStudentTimetableModel,

  // 🔥 NEW PERFORMANCE MODEL FUNCTIONS
  getStudentSummaryModel,
  getStudentSubjectPerformanceModel,
  getStudentMonthlyAttendanceModel,
  getStudentWeeklyAttendanceModel,
  getStudentCustomAttendanceModel

} = require("../../models/coordinator/studentModel");

/* ===========================================================
   1) GET STUDENTS
=========================================================== */
const getStudentsController = async (req, res) => {
  try {
    const { cohortNumber, batchId, classroomId, isAttendance } = req.query;
    const user_id = req.user.user_id;

    let students = [];

    if (isAttendance === 'true' && cohortNumber && batchId) {
      students = await getActiveStudentsForAttendance(cohortNumber, batchId, classroomId);
      return res.json(students);
    }

    if (cohortNumber && batchId) {
      students = await getStudentsByCohortAndBatch(cohortNumber, batchId);
      return res.json(students);
    }

    if (cohortNumber && !batchId) {
      const rows = await getStudentsByCoordinator(user_id);
      students = rows.filter(s => String(s.cohort_number) === String(cohortNumber));
      return res.json(students);
    }

    students = await getStudentsByCoordinator(user_id);
    return res.json(students);

  } catch (err) {
    console.error("Error fetching students:", err);
    return res.status(500).json({ error: "Failed to fetch students" });
  }
};

/* ===========================================================
   2) UPDATE STUDENT
=========================================================== */
const updateStudentController = async (req, res) => {
  try {
    const student_id = req.params.id;
    const payload = req.body;
    const user_id = req.user.user_id;

    if (
      payload.active_yn &&
      payload.active_yn.toUpperCase() === "INACTIVE" &&
      payload.inactive_reason
    ) {
      await markStudentInactiveModel(student_id, payload.inactive_reason, user_id);

      return res.json({
        message: "Student marked inactive successfully"
      });
    }

    await updateStudentModel(student_id, payload);

    return res.json({ message: "Student updated successfully" });

  } catch (err) {
    console.error("Update student failed:", err);
    return res.status(500).json({ error: "Failed to update student" });
  }
};

/* ===========================================================
   3) MARK INACTIVE
=========================================================== */
const markInactiveController = async (req, res) => {
  try {
    const student_id = req.params.id;
    const { inactive_reason } = req.body;
    const user_id = req.user.user_id;

    if (!inactive_reason || inactive_reason.trim() === "") {
      return res.status(400).json({ error: "Inactive reason is required" });
    }

    await markStudentInactiveModel(student_id, inactive_reason, user_id);

    return res.json({ message: "Student marked inactive successfully" });

  } catch (err) {
    console.error("Error marking inactive:", err);
    return res.status(500).json({ error: "Failed to mark student inactive" });
  }
};

/* ===========================================================
   4) INACTIVE HISTORY
=========================================================== */
const getInactiveHistoryController = async (req, res) => {
  try {
    const student_id = req.params.id;

    const rows = await getInactiveHistoryByStudentId(student_id);

    return res.json(rows);
  } catch (err) {
    console.error("Error fetching inactive history:", err);
    return res.status(500).json({ error: "Failed to fetch inactive history" });
  }
};

/* ===========================================================
   5) PROFILE
=========================================================== */
const getStudentProfile = async (req, res) => {
  try {
    const user_id = req.user.user_id;

    const student = await getStudentProfileByUserId(user_id);

    if (!student) {
      return res.status(404).json({
        message: "Student profile not found"
      });
    }

    res.json(student);

  } catch (error) {
    console.error("Error fetching student profile:", error);
    res.status(500).json({ error: "Server error" });
  }
};

/* ===========================================================
   6) TIMETABLE
=========================================================== */
const getMySchedule = async (req, res) => {
  try {
    const userId = req.user.user_id;

    const profile = await getStudentProfileByUserId(userId);

    if (!profile) {
      return res.status(404).json({ message: "Student profile not found." });
    }

    if (!profile.batch_id) {
      return res.status(400).json({ message: "No batch assigned." });
    }

    const timetable = await getStudentTimetableModel(profile.batch_id);

    res.status(200).json(timetable);

  } catch (error) {
    console.error("Error in getMySchedule:", error.message);
    res.status(500).json({ message: "Internal Server Error" });
  }
};

/* ===========================================================
   🔥 7) PERFORMANCE - SUMMARY
=========================================================== */
const getStudentSummary = async (req, res) => {
  try {
    const user_id = req.user.user_id;

    const data = await getStudentSummaryModel(user_id);

    res.json(data);
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: "Failed to fetch summary" });
  }
};

/* ===========================================================
   🔥 8) SUBJECT PERFORMANCE
=========================================================== */
const getStudentSubjectPerformance = async (req, res) => {
  try {
    const user_id = req.user.user_id;

    const data = await getStudentSubjectPerformanceModel(user_id);

    res.json(data);
  } catch (err) {
    res.status(500).json({ error: "Failed to fetch subject performance" });
  }
};

/* ===========================================================
   🔥 9) MONTHLY
=========================================================== */
const getStudentMonthlyAttendance = async (req, res) => {
  try {
    const user_id = req.user.user_id;

    const data = await getStudentMonthlyAttendanceModel(user_id);

    res.json(data);
  } catch (err) {
    res.status(500).json({ error: "Failed to fetch monthly data" });
  }
};

/* ===========================================================
   🔥 10) WEEKLY
=========================================================== */
const getStudentWeeklyAttendance = async (req, res) => {
  try {
    const user_id = req.user.user_id;

    const data = await getStudentWeeklyAttendanceModel(user_id);

    res.json(data);
  } catch (err) {
    res.status(500).json({ error: "Failed to fetch weekly data" });
  }
};

/* ===========================================================
   🔥 11) CUSTOM RANGE
=========================================================== */
const getStudentCustomAttendance = async (req, res) => {
  try {
    const user_id = req.user.user_id;
    const { fromDate, toDate } = req.query;

    if (!fromDate || !toDate) {
      return res.status(400).json({ error: "Date range required" });
    }

    const data = await getStudentCustomAttendanceModel(user_id, fromDate, toDate);

    res.json(data);
  } catch (err) {
    res.status(500).json({ error: "Failed to fetch custom data" });
  }
};

/* ===========================================================
   EXPORTS
=========================================================== */
module.exports = {
  getStudentsController,
  updateStudentController,
  markInactiveController,
  getInactiveHistoryController,
  getStudentProfile,
  getMySchedule,

  getStudentSummary,
  getStudentSubjectPerformance,
  getStudentMonthlyAttendance,
  getStudentWeeklyAttendance,
  getStudentCustomAttendance
};










