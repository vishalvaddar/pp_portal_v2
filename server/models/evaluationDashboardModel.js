const pool = require("../config/db");

const DashboardModel = {
  // 1. High-level counts
  async getOverallCounts(nmmsYear) {
    try {
      const queries = [
        { label: 'Total Students', sql: `SELECT COUNT(*) FROM pp.applicant_primary_info WHERE nmms_year = $1;` },
        { label: 'Shortlisted', sql: `SELECT COUNT(*) FROM pp.applicant_shortlist_info a JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id WHERE api.nmms_year = $1 and a.shortlisted_yn='Y';` },
        { label: 'Evaluated', sql: `SELECT COUNT(*) FROM pp.applicant_secondary_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id WHERE api.nmms_year = $1;` },
        { label: 'Pending Evaluation/Marks Entry', sql: `SELECT COUNT(*) FROM pp.applicant_primary_info a WHERE a.applicant_id NOT IN (SELECT asi.applicant_id FROM pp.applicant_secondary_info asi) AND a.applicant_id IN (SELECT s.applicant_id FROM pp.applicant_shortlist_info s) AND a.nmms_year = $1;` },
        { label: 'Interview Required', sql: `SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1;` },
        { label: 'Pending Interviews Assignment', sql: `SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1 AND NOT EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id);` },
        { label: 'Pending Interview Result Upload', sql: `SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1 AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.interview_result IS NULL);` },
        { label: 'Home Verification Required', sql: `SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1 AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn='Y');` },
        { label: 'Pending Home Verification Result Upload', sql: `SELECT COUNT(*) FROM pp.exam_results er JOIN pp.applicant_primary_info api ON er.applicant_id = api.applicant_id WHERE er.interview_required_yn = 'Y' AND api.nmms_year = $1 AND EXISTS (SELECT 1 FROM pp.student_interview si WHERE si.applicant_id = er.applicant_id AND si.home_verification_req_yn = 'Y') AND NOT EXISTS (SELECT 1 FROM pp.home_verification hv WHERE hv.applicant_id = er.applicant_id AND hv.status IS NOT NULL);` },
      ];

      const results = {};
      for (const q of queries) {
        const result = await pool.query(q.sql, [nmmsYear]);
        results[q.label] = parseInt(result.rows[0].count, 10);
      }
      return results;
    } catch (err) {
      console.error('Error fetching overall counts:', err);
      throw err;
    }
  },

  async getJurisdictionStatus(nmmsYear) {
    try {
      const sql = `
        SELECT 
          j.juris_name, 
          j.juris_code,
          COUNT(asi.applicant_id) AS "totalShortlisted",
          
          -- Count of students who have secondary info (Evaluated)
          COUNT(sec.applicant_id) AS "evaluated",
          
          -- Students in shortlist but NOT in secondary info
          COUNT(CASE WHEN sec.applicant_id IS NULL THEN 1 END) AS "pendingEvaluation",
          
          -- Interview stats
          COUNT(CASE WHEN er.interview_required_yn = 'Y' THEN 1 END) AS "totalInterviewRequired",
          COUNT(CASE WHEN si.status = 'Completed' THEN 1 END) AS "completedInterview"

        FROM pp.jurisdiction j
        JOIN pp.applicant_primary_info a ON j.juris_code = a.nmms_block
        JOIN pp.applicant_shortlist_info asi ON a.applicant_id = asi.applicant_id
        LEFT JOIN pp.applicant_secondary_info sec ON a.applicant_id = sec.applicant_id
        LEFT JOIN pp.exam_results er ON a.applicant_id = er.applicant_id
        LEFT JOIN pp.student_interview si ON a.applicant_id = si.applicant_id
        
        WHERE a.nmms_year = $1
        GROUP BY j.juris_code, j.juris_name
        ORDER BY j.juris_name ASC;
      `;

      const result = await pool.query(sql, [nmmsYear]);
      
      // Calculate progress percentage in JS to keep SQL clean
      return result.rows.map(row => {
        const total = parseInt(row.totalShortlisted, 10);
        const done = parseInt(row.evaluated, 10);
        return {
          ...row,
          progress: total > 0 ? Math.round((done / total) * 100) : 0,
          counts: {
            pendingEvaluation: parseInt(row.pendingEvaluation, 10),
            totalInterviewRequired: parseInt(row.totalInterviewRequired, 10),
            completedInterview: parseInt(row.completedInterview, 10)
          }
        };
      });
    } catch (err) {
      console.error('Error fetching optimized jurisdiction status:', err);
      throw err;
    }
  },



  // 4. Overall Progress
  async getOverallProgress(nmmsYear) {
    try {
      const q1 = `SELECT COUNT(*) FROM pp.applicant_shortlist_info a JOIN pp.applicant_primary_info api ON a.applicant_id = api.applicant_id WHERE api.nmms_year = $1;`;
      const q2 = `SELECT COUNT(*) FROM pp.applicant_secondary_info asi JOIN pp.applicant_primary_info api ON asi.applicant_id = api.applicant_id WHERE api.nmms_year = $1;`;
      
      const res1 = await pool.query(q1, [nmmsYear]);
      const res2 = await pool.query(q2, [nmmsYear]);
      
      const totalReq = parseInt(res1.rows[0].count, 10);
      const totalDone = parseInt(res2.rows[0].count, 10);

      let overallProgress = totalReq > 0 ? Math.round((totalDone / totalReq) * 100) : 0;
      return { overallProgress };
    } catch (err) {
      throw err;
    }
  }
};

module.exports = DashboardModel;