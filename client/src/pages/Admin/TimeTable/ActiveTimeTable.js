import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom'; 
import axios from 'axios';
import { useAuth } from "../../../contexts/AuthContext"; 
import styles from './ActiveTimeTable.module.css';
import Breadcrumbs from "../../../components/Breadcrumbs/Breadcrumbs"; 
import * as XLSX from 'xlsx';
import { jsPDF } from 'jspdf';
import autoTable from 'jspdf-autotable';

const ActiveTimeTable = () => {
  const { user } = useAuth();
  const BASE = `${process.env.REACT_APP_BACKEND_API_URL}/api/activetimetable`;
  const DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

  // Matches the URL tokens exactly so your component routes to /admin/academics/time-table-dashboard
  const currentPath = ['Admin', 'Academics', 'time-table-dashboard', 'Active timetable'];

  const [view, setView] = useState('combined');
  const [filters, setFilters] = useState({ cohort: '', batch: '', teacher: '' });
  const [options, setOptions] = useState({ cohorts: [], batches: [], teachers: [] });
  const [timetable, setTimetable] = useState([]);
  
  const [showSubjectModal, setShowSubjectModal] = useState(false);
  const [showSkillsModal, setShowSkillsModal] = useState(false);

  const [selectedTeacher, setSelectedTeacher] = useState('');
  const [teacherSkills, setTeacherSkills] = useState([]);
  const [availableSubjects, setAvailableSubjects] = useState([]);
  const [skillForm, setSkillForm] = useState({ subjectId: '', medium: '' });
  const [subjectForm, setSubjectForm] = useState({ subject_code: '', subject_name: '' });

  useEffect(() => {
    const fetchInitialData = async () => {
      try {
        const res = await axios.get(`${BASE}/dropdowns`);
        setOptions(prev => ({ 
          ...prev, 
          cohorts: res.data.cohorts || [], 
          teachers: res.data.teachers || [] 
        }));
      } catch (err) { console.error(err); }
    };
    fetchInitialData();
  }, [BASE]);

  useEffect(() => {
    if (filters.cohort) {
      const fetchBatches = async () => {
        try {
          const res = await axios.get(`${BASE}/batches?cohortName=${filters.cohort}`);
          setOptions(prev => ({ ...prev, batches: res.data || [] }));
        } catch (err) { console.error(err); }
      };
      fetchBatches();
    }
  }, [filters.cohort, BASE]);

  useEffect(() => {
    if (showSkillsModal && selectedTeacher) {
      const fetchSkills = async () => {
        try {
          const res = await axios.get(`${BASE}/teacher-skills/${selectedTeacher}`);
          setTeacherSkills(res.data.skills || []);
          setAvailableSubjects(res.data.allSubjects || []);
        } catch (err) { console.error(err); }
      };
      fetchSkills();
    }
  }, [selectedTeacher, showSkillsModal, BASE]);

  const handleFetch = async () => {
    let id = view === 'teacher' ? filters.teacher : (view === 'batch' ? filters.batch : filters.cohort);
    if (!id) return alert("Please select filters first!");
    try {
      const res = await axios.get(`${BASE}/fetch`, { 
        params: { type: view, id, cohort: filters.cohort } 
      });
      setTimetable(res.data || []);
    } catch (err) { console.error(err); }
  };

  const handleAddSubject = async (e) => {
    e.preventDefault();
    try {
      await axios.post(`${BASE}/subject/add`, { ...subjectForm, admin_id: user?.user_id });
      alert("Subject Added Successfully!");
      setShowSubjectModal(false);
    } catch (err) { alert(err.response?.data?.error || "Error adding subject"); }
  };

  const manageSkill = async (action, skillData = null) => {
    if (action === 'add' && (!skillForm.subjectId || !skillForm.medium)) return alert("Select Subject and Medium");
    try {
      const payload = action === 'add' 
        ? { action, teacherId: selectedTeacher, ...skillForm, admin_id: user?.user_id }
        : { action, teacherId: selectedTeacher, ...skillData };
      await axios.post(`${BASE}/teacher-skills/manage`, payload);
      const res = await axios.get(`${BASE}/teacher-skills/${selectedTeacher}`);
      setTeacherSkills(res.data.skills || []);
      if(action === 'add') setSkillForm({ subjectId: '', medium: '' });
    } catch (err) { alert("Skill management failed(Duplicate skill can't be added)"); }
  };

  const exportToXLS = () => {
    if (timetable.length === 0) return alert("Please load data first.");
    const exportData = timetable.map(item => ({
      DAY: item.day_of_week?.toUpperCase(),
      "START TIME": item.start_time,
      "END TIME": item.end_time,
      SUBJECT: item.subject_name?.toUpperCase(),
      TEACHER: item.teacher_name?.toUpperCase(),
      BATCH: item.batch_name?.toUpperCase()
    }));
    const ws = XLSX.utils.json_to_sheet(exportData);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "TIMETABLE");
    XLSX.writeFile(wb, `TIMETABLE_${filters.cohort || view}.xlsx`);
  };

  const exportToPDF = async () => {
    if (timetable.length === 0) return alert("No data to download.");
    
    const teacherName = options.teachers.find(t => t.teacher_id == filters.teacher)?.teacher_name || "";
    const batchName = options.batches.find(b => b.batch_id == filters.batch)?.batch_name || filters.batch || "";

    const cleanCohort = (filters.cohort || "GENERAL").replace(/\s+/g, '_');
    const cleanBatch = (batchName || "ALL").replace(/\s+/g, '_');
    const fileName = `TIMETABLE_${cleanCohort}_${cleanBatch}.pdf`;

    try {
        const response = await axios.post(`${BASE}/download-pdf`, {
            timetableData: timetable,
            cohortName: filters.cohort || "GENERAL",
            viewType: view,
            fileName: fileName, 
            filterDetails: { teacherName, batchName } 
        }, { responseType: 'blob' });

        const url = window.URL.createObjectURL(new Blob([response.data]));
        const link = document.createElement('a');
        link.href = url;
        link.setAttribute('download', fileName);
        document.body.appendChild(link);
        link.click();
        link.remove();
    } catch (error) {
        console.error("Export Error:", error);
        alert("Failed to generate PDF from server.");
    }
  };

  return (
    <div style={{ padding: '24px 32px 0 32px', backgroundColor: '#f1f5f9' }}>
      
      <Breadcrumbs path={currentPath} nonLinkSegments={['Admin', 'Academics', 'Active timetable']} />

      <div className={styles.dashboardWrapper} style={{ marginTop: '16px' }}>
        <aside className={styles.managementSidebar}>
          <h3 className={styles.sidebarTitle}>Timetable Setup</h3>
          <button onClick={() => setShowSubjectModal(true)} className={styles.actionBtn}>+ Add Subject</button>
          <button onClick={() => setShowSkillsModal(true)} className={styles.actionBtnSkills}>Teacher Skills</button>
        </aside>

        <main className={styles.ttContainer} style={{ paddingTop: '0' }}>
          <div className={styles.ttHeader}>
            <div className={styles.headerTop}>
              <div style={{ display: 'flex', alignItems: 'center' }}>
                <h2 className={styles.title}>Active TimeTable </h2>
              </div>
              <div className={styles.exportSection}>
                <button onClick={exportToXLS} className={styles.downloadBtnXls}>XLS</button>
                <button onClick={exportToPDF} className={styles.downloadBtnPdf}>PDF</button>
              </div>
            </div>
            
            <div className={styles.segmentControl}>
              {['combined', 'teacher', 'batch'].map((v) => (
                <button key={v} className={`${styles.tab} ${view === v ? styles.active : ''}`} onClick={() => { setView(v); setTimetable([]); }}>
                  {v.toUpperCase()}
                </button>
              ))}
            </div>

            <div className={styles.filterBar}>
              {(view === 'combined' || view === 'batch') && (
                <select value={filters.cohort} onChange={(e) => setFilters({ ...filters, cohort: e.target.value, batch: '' })}>
                  <option value="">Select Cohort</option>
                  {options.cohorts.map(c => <option key={c.cohort_number} value={c.cohort_name}>{c.cohort_name}</option>)}
                </select>
              )}
              {view === 'batch' && (
                <select value={filters.batch} onChange={(e) => setFilters({ ...filters, batch: e.target.value })} disabled={!filters.cohort}>
                  <option value="">Select Batch</option>
                  {options.batches.map(b => <option key={b.batch_id} value={b.batch_name}>{b.batch_name}</option>)}
                </select>
              )}
              {view === 'teacher' && (
                <select value={filters.teacher} onChange={(e) => setFilters({ ...filters, teacher: e.target.value })}>
                  <option value="">Select Teacher</option>
                  {options.teachers.map(t => <option key={t.teacher_id} value={t.teacher_id}>{t.teacher_name}</option>)}
                </select>
              )}
              <button onClick={handleFetch} className={styles.loadBtn}>Load TimeTable</button>
            </div>
          </div>

          <div className={styles.matrixContainer}>
            {DAYS.map((day) => (
              <React.Fragment key={day}>
                <div className={styles.matrixRow}>
                  <div className={styles.dayColumn}>{day}</div>
                  <div className={styles.slotsColumn}>
                    {timetable.filter(t => t.day_of_week?.trim().toLowerCase() === day.toLowerCase()).length > 0 ? (
                      timetable
                        .filter(t => t.day_of_week?.trim().toLowerCase() === day.toLowerCase())
                        .sort((a,b) => (a.batch_name || "").localeCompare(b.batch_name || ""))
                        .map((item, idx) => (
                          <div key={idx} className={styles.matrixCard}>
                            <div className={styles.cardTime}>{item.start_time} - {item.end_time}</div>
                            <div className={styles.cardSubject}>{item.subject_name}</div>
                            <div className={styles.cardMeta}>{item.teacher_name} | {item.batch_name}</div>
                          </div>
                        ))
                    ) : <span className={styles.emptyText}>No Classes</span>}
                  </div>
                </div>
                <div className={styles.weekSeparator}></div>
              </React.Fragment>
            ))}
          </div>
        </main>

        {/* Subject Modal */}
        {showSubjectModal && (
          <div className={styles.modalOverlay}>
            <div className={styles.modalContentWide} style={{width: '400px'}}>
               <div className={styles.modalHeader}>
                  <h3>Add New Subject</h3>
                  <button onClick={() => setShowSubjectModal(false)} className={styles.closeIcon}>&times;</button>
               </div>
               <div className={styles.modalBody}>
                  <form onSubmit={handleAddSubject} className={styles.modalForm}>
                    <input placeholder="Subject Code" maxLength="5" onChange={e => setSubjectForm({...subjectForm, subject_code: e.target.value})} required className={styles.skillSelect} style={{marginBottom: '10px'}}/>
                    <input placeholder="Subject Name" onChange={e => setSubjectForm({...subjectForm, subject_name: e.target.value})} required className={styles.skillSelect}/>
                    <button type="submit" className={styles.addBtn} style={{marginTop: '20px', width: '100%'}}>Save Subject</button>
                  </form>
               </div>
            </div>
          </div>
        )}

        {/* Skills Modal */}
        {showSkillsModal && (
          <div className={styles.modalOverlay}>
            <div className={styles.modalContentWide}>
              <div className={styles.modalHeader}>
                  <h3>Teacher Skill Management</h3>
                  <button onClick={() => setShowSkillsModal(false)} className={styles.closeIcon}>&times;</button>
              </div>
              <div className={styles.modalBody}>
                  <div className={styles.selectionArea}>
                      <label>Select Teacher</label>
                      <select className={styles.skillSelect} value={selectedTeacher} onChange={e => setSelectedTeacher(e.target.value)}>
                          <option value="">-- Choose a teacher --</option>
                          {options.teachers.map(t => <option key={t.teacher_id} value={t.teacher_id}>{t.teacher_name}</option>)}
                      </select>
                  </div>
                  {selectedTeacher && (
                      <>
                          <div className={styles.addSkillBox}>
                              <h4>Add New Skill</h4>
                             <div className={styles.addSkillRow}>
                                {/* FIXED: Now accurately references setSkillForm and updates skillForm state */}
                                <select value={skillForm.subjectId} onChange={e => setSkillForm({...skillForm, subjectId: e.target.value})}>
                                    <option value="">Select Subject</option>
                                    {availableSubjects.map(s => <option key={s.subject_id} value={s.subject_id}>{s.subject_name}</option>)}
                                </select>
                                {/* FIXED: Now accurately references setSkillForm and updates skillForm state */}
                                <select value={skillForm.medium} onChange={e => setSkillForm({...skillForm, medium: e.target.value})}>
                                    <option value="">Select Medium</option>
                                    <option value="KANNADA">KANNADA</option>
                                    <option value="ENGLISH">ENGLISH</option>
                                    <option value="HINDI">HINDI</option>
                                    <option value="MARATHI">MARATHI</option>
                                </select>
                                <button className={styles.addBtn} onClick={() => manageSkill('add')}>Add Skill</button>
                             </div>
                          </div>
                          <div className={styles.skillsListContainer}>
                              <div className={styles.skillsList}>
                                  {teacherSkills.length > 0 ? teacherSkills.map((s, i) => (
                                      <div key={i} className={styles.skillItem}>
                                          <div className={styles.skillInfo}>
                                              <span className={styles.skillSubject}>{s.subject_name}</span>
                                              <span className={styles.skillMedium}>{s.medium}</span>
                                          </div>
                                          <button className={styles.deleteBtn} onClick={() => manageSkill('delete', { subjectId: s.subject_id, medium: s.medium })}>&times;</button>
                                      </div>
                                  )) : <div className={styles.noData}>No skills assigned.</div>}
                              </div>
                          </div>
                      </>
                  )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ActiveTimeTable;