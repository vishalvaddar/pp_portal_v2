const pool = require("../config/db");

const getTeachersBySubject = async (subjectId) => {
  const result = await pool.query(
    `SELECT 
        t.teacher_id, 
        u.user_name AS teacher_name
     FROM pp.teacher t
     JOIN pp.user u ON t.user_id = u.user_id
     JOIN pp.teacher_subject ts ON t.teacher_id = ts.teacher_id
     WHERE ts.subject_id = $1
     ORDER BY u.user_name`,
    [subjectId]
  );
  return result.rows || [];
};

const createClassroom = async (data) => {
  const {
    classroom_name, 
    subject_id,
    teacher_id,
    platform_id,
    class_link,
    active_yn,
    created_by,
    updated_by,
    batch_ids
  } = data;

  const client = await pool.connect();

  try {
    await client.query('BEGIN');

    const classRes = await client.query(
      `INSERT INTO pp.classroom
       (classroom_name, subject_id, teacher_id, platform_id, class_link, active_yn, created_by, updated_by)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
       RETURNING classroom_id`,
      [classroom_name, subject_id, teacher_id, platform_id, class_link, active_yn, created_by, updated_by]
    );

    const newClassroomId = classRes.rows[0].classroom_id;

    if (batch_ids && batch_ids.length > 0) {
      for (const batchId of batch_ids) {
        await client.query(
          `INSERT INTO pp.classroom_batch (classroom_id, batch_id) VALUES ($1, $2)`,
          [newClassroomId, batchId]
        );
      }
    }

    await client.query('COMMIT');
    return { classroom_id: newClassroomId };
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
};

const getClassrooms = async () => {
  const result = await pool.query(
    `SELECT 
        c.classroom_id, c.classroom_name, c.class_link, c.active_yn, c.description, c.created_at,
        c.subject_id, c.teacher_id, c.platform_id,
        s.subject_name, s.subject_code,
        u.user_name AS teacher_name, 
        p.platform_name,
        COALESCE(array_agg(cb.batch_id) FILTER (WHERE cb.batch_id IS NOT NULL), '{}') AS batch_ids,
        MAX(b.cohort_number) AS cohort_number
     FROM pp.classroom c
     LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
     LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
     LEFT JOIN pp.user u ON t.user_id = u.user_id
     LEFT JOIN pp.teaching_platform p ON c.platform_id = p.platform_id
     LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
     LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
     GROUP BY 
        c.classroom_id, s.subject_name, s.subject_code, 
        u.user_name, p.platform_name
     ORDER BY c.created_at DESC`
  );
  return result.rows || [];
};

const getSubjects = async () => {
  const res = await pool.query(
    `SELECT subject_id, subject_name, subject_code FROM pp.subject ORDER BY subject_name`
  );
  return res.rows;
};

const getTeachingPlatforms = async () => {
  const result = await pool.query(
    `SELECT platform_id, platform_name FROM pp.teaching_platform ORDER BY platform_name`
  );
  return result.rows || [];
};

const updateClassroom = async (classroomId, data) => {
    const {
        classroom_name,
        subject_id,
        teacher_id,
        platform_id,
        class_link,
        active_yn,
        updated_by,
        batch_ids
    } = data;

    const client = await pool.connect();

    try {
      await client.query('BEGIN');

      // 1. Update classroom details
      const result = await client.query(
          `UPDATE pp.classroom
              SET classroom_name = $1,
                  subject_id = $2,
                  teacher_id = $3,
                  platform_id = $4,
                  class_link = $5,
                  active_yn = $6,
                  updated_by = $7,
                  updated_at = NOW()
              WHERE classroom_id = $8
              RETURNING classroom_id`,
          [classroom_name, subject_id, teacher_id, platform_id, class_link, active_yn, updated_by, classroomId]
      );

      // 2. Re-sync batches: Delete old bindings, insert new bindings
      if (batch_ids && Array.isArray(batch_ids)) {
        await client.query(`DELETE FROM pp.classroom_batch WHERE classroom_id = $1`, [classroomId]);
        
        for (const batchId of batch_ids) {
          await client.query(
            `INSERT INTO pp.classroom_batch (classroom_id, batch_id) VALUES ($1, $2)`,
            [classroomId, batchId]
          );
        }
      }

      await client.query('COMMIT');
      return result.rows[0];
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    } finally {
      client.release();
    }
};

/**
 * DELETE CLASSROOM (Transactional)
 * Deletes references in the junction table first to avoid FK constraint violations
 */
const deleteClassroom = async (classroomId) => {
    const client = await pool.connect();
    
    try {
      await client.query('BEGIN');
      
      // 1. Delete mapping to batches
      await client.query(`DELETE FROM pp.classroom_batch WHERE classroom_id = $1`, [classroomId]);
      
      // 2. Delete classroom
      const result = await client.query(
          `DELETE FROM pp.classroom
              WHERE classroom_id = $1
              RETURNING classroom_id`,
          [classroomId]
      );
      
      await client.query('COMMIT');
      return result.rows[0];
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    } finally {
      client.release();
    }
};

const getBatchesByCohort = async (cohort_number) => {
    const result = await pool.query(
        `SELECT batch_id, batch_name FROM pp.batch WHERE cohort_number = $1 ORDER BY batch_name`,
        [cohort_number]
    );
    return result.rows || [];
};

module.exports = {
  getTeachersBySubject,
  createClassroom,
  getClassrooms,
  getSubjects,
  getBatchesByCohort,
  getTeachingPlatforms,
  updateClassroom,
  deleteClassroom
};