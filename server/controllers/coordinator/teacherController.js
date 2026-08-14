const pool = require("../../config/db.js");

const getCoordinatorTeachers = async (req, res) => {
  const coordinatorId = req.params.coordinatorId || req.query.user_id;
  console.log("🎓 Fetching teachers for coordinator:", coordinatorId);

  if (!coordinatorId) {
    return res.status(400).json({ error: "Coordinator ID is required" });
  }

  try {
    const query = `
      SELECT DISTINCT 
          t.teacher_id,
          t.teacher_name,
          t.contact_no,
          s.subject_name,
          b.batch_name

      FROM pp.batch_coordinator_batches bcb

      JOIN pp.batch b 
          ON bcb.batch_id = b.batch_id

      JOIN pp.classroom_batch cb 
          ON b.batch_id = cb.batch_id

      JOIN pp.classroom cls 
          ON cb.classroom_id = cls.classroom_id

      -- 🔥 IMPORTANT: use class_session instead of classroom teacher
      JOIN pp.class_session cs 
          ON cs.classroom_id = cls.classroom_id

      JOIN pp.teacher t 
          ON t.teacher_id = cs.teacher_id

      LEFT JOIN pp.subject s 
          ON cls.subject_id = s.subject_id

      WHERE bcb.user_id = $1

      ORDER BY t.teacher_name;
    `;

    const result = await pool.query(query, [coordinatorId]);

    console.log("✅ Teachers fetched:", result.rows.length);

    res.status(200).json(result.rows);

  } catch (error) {
    console.error("❌ Error fetching coordinator teachers:", error.message);
    res.status(500).json({ error: error.message });
  }
};

module.exports = { getCoordinatorTeachers };