const pool = require("../config/db");

exports.getSubjectsForTimeTable = async () => {
    const query = `
        SELECT subject_id,
               subject_name,
               subject_code,
               CAST(SUBSTRING(subject_code FROM '[0-9]+') AS INTEGER) AS grade,
               SUBSTRING(subject_code FROM '[0-9]+')
               || '-' || subject_name AS grade_subject
        FROM pp.subject
        ORDER BY grade`;
    const result = await pool.query(query);
    return result.rows;
};

exports.getTeachersForTimeTable = async () => {
    const query = `
        SELECT teacher_id,teacher_name
        FROM pp.teacher
        ORDER BY teacher_id`;
    const result = await pool.query(query);
    return result.rows;
};


exports.getBatchesByGrades = async (grades) => {
    const placeholders = grades.map((_, i) => `$${i + 1}`).join(",");
    const query = `
        SELECT 
            'Batch-' || LPAD(c.current_grade::text, 2, '0') || '-' || LPAD(SPLIT_PART(b.batch_name, '-', 2), 2, '0') AS id,
            c.current_grade::text AS grade,b.medium
        FROM pp.batch b JOIN pp.cohort c ON c.cohort_number = b.cohort_number
        WHERE c.current_grade IN (${placeholders})
        ORDER BY id
    `;
    const result = await pool.query(query, grades);
    return result.rows;
};

exports.getCanTeachByTeacherIds = async (teacherIds) => {
    teacherIds = [...new Set(teacherIds)];
    const placeholders = teacherIds.map((_, i) => `$${i + 1}`).join(",");
    const query = `
        SELECT 
            t.teacher_id,s.subject_name AS subject,ts.medium,
            CAST(REGEXP_REPLACE(s.subject_code,'[^0-9]','','g') AS INTEGER) AS grade
        FROM pp.teacher t
        JOIN pp.teacher_subject ts ON t.teacher_id = ts.teacher_id
        JOIN pp.subject s ON s.subject_id = ts.subject_id
        WHERE t.teacher_id IN (${placeholders})
        ORDER BY t.teacher_id
    `;
    const result = await pool.query(query, teacherIds);
    return result.rows;
};



exports.getBatchDetailsForGroupTeacherMapDtls = async (teacherId, subjectId, grade) => {
    const query = `
       SELECT c.subject_id,
            c.subject_name,
            a.teacher_name,d.current_grade,
            e.batch_id,e.batch_name,e.medium,
            'g' || d.current_grade || '-' || SPLIT_PART(e.batch_name,'-',2) AS gid
        FROM pp.teacher a
        JOIN pp.teacher_subject b ON a.teacher_id = b.teacher_id
        JOIN pp.subject c ON b.subject_id = c.subject_id
        JOIN pp.cohort d ON d.current_grade = CAST(SPLIT_PART(c.subject_name, '-', 2) AS INTEGER)
        JOIN pp.batch e ON (d.cohort_number = e.cohort_number and b.medium = e.medium)
        where a.teacher_id = $1
        and c.subject_id = $2
        and d.current_grade = $3
        order by e.batch_id
    `;
    const result = await pool.query(query, [teacherId, subjectId, grade]);
    return result.rows;
};



exports.getTeachersBySubjects = async (subjectIds) => {
    const placeholders = subjectIds.map((_, i) => `$${i + 1}`).join(",");
    const query = `
        SELECT a.teacher_id,a.teacher_name
        FROM pp.teacher a
        JOIN pp.teacher_subject b ON a.teacher_id = b.teacher_id
        WHERE b.subject_id IN (${placeholders})
        GROUP BY a.teacher_id,a.teacher_name
        ORDER BY a.teacher_id
    `;
    const result = await pool.query(query, subjectIds);
    return result.rows;
};


exports.getAllConfigurationDraftFileDtls = async () => {
    const query = `
        SELECT time_table_config_id,time_table_config_file_name,time_table_config_file_ins_user_name,
            created_at,updated_at,
            COALESCE(updated_at,created_at) AS display_time,
            TO_CHAR(COALESCE(updated_at,created_at),'DD Mon YYYY, HH12:MI AM') AS display_time_formatted
        FROM pp.time_table_config_file
        ORDER BY COALESCE(updated_at,created_at) DESC
    `;
    const result = await pool.query(query);
    return result.rows;
};


exports.getConfigurationDraftFileById = async (id) => {
    const query = `
        SELECT time_table_config_file_name
        FROM pp.time_table_config_file
        WHERE time_table_config_id = $1
    `;
    const result = await pool.query(query, [id]);
    return result.rows;
};

exports.deleteConfigurationDraftFile = async (id) => {
    const query = `
        DELETE FROM pp.time_table_config_file
        WHERE time_table_config_id = $1
    `;
    await pool.query(query, [id]);
};


exports.getConfigById = async (configId) => {
    const query = `
        SELECT 
            time_table_config_id,time_table_config_file_name,
            time_table_config_file_ins_user_name,created_at,updated_at
        FROM pp.time_table_config_file
        WHERE time_table_config_id = $1
    `;
    const result = await pool.query(query, [configId]);
    return result.rows;
};



exports.getConfigurationById = async (configId) => {
    const query = `
        SELECT time_table_config_file_name
        FROM pp.time_table_config_file
        WHERE time_table_config_id = $1
    `;
    const result = await pool.query(query, [configId]);
    return result.rows;
};


exports.getConfigurationByUserName = async (userName) => {
    const query = `
        SELECT 
            time_table_config_id,
            time_table_config_file_name
        FROM pp.time_table_config_file
        WHERE time_table_config_file_ins_user_name = $1
    `;
    const result = await pool.query(query, [userName]);
    return result.rows;
};



exports.updateConfigurationTime = async (configId) => {
    const query = `
        UPDATE pp.time_table_config_file
        SET updated_at = NOW()
        WHERE time_table_config_id = $1
    `;
    await pool.query(query, [configId]);
};


exports.saveConfigurationDraftFile = async (fileName, userName) => {
    const query = `
        INSERT INTO pp.time_table_config_file (
            time_table_config_file_name,
            time_table_config_file_ins_user_name
        )VALUES ($1, $2)
        RETURNING *
    `;
    const result = await pool.query(query, [fileName,userName]);
    return result.rows[0];
};


exports.saveTimeTableSolution = async (fileName, userName) => {
    const query = `
        INSERT INTO pp.time_table_solution
        (
            solution_file_name,
            solution_file_ins_user_name
        )
        VALUES ($1, $2)
        RETURNING *
    `;
    const result = await pool.query(query, [fileName, userName]);
    return result.rows[0];
};

exports.getGradesForCombinedBatches = async () => {
    const query = `
        SELECT c.current_grade || '_' || b.medium AS current_grade
            FROM pp.batch b
        JOIN pp.cohort c ON c.cohort_number = b.cohort_number
        GROUP BY c.current_grade, b.medium
        ORDER BY c.current_grade::int, b.medium;
    `;
    const result = await pool.query(query);
    return result.rows;
};

exports.getBatchesByGradeForCombinedBatches = async (grade, language) => {
    const query = `
        SELECT b.batch_name,
            'Batch-' || LPAD(c.current_grade::text, 2, '0') || '-' || LPAD( SPLIT_PART(b.batch_name,'-',2),2,'0') AS batch_id,
            c.current_grade AS grade,b.medium,
            'g' || c.current_grade || '-' || SPLIT_PART(b.batch_name,'-',2) AS gid
        FROM pp.batch b
        JOIN pp.cohort c ON c.cohort_number = b.cohort_number
        WHERE c.current_grade = $1 AND b.medium = $2
        ORDER BY batch_name
    `;
    const result = await pool.query(query, [grade, language]);
    return result.rows;
};

exports.getBatchesByGradeForCombinedBatchesForPrepration = async (grade) => {
    const query = `
        SELECT b.batch_name,
            'Batch-' || LPAD(c.current_grade::text, 2, '0') || '-' || LPAD(SPLIT_PART(b.batch_name, '-', 2),2,'0') AS batch_id,
            'g' ||c.current_grade || '-' || LPAD(SPLIT_PART(b.batch_name,'-',2),2,'0') AS group_id,
            c.current_grade AS current_grade,b.medium
        FROM pp.batch b
        JOIN pp.cohort c ON c.cohort_number = b.cohort_number
        WHERE c.current_grade = $1
        ORDER BY c.current_grade
    `;
    const result = await pool.query(query, [grade]);
    return result.rows;
};


exports.getBatchesByGradeForSubjectTeacherDtls = async (grade) => {
    const query = `
        SELECT b.batch_name,
            'Batch-' || LPAD(c.current_grade::text, 2, '0') || '-' || LPAD( SPLIT_PART(b.batch_name,'-',2),2,'0') AS batch_id,
            c.current_grade AS grade,b.medium,
            'g' || c.current_grade || '-' || SPLIT_PART(b.batch_name,'-',2) AS gid
        FROM pp.batch b
        JOIN pp.cohort c ON c.cohort_number = b.cohort_number
        WHERE c.current_grade = $1
        ORDER BY batch_name
    `;
    const result = await pool.query(query, [grade]);
    return result.rows;
};


exports.getBatchesForBatchWeeklyPeriod = async () => {
    const query = `
        SELECT b.batch_id,
            'Batch-' || LPAD(c.current_grade::text,2,'0')|| '-' ||LPAD(SPLIT_PART(b.batch_name,'-',2),2,'0') AS batch_name,
            c.current_grade AS grade,b.medium
        FROM pp.batch b
        JOIN pp.cohort c ON c.cohort_number = b.cohort_number
        ORDER BY batch_name
    `;
    const result = await pool.query(query);
    return result.rows;
};


exports.getSubjectsByBatchIdForBatchWeeklyPeriod = async (batchId) => {
    const query = `
        SELECT b.batch_id,
            REGEXP_REPLACE(s.subject_name,'\\s*-\\s*\\d+$','') AS subject_name,
            s.subject_id,b.medium
        FROM pp.subject s
        JOIN pp.cohort c ON CAST(SUBSTRING(s.subject_code FROM '\\d+$') AS INTEGER) = c.current_grade
        JOIN pp.batch b ON b.cohort_number = c.cohort_number
        WHERE b.batch_id = $1
        ORDER BY subject_name
    `;
    const result = await pool.query(query, [batchId]);
    return result.rows;
};

exports.getSavedTimeTableSolutionList = async () => {
    const query = `
        SELECT solution_id,
            solution_file_name,solution_file_ins_user_name,
            TO_CHAR(COALESCE(updated_at, created_at),'YYYY-MM-DD HH24:MI') AS display_date
        FROM pp.time_table_solution
    `;
    const result = await pool.query(query);
    return result.rows;
};


exports.getTimeTableSolutionBySolutionId = async (solutionId) => {
    const query = `
        SELECT solution_file_name
        FROM pp.time_table_solution
        WHERE solution_id = $1
    `;
    const result = await pool.query(query, [solutionId]);
    return result.rows;
};


exports.getBatches = async () => {
    const query = `
        SELECT b.batch_id,
            'Batch-' || LPAD(c.current_grade::text,2,'0')|| '-' || LPAD(SPLIT_PART(b.batch_name,'-',2),2,'0') AS batch_name,
            c.current_grade AS grade,b.medium
        FROM pp.batch b
        JOIN pp.cohort c ON c.cohort_number = b.cohort_number
        ORDER BY batch_name
    `;
    const result = await pool.query(query);
    return result.rows;
};

exports.getSubjectsByBatchId = async (batchId) => {
    const query = `
        SELECT 
            b.batch_id,REGEXP_REPLACE(s.subject_name,'\\s*-\\s*\\d+$','') AS subject_name,
            s.subject_id,b.medium
        FROM pp.subject s
        JOIN pp.cohort c ON CAST(SUBSTRING(s.subject_code FROM '\\d+$') AS INTEGER) = c.current_grade
        JOIN pp.batch b ON b.cohort_number = c.cohort_number
        WHERE b.batch_id = $1
    `;
    const result = await pool.query(query, [batchId]);
    return result.rows;
};


exports.getTeachersBySubject = async (subjectId, medium) => {
    const query = `
        SELECT 
            t.teacher_id,t.teacher_name
        FROM pp.teacher t
        JOIN pp.teacher_subject ts ON t.teacher_id = ts.teacher_id
        WHERE ts.subject_id = $1
        AND ts.medium = $2
        ORDER BY t.teacher_id
    `;
    const result = await pool.query(query,[subjectId, medium]);
    return result.rows;
};

exports.getSolutionFileNameById = async (solutionId) => {
    const query = `
        SELECT solution_file_name
        FROM pp.time_table_solution
        WHERE solution_id = $1
    `;
    const result = await pool.query(query, [solutionId]);
    return result.rows;
};

exports.updateTimeTableSolutionTimestamp = async (solutionId) => {
    const query = `
        UPDATE pp.time_table_solution
        SET updated_at = CURRENT_TIMESTAMP
        WHERE solution_id = $1
    `;
    await pool.query(query, [solutionId]);
};

