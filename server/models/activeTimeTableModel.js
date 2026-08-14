const pool = require("../config/db");

const TimetableModel = {
  getCohorts: async () => {
    const { rows } = await pool.query("SELECT cohort_number, cohort_name FROM pp.cohort WHERE end_date IS NULL ORDER BY cohort_name");
    return rows;
  },

  getBatches: async (cohortName) => {
    const query = `
      SELECT b.batch_id, b.batch_name 
      FROM pp.batch b
      JOIN pp.cohort c ON b.cohort_number = c.cohort_number
      WHERE c.cohort_name = $1`;
    const { rows } = await pool.query(query, [cohortName]);
    return rows;
  },

  getTeachers: async () => {
    const { rows } = await pool.query("SELECT teacher_id, teacher_name FROM pp.teacher ORDER BY teacher_name");
    return rows;
  },

  getCombined: async (cohortName) => {
    const query = `
      SELECT 
        t.teacher_name, 
        s.subject_name, 
        b.batch_name, 
        tt.day_of_week, 
        tt.start_time, 
        tt.end_time
      FROM pp.timetable tt
      LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
      LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
      LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
      LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
      LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
      LEFT JOIN pp.cohort ch ON b.cohort_number = ch.cohort_number
      WHERE ch.cohort_name = $1
      ORDER BY 
        CASE 
          WHEN TRIM(LOWER(tt.day_of_week)) = 'sunday' THEN 1
          WHEN TRIM(LOWER(tt.day_of_week)) = 'monday' THEN 2
          WHEN TRIM(LOWER(tt.day_of_week)) = 'tuesday' THEN 3
          WHEN TRIM(LOWER(tt.day_of_week)) = 'wednesday' THEN 4
          WHEN TRIM(LOWER(tt.day_of_week)) = 'thursday' THEN 5
          WHEN TRIM(LOWER(tt.day_of_week)) = 'friday' THEN 6
          WHEN TRIM(LOWER(tt.day_of_week)) = 'saturday' THEN 7
        END, tt.start_time;`;
    const { rows } = await pool.query(query, [cohortName]);
    return rows;
  },

  getTeacherWise: async (teacherId) => {
    const query = `
      SELECT t.teacher_name, s.subject_name, b.batch_name, tt.day_of_week, tt.start_time, tt.end_time
      FROM pp.timetable tt
      LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
      LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
      LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
      LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
      LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
      WHERE t.teacher_id = $1
      ORDER BY tt.day_of_week, tt.start_time;`;
    const { rows } = await pool.query(query, [teacherId]);
    return rows;
  },

  getBatchWise: async (batchName, cohortName) => {
    const query = `
      SELECT s.subject_name, t.teacher_name, b.batch_name, tt.day_of_week, tt.start_time, tt.end_time
      FROM pp.timetable tt
      LEFT JOIN pp.classroom c ON tt.classroom_id = c.classroom_id
      LEFT JOIN pp.subject s ON c.subject_id = s.subject_id
      LEFT JOIN pp.teacher t ON c.teacher_id = t.teacher_id
      LEFT JOIN pp.classroom_batch cb ON c.classroom_id = cb.classroom_id
      LEFT JOIN pp.batch b ON cb.batch_id = b.batch_id
      LEFT JOIN pp.cohort ch ON b.cohort_number = ch.cohort_number
      WHERE b.batch_name = $1 AND ch.cohort_name = $2
      ORDER BY tt.day_of_week, tt.start_time;`;
    const { rows } = await pool.query(query, [batchName, cohortName]);
    return rows;
  },

   // Fetch existing skills for a teacher
  getTeacherSkills: async (teacherId) => {
    const query = `
      SELECT ts.subject_id, s.subject_name, ts.medium
      FROM pp.teacher_subject ts
      JOIN pp.subject s ON ts.subject_id = s.subject_id
      WHERE ts.teacher_id = $1`;
    const { rows } = await pool.query(query, [teacherId]);
    return rows;
  },



// Add a new skill
  addTeacherSkill: async (teacherId, subjectId, medium) => {
    try {
      const query = `
        INSERT INTO pp.teacher_subject (teacher_id, subject_id, medium)
        VALUES ($1, $2, $3) 
        RETURNING *`;
      
      // Ensure medium is Uppercase to pass the DB Check Constraint
      const { rows } = await pool.query(query, [teacherId, subjectId, medium.toUpperCase()]);
      return rows[0];
    } catch (error) {
      throw error;
    }
  },

  // Delete a skill
  deleteTeacherSkill: async (teacherId, subjectId, medium) => {
    const query = `
      DELETE FROM pp.teacher_subject 
      WHERE teacher_id = $1 AND subject_id = $2 AND medium = $3`;
    await pool.query(query, [teacherId, subjectId, medium]);
  },

  getSubjects: async () => {
    const { rows } = await pool.query("SELECT subject_id, subject_name FROM pp.subject ORDER BY subject_name");
    return rows;
  },

  addSubject: async (subjectData) => {
    const { subject_code, subject_name, created_by } = subjectData;
    try {
      const query = `
        INSERT INTO pp.subject 
        (subject_code, subject_name, created_by, updated_by)
        VALUES ($1, $2, $3, $3)
        RETURNING *;`;
      const values = [subject_code, subject_name, created_by];
      const { rows } = await pool.query(query, values);
      return rows[0];
    } catch (error) {
      console.error("Model Error in addSubject:", error.message);
      throw error;
    }
  }
};



module.exports = TimetableModel;

