const TimetableModel = require("../../models/coordinator/timetableModel");

/* ============================================================
    GET TIMETABLE (by batch)
============================================================ */
exports.getTimetable = async (req, res) => {
  try {
    const batchId = req.query.batchId;
    if (!batchId) return res.status(400).json({ error: "batchId is required" });

    const result = await TimetableModel.getTimetableByBatch(batchId);
    res.json(result);
  } catch (err) {
    console.error("❌ GET TIMETABLE ERROR:", err);
    res.status(500).json({ error: "Failed to fetch timetable" });
  }
};

/* ============================================================
    CHECK CONFLICTS
============================================================ */
exports.checkConflict = async (req, res) => {
  try {
    const classroomId = req.query.classroomId || req.query.classroom_id;
    const teacherId = req.query.teacherId || req.query.teacher_id;
    const day = req.query.day || req.query.dayOfWeek;
    const startTime = req.query.startTime || req.query.start_time;
    const endTime = req.query.endTime || req.query.end_time;
    const excludeId = req.query.excludeId || req.query.exclude_id;

    const conflicts = await TimetableModel.checkConflicts({
      classroom_id: classroomId,
      teacher_id: teacherId,
      day,
      start_time: startTime,
      end_time: endTime,
      exclude_id: excludeId,
    });

    if (conflicts.length > 0) {
      return res.json({ 
        overlap: true, 
        conflicts: conflicts // Detailed list for the frontend alert
      });
    }

    return res.json({ overlap: false });
  } catch (err) {
    console.error("❌ CHECK CONFLICT ERROR:", err);
    res.status(500).json({ error: "Failed to check conflicts" });
  }
};

/* ============================================================
    CREATE SLOT
============================================================ */
exports.createSlot = async (req, res) => {
  try {
    const { batch_id, classroom_id, day, start_time, end_time, class_link } = req.body;

    if (!batch_id || !classroom_id || !day || !start_time || !end_time) {
      return res.status(400).json({ error: "Missing required fields" });
    }

    // 1. Conflict Check
    const conflicts = await TimetableModel.checkConflicts({
      classroom_id,
      day,
      start_time,
      end_time,
      teacher_id: null,
      exclude_id: null,
    });

    if (conflicts.length > 0) {
      return res.status(400).json({
        overlap: true,
        conflicts: conflicts,
        message: "Conflict detected with existing schedule."
      });
    }

    // 2. Create slot and sync the classroom link
    const created = await TimetableModel.createSlot({
      batch_id,
      classroom_id,
      day,
      start_time,
      end_time,
      class_link: class_link || null 
    });

    res.json({ success: true, data: created });
  } catch (err) {
    console.error("❌ CREATE SLOT ERROR:", err);
    res.status(500).json({ error: "Failed to create timetable slot" });
  }
};

/* ============================================================
    UPDATE SLOT
============================================================ */
exports.updateSlot = async (req, res) => {
  try {
    const id = req.params.id;
    const { classroom_id, day, start_time, end_time, class_link } = req.body;

    if (!classroom_id || !day || !start_time || !end_time) {
      return res.status(400).json({ error: "Missing required fields" });
    }

    // 1. Conflict Check
    const conflicts = await TimetableModel.checkConflicts({
      classroom_id,
      day,
      start_time,
      end_time,
      teacher_id: null,
      exclude_id: id,
    });

    if (conflicts.length > 0) {
      return res.status(400).json({
        overlap: true,
        conflicts: conflicts,
        message: "Conflict detected with existing schedule."
      });
    }

    // 2. Use the transactional model method to update both slot and classroom link
    const updated = await TimetableModel.updateSlotAndLink(id, {
      classroom_id,
      day,
      start_time,
      end_time,
      class_link: class_link || null
    });

    res.json({ success: true, data: updated });
  } catch (err) {
    console.error("❌ UPDATE SLOT ERROR:", err);
    res.status(500).json({ error: "Failed to update timetable slot" });
  }
};

/* ============================================================
    DELETE SLOT
============================================================ */
exports.deleteSlot = async (req, res) => {
  try {
    await TimetableModel.deleteSlot(req.params.id);
    res.json({ success: true });
  } catch (err) {
    console.error("❌ DELETE SLOT ERROR:", err);
    res.status(500).json({ error: "Failed to delete timetable slot" });
  }
};