import { useEffect, useState, useCallback } from "react";
import axios from "axios";
import { 
  Search, Plus, Edit, Eye, Trash2,
  CheckCircle, X, Users, Layout, Filter, BookOpen, Presentation,
  User, Link as LinkIcon, Mail, Phone, Calendar,
  ChevronLeft, ChevronRight // Added pagination icons
} from "lucide-react";
import Logo from "../../assets/RCF-PP.jpg";
import styles from "./ClassroomManager.module.css";

const BACKEND = process.env.REACT_APP_BACKEND_API_URL;
const API_BASE = `${BACKEND}/api/classrooms`;

const INITIAL_FORM_STATE = {
  subject_id: "",
  teacher_id: "",
  platform_id: "",
  class_link: "",
  description: "",
  active_yn: "Y",
  created_by: 1,
  updated_by: 1
};

export default function ClassroomManager() {
  const [classrooms, setClassrooms] = useState([]);
  const [subjects, setSubjects] = useState([]);
  const [teachers, setTeachers] = useState([]);
  const [platforms, setPlatforms] = useState([]);
  const [batches, setBatches] = useState([]);
  const [cohorts, setCohorts] = useState([]);
  const [students, setStudents] = useState([]);
  
  // View State
  const [selectedClassroom, setSelectedClassroom] = useState(null);
  const [viewMode, setViewMode] = useState(false);
  const [studentSearch, setStudentSearch] = useState("");
  const [teacherDetails, setTeacherDetails] = useState(null);
  const [loadingView, setLoadingView] = useState(false);
  
  // Form State
  const [showForm, setShowForm] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [selectedCohort, setSelectedCohort] = useState("");
  const [selectedBatches, setSelectedBatches] = useState([]); 
  
  const [search, setSearch] = useState("");
  const [formData, setFormData] = useState(INITIAL_FORM_STATE);
  const [autoName, setAutoName] = useState("");
  const [loading, setLoading] = useState(false);
  const [showAssignModal, setShowAssignModal] = useState(false);
  const [assignData, setAssignData] = useState({ subject_id: "", teacher_id: ""});

  // Pagination State
  const [currentPage, setCurrentPage] = useState(1);
  const [rowsPerPage, setRowsPerPage] = useState(10);

  // --------------------------
  // API Fetching
  // --------------------------
  const loadInitialData = useCallback(async () => {
    setLoading(true);
    try {
      const [resSubs, resPlats, resCohorts, resRooms] = await Promise.all([
        axios.get(`${API_BASE}/subjects`),
        axios.get(`${API_BASE}/platforms`),
        axios.get(`${BACKEND}/api/batches/cohorts`), 
        axios.get(`${API_BASE}`)
      ]);
      setSubjects(resSubs.data || []);
      setPlatforms(resPlats.data || []);
      setCohorts(resCohorts.data || []);
      setClassrooms(resRooms.data || []);
    } catch (err) {
      console.error("Failed to load initial data", err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadInitialData(); }, [loadInitialData]);

  // FETCH TEACHERS WHEN SUBJECT CHANGES
  useEffect(() => {
    if (formData.subject_id) {
      axios.get(`${API_BASE}/teachers/${formData.subject_id}`)
        .then(res => setTeachers(res.data))
        .catch(err => console.error("Error fetching teachers", err));
    } else {
      setTeachers([]);
    }
  }, [formData.subject_id]);

  // FETCH BATCHES DYNAMICALLY WHEN COHORT CHANGES
  useEffect(() => {
    if (selectedCohort) {
      axios.get(`${API_BASE}/batches/${selectedCohort}`)
        .then(res => setBatches(res.data || []))
        .catch(err => console.error("Error fetching batches for cohort", err));
    } else {
      setBatches([]);
    }
  }, [selectedCohort]);

  const fetchStudentsByBatch = useCallback(async (batchId) => {
    if (!batchId) return;
    setLoadingView(true);
    try {
      const response = await axios.get(`${BACKEND}/api/batches/${batchId}/students`);
      setStudents(response.data || []);
    } catch (err) {
      console.error("Error fetching students", err);
      setStudents([]);
    } finally {
      setLoadingView(false);
    }
  }, []);

  const fetchTeacherDetails = useCallback(async (teacherId) => {
    if (!teacherId) return;
    try {
      const response = await axios.get(`${BACKEND}/api/teachers/${teacherId}/details`);
      setTeacherDetails(response.data);
    } catch (err) {
      console.error("Error fetching teacher details", err);
      setTeacherDetails(null);
    }
  }, []);

  const handleAssignTeacher = async (e) => {
    e.preventDefault();
    try{
      await axios.post(`${BACKEND}/api/subjects/assign-teacher`, assignData);
      setShowAssignModal(false);
      setAssignData({ subject_id: "", teacher_id: "" });
      alert("Teacher assigned to subject successfully");
      loadInitialData();
    } catch(err) {
      alert("Failed to assign teacher: " + err.response?.data?.error || err.message);
    }
  }

  const handleViewClassroom = async (classroom) => {
    setSelectedClassroom(classroom);
    setViewMode(true);
    
    const primaryBatchId = classroom.batch_ids && classroom.batch_ids.length > 0 
      ? classroom.batch_ids[0] 
      : classroom.batch_id;

    if (primaryBatchId) {
      await fetchStudentsByBatch(primaryBatchId);
    }
    
    if (classroom.teacher_id) {
      await fetchTeacherDetails(classroom.teacher_id);
    }
  };

  const closeViewModal = () => {
    setViewMode(false);
    setSelectedClassroom(null);
    setStudents([]);
    setTeacherDetails(null);
    setStudentSearch("");
  };

  // --------------------------
  // Auto-generation Logic
  // --------------------------
  useEffect(() => {
    const subject = subjects.find(s => String(s.subject_id) === String(formData.subject_id));
    const teacher = teachers.find(t => String(t.teacher_id) === String(formData.teacher_id));
    
    const selectedBatchObjs = batches.filter(b => selectedBatches.includes(String(b.batch_id)));

    if (subject && teacher && selectedBatchObjs.length > 0) {
      const tName = teacher.teacher_name.split(/[@ ]/)[0].toUpperCase();
      const code = subject.subject_code || "SUB";
      
      const formattedBatches = selectedBatchObjs.map(batch => {
        const rawName = batch.batch_name || "";
        const numMatch = rawName.match(/\d+/); 
        
        if (numMatch) {
          const paddedNum = numMatch[0].padStart(2, '0');
          return `B${paddedNum}`;
        }
        
        return rawName.replace(/BATCH-?/i, "B").replace(/BATCH /i, "B").toUpperCase();
      });
      
      let bNames = "";
      if (formattedBatches.length === 1) {
        bNames = formattedBatches[0];
      } else if (formattedBatches.length === 2) {
        bNames = formattedBatches.join("&");
      } else {
        const allButLast = formattedBatches.slice(0, -1).join(",");
        const lastItem = formattedBatches[formattedBatches.length - 1];
        bNames = `${allButLast}&${lastItem}`;
      }
      
      setAutoName(`${code}-${tName}-${bNames}`);
    } else {
      setAutoName("");
    }
  }, [formData.subject_id, formData.teacher_id, selectedBatches, subjects, teachers, batches]);

  // --------------------------
  // Batch Pill Handlers
  // --------------------------
  const handleBatchToggle = (batchId) => {
    const id = String(batchId);
    setSelectedBatches(prev => 
      prev.includes(id) 
        ? prev.filter(b => b !== id) 
        : [...prev, id]
    );
  };

  const handleSelectAllBatches = () => {
    if (selectedBatches.length === batches.length) {
      setSelectedBatches([]); 
    } else {
      setSelectedBatches(batches.map(b => String(b.batch_id))); 
    }
  };

  // --------------------------
  // Form Handlers
  // --------------------------
  const handleSubmit = async (e) => {
    e.preventDefault();
    const payload = { 
        ...formData, 
        classroom_name: autoName, 
        batch_ids: selectedBatches 
    };

    try {
      if (isEditing) {
        await axios.put(`${API_BASE}/${editingId}`, payload);
      } else {
        await axios.post(`${API_BASE}`, payload);
      }
      setShowForm(false);
      resetForm();
      loadInitialData();
      alert(`Classroom ${isEditing ? 'updated' : 'created'} successfully`);
    } catch (err) {
      alert("Error saving classroom details: " + err.response?.data?.error || err.message);
    }
  };

  const handleEditClick = (c) => {
    setIsEditing(true);
    setEditingId(c.classroom_id);
    setFormData({
      subject_id: c.subject_id,
      teacher_id: c.teacher_id,
      platform_id: c.platform_id,
      class_link: c.class_link || "",
      active_yn: c.active_yn || "Y",
      created_by: c.created_by,
      updated_by: 1
    });
    
    setSelectedCohort(c.cohort_number);
    setSelectedBatches(c.batch_ids ? c.batch_ids.map(String) : (c.batch_id ? [String(c.batch_id)] : []));
    setShowForm(true);
  };

  const handleDeleteClassroom = async (classroomId) => {
    const confirmDelete = window.confirm(
      "Are you sure you want to delete this classroom?"
    );
    if (!confirmDelete) return;

    try {
      await axios.delete(`${API_BASE}/${classroomId}`);
      loadInitialData();
      alert("Classroom deleted successfully");
    } catch (err) {
      console.error("Delete failed", err);
      alert("Failed to delete classroom");
    }
  };

  const resetForm = () => {
    setFormData(INITIAL_FORM_STATE);
    setSelectedBatches([]); 
    setSelectedCohort("");
    setIsEditing(false);
    setEditingId(null);
    setAutoName("");
  };

  // Reset to page 1 when searching
  useEffect(() => {
    setCurrentPage(1);
  }, [search]);

  // --------------------------
  // Data Filtering & Pagination
  // --------------------------
  const filteredRooms = classrooms.filter(c => 
    c.classroom_name?.toLowerCase().includes(search.toLowerCase()) ||
    c.teacher_name?.toLowerCase().includes(search.toLowerCase()) ||
    c.subject_name?.toLowerCase().includes(search.toLowerCase())
  );

  const indexOfLastRow = currentPage * rowsPerPage;
  const indexOfFirstRow = indexOfLastRow - rowsPerPage;
  const currentRooms = filteredRooms.slice(indexOfFirstRow, indexOfLastRow);
  const totalPages = Math.ceil(filteredRooms.length / rowsPerPage);

  const filteredStudents = students.filter(student =>
    student.student_name?.toLowerCase().includes(studentSearch.toLowerCase()) ||
    student.student_email?.toLowerCase().includes(studentSearch.toLowerCase()) ||
    student.student_id?.toString().includes(studentSearch)
  );

  return (
    <div className={styles.fullPageContainer}>
      {/* HEADER SECTION */}
      <div className={styles.pageHeader}>
        <div className={styles.headerLeft}>
          <img src={Logo} alt="Logo" className={styles.logo} />
          <div>
            <h1 className={styles.title}>Classroom Management</h1>
            <p className={styles.subtitle}>Managing {classrooms.length} active learning environments</p>
          </div>
        </div>
      </div>

      {/* FILTER & ACTIONS */}
      <div className={styles.filterBar}>
        <div className={styles.searchBox}>
          <Search className={styles.searchIcon} size={18} />
          <input 
            placeholder="Search Classroom, Teacher or Subject..." 
            value={search} 
            onChange={e => setSearch(e.target.value)} 
            className={styles.searchInput}
          />
        </div>
        <button className={`${styles.btn} ${styles.btnPrimary}`} onClick={() => { resetForm(); setShowForm(true); }}>
          <Plus size={18} /> Create Classroom
        </button>
      </div>

      {/* CLASSROOM TABLE */}
      <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead className={styles.thead}>
            <tr>
              <th className={styles.th}>Classroom Name</th>
              <th className={styles.th}>Subject</th>
              <th className={styles.th}>Teacher</th>
              <th className={styles.th}>Platform</th>
              <th className={styles.th}>Status</th>
              <th className={styles.thCenter}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {currentRooms.length > 0 ? (
              currentRooms.map(c => (
                <tr key={c.classroom_id} className={styles.tr}>
                  <td className={styles.tdBold}>{c.classroom_name}</td>
                  <td className={styles.td}>{c.subject_name}</td>
                  <td className={styles.td}>{c.teacher_name}</td>
                  <td className={styles.td}><span className={styles.badgeRoom}>{c.platform_name}</span></td>
                  <td className={styles.td}>
                    <span className={`${styles.statusPill} ${c.active_yn === 'Y' ? styles.statusActive : styles.statusInactive}`}>
                      {c.active_yn === 'Y' ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td className={styles.tdCenter}>
                    <div className={styles.actionGroup}>
                      <button 
                        onClick={() => handleViewClassroom(c)} 
                        className={`${styles.actionBtn} ${styles.textEmerald}`}
                        title="View Details"
                      >
                        <Eye size={16}/>
                      </button>
                      <button 
                        onClick={() => handleEditClick(c)} 
                        className={`${styles.actionBtn} ${styles.textBlue}`}
                        title="Edit"
                      >
                        <Edit size={16}/>
                      </button>
                      <button 
                        onClick={() => handleDeleteClassroom(c.classroom_id)} 
                        className={`${styles.actionBtn} ${styles.textRed} ${styles.btnDelete}`}
                        title="Delete Classroom"
                      >
                        <Trash2 size={16}/>
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan="6" style={{ textAlign: "center", padding: "30px", color: "#64748b" }}>
                  No classrooms found matching "{search}"
                </td>
              </tr>
            )}
          </tbody>
        </table>

        {/* PAGINATION CONTROLS */}
        {filteredRooms.length > 0 && (
          <div className={styles.paginationContainer}>
            <div className={styles.pageInfo}>
              Showing <strong>{indexOfFirstRow + 1}</strong> to <strong>{Math.min(indexOfLastRow, filteredRooms.length)}</strong> of <strong>{filteredRooms.length}</strong> classrooms
            </div>
            
            <div className={styles.paginationControls}>
              <div className={styles.rowsSelector}>
                <span className={styles.pageText}>Rows per page:</span>
                <select 
                  value={rowsPerPage} 
                  onChange={(e) => {
                    setRowsPerPage(Number(e.target.value));
                    setCurrentPage(1);
                  }}
                  className={styles.rowsSelect}
                >
                  <option value={5}>5</option>
                  <option value={10}>10</option>
                  <option value={20}>20</option>
                  <option value={50}>50</option>
                </select>
              </div>

              <div className={styles.pageButtons}>
                <button 
                  className={styles.pageBtn} 
                  onClick={() => setCurrentPage(prev => Math.max(prev - 1, 1))}
                  disabled={currentPage === 1}
                >
                  <ChevronLeft size={16} />
                </button>
                <span className={styles.pageText}>Page {currentPage} of {totalPages}</span>
                <button 
                  className={styles.pageBtn} 
                  onClick={() => setCurrentPage(prev => Math.min(prev + 1, totalPages))}
                  disabled={currentPage === totalPages}
                >
                  <ChevronRight size={16} />
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* VIEW CLASSROOM MODAL */}
      {viewMode && selectedClassroom && (
        <div className={styles.modalOverlay}>
          <div className={styles.modalContent}>
            
            {/* Modal Header */}
            <div className={styles.modalHeader}>
              <h3 className={styles.modalTitle}>Classroom Details</h3>
              <button onClick={closeViewModal} className={styles.closeBtn}>
                <X size={24} />
              </button>
            </div>

            {/* Classroom Info Cards */}
            <div className={styles.infoGrid}>
              <div className={styles.infoCard}>
                <div className={styles.infoCardHeader}>
                  <BookOpen size={20} className={styles.textBlue} />
                  <h4 className={styles.infoCardTitle}>Subject</h4>
                </div>
                <p className={styles.infoCardValue}>{selectedClassroom.subject_name}</p>
              </div>

              <div className={styles.infoCard}>
                <div className={styles.infoCardHeader}>
                  <Presentation size={20} className={styles.textBlue} />
                  <h4 className={styles.infoCardTitle}>Platform</h4>
                </div>
                <p className={styles.infoCardValue}>{selectedClassroom.platform_name}</p>
              </div>

              <div className={styles.infoCard}>
                <div className={styles.infoCardHeader}>
                  <Calendar size={20} className={styles.textBlue} />
                  <h4 className={styles.infoCardTitle}>Status</h4>
                </div>
                <span className={`${styles.statusPill} ${selectedClassroom.active_yn === 'Y' ? styles.statusActive : styles.statusInactive}`}>
                  {selectedClassroom.active_yn === 'Y' ? 'Active' : 'Inactive'}
                </span>
              </div>

              {selectedClassroom.class_link && (
                <div className={`${styles.infoCard} ${styles.infoCardSpan2}`}>
                  <div className={styles.infoCardHeader}>
                    <LinkIcon size={20} className={styles.textBlue} />
                    <h4 className={styles.infoCardTitle}>Class Link</h4>
                  </div>
                  <a href={selectedClassroom.class_link} target="_blank" rel="noopener noreferrer" className={styles.link}>
                    {selectedClassroom.class_link}
                  </a>
                </div>
              )}
            </div>

            {/* Teacher Information Section */}
            <div style={{ marginBottom: '30px' }}>
              <h3 className={styles.sectionTitle}>
                <User size={20} /> Teacher Information
              </h3>
              
              {teacherDetails ? (
                <div className={styles.teacherInfoBox}>
                  <div className={styles.teacherGrid}>
                    <div>
                      <p className={styles.teacherLabel}>Name</p>
                      <p className={styles.teacherValue}>{teacherDetails.teacher_name || selectedClassroom.teacher_name}</p>
                    </div>
                    <div>
                      <p className={styles.teacherLabel}>Email</p>
                      <p className={styles.teacherValueFlex}>
                        <Mail size={14} /> {teacherDetails.email || 'N/A'}
                      </p>
                    </div>
                    <div>
                      <p className={styles.teacherLabel}>Phone</p>
                      <p className={styles.teacherValueFlex}>
                        <Phone size={14} /> {teacherDetails.phone || 'N/A'}
                      </p>
                    </div>
                    <div>
                      <p className={styles.teacherLabel}>Specialization</p>
                      <p className={styles.teacherValue}>{teacherDetails.specialization || selectedClassroom.subject_name}</p>
                    </div>
                  </div>
                </div>
              ) : (
                <div className={styles.teacherInfoBoxSimple}>
                  <p style={{ margin: 0 }}>Teacher: <strong>{selectedClassroom.teacher_name}</strong></p>
                </div>
              )}
            </div>

            {/* Students Information Section */}
            <div>
              <div className={styles.studentsHeader}>
                <h3 className={styles.sectionTitle} style={{ marginBottom: 0 }}>
                  <Users size={20} /> Students ({filteredStudents.length})
                </h3>
                
                <div className={styles.studentsSearchBox}>
                  <Search className={styles.searchIcon} style={{ top: '8px' }} size={18} />
                  <input 
                    placeholder="Search students..." 
                    value={studentSearch} 
                    onChange={e => setStudentSearch(e.target.value)} 
                    className={styles.studentsSearchInput}
                  />
                </div>
              </div>

              {loadingView ? (
                <div className={styles.spinnerContainer}>
                  <div className={styles.spinner}></div>
                  <p>Loading students...</p>
                </div>
              ) : (
                <div className={styles.tableWrapper} style={{ boxShadow: 'none' }}>
                  <table className={styles.table}>
                    <thead className={styles.thead}>
                      <tr>
                        <th className={styles.th}>Student ID</th>
                        <th className={styles.th}>Name</th>
                        <th className={styles.th}>Email</th>
                        <th className={styles.th}>Contact</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredStudents.length > 0 ? (
                        filteredStudents.map((student, index) => (
                          <tr key={student.student_id || index} className={styles.tr}>
                            <td className={styles.td} style={{ fontWeight: '500' }}>{student.student_id}</td>
                            <td className={styles.td}>{student.student_name}</td>
                            <td className={styles.td}>
                              <div className={styles.iconLabel}>
                                <Mail size={14} color="#64748b" /> {student.student_email || 'N/A'}
                              </div>
                            </td>
                            <td className={styles.td}>
                              <div className={styles.iconLabel}>
                                <Phone size={14} color="#64748b" /> {student.contact_no1 || 'N/A'}
                              </div>
                            </td>
                          </tr>
                        ))
                      ) : (
                        <tr>
                          <td colSpan="4" className={styles.emptyState}>
                            {studentSearch ? 'No students match your search' : 'No students found'}
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className={styles.modalFooter}>
              <button className={`${styles.btn} ${styles.btnSecondary}`} onClick={closeViewModal}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* FORM MODAL */}
      {showForm && (
        <div className={styles.modalOverlay}>
          <div className={`${styles.modalContent} ${styles.modalContentSmall}`}>
            <div className={styles.modalHeaderSmall}>
              <h3 className={styles.modalTitleSmall}>{isEditing ? "Update Classroom" : "Create New Classroom"}</h3>
              <button onClick={() => setShowForm(false)} className={styles.closeBtn}><X /></button>
            </div>
            
            <form onSubmit={handleSubmit} className={styles.formGrid}>
              <div className={styles.formGroup}>
                <label className={styles.formLabel}><BookOpen size={14}/> Subject</label>
                <select value={formData.subject_id} onChange={e => setFormData({...formData, subject_id: e.target.value})} className={styles.formInput} required>
                  <option value="">Select Subject</option>
                  {subjects.map(s => <option key={s.subject_id} value={s.subject_id}>{s.subject_name}</option>)}
                </select>
              </div>

              <div className={styles.formGroup}>
                <label className={styles.formLabel}><Presentation size={14}/> Teacher</label>
                <select value={formData.teacher_id} onChange={e => setFormData({...formData, teacher_id: e.target.value})} className={styles.formInput} required disabled={!formData.subject_id}>
                  <option value="">Select Teacher</option>
                  {teachers.map(t => <option key={t.teacher_id} value={t.teacher_id}>{t.teacher_name}</option>)}
                </select>
              </div>

              <div className={styles.formGroup}>
                <label className={styles.formLabel}><Filter size={14}/> Cohort</label>
                <select 
                  value={selectedCohort} 
                  onChange={e => {
                    setSelectedCohort(e.target.value);
                    setSelectedBatches([]); 
                  }} 
                  className={styles.formInput}
                >
                  <option value="">Select Cohort</option>
                  {cohorts.map(c => <option key={c.cohort_number} value={c.cohort_number}>{c.cohort_name}</option>)}
                </select>
              </div>

              <div className={styles.formGroup}>
                <label className={styles.formLabel}>Status</label>
                <select 
                  value={formData.active_yn} 
                  onChange={e => setFormData({...formData, active_yn: e.target.value})} 
                  className={styles.formInput} 
                  required
                >
                  <option value="Y">Active</option>
                  <option value="N">Inactive</option>
                </select>
              </div>

              {/* Clickable Pills for Batches - Spans 2 Columns */}
              <div className={`${styles.formGroup} ${styles.formGroupSpan2}`}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                  <label className={styles.formLabel} style={{ margin: 0 }}>
                    <Layout size={14}/> Select Batches
                  </label>
                  {batches.length > 0 && (
                    <button 
                      type="button" 
                      onClick={handleSelectAllBatches}
                      style={{ background: 'none', border: 'none', color: '#3b82f6', fontSize: '12px', cursor: 'pointer', fontWeight: '500' }}
                    >
                      {selectedBatches.length === batches.length ? 'Deselect All' : 'Select All'}
                    </button>
                  )}
                </div>
                
                {!selectedCohort ? (
                  <div className={styles.formInput} style={{ color: '#94a3b8', fontStyle: 'italic', display: 'flex', alignItems: 'center', minHeight: '42px', backgroundColor: '#f8fafc' }}>
                    Please select a cohort first...
                  </div>
                ) : batches.length === 0 ? (
                  <div className={styles.formInput} style={{ color: '#ef4444', fontStyle: 'italic', display: 'flex', alignItems: 'center', minHeight: '42px', backgroundColor: '#fef2f2' }}>
                    No batches found for this cohort.
                  </div>
                ) : (
                  <div className={styles.batchPillContainer}>
                    {batches.map(b => {
                      const isSelected = selectedBatches.includes(String(b.batch_id));
                      return (
                        <button
                          key={b.batch_id}
                          type="button"
                          onClick={() => handleBatchToggle(b.batch_id)}
                          className={`${styles.batchPill} ${isSelected ? styles.batchPillActive : ''}`}
                        >
                          {isSelected && <CheckCircle size={14} style={{ marginRight: '4px' }} />}
                          {b.batch_name}
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>

              <div className={styles.formGroup}>
                <label className={styles.formLabel}>Platform</label>
                <select value={formData.platform_id} onChange={e => setFormData({...formData, platform_id: e.target.value})} className={styles.formInput} required>
                  <option value="">Select Platform</option>
                  {platforms.map(p => <option key={p.platform_id} value={p.platform_id}>{p.platform_name}</option>)}
                </select>
              </div>

              <div className={styles.formGroup}>
                <label className={styles.formLabel}>Class Link</label>
                <input type="url" placeholder="https://..." value={formData.class_link} onChange={e => setFormData({...formData, class_link: e.target.value})} className={styles.formInput} />
              </div>

              <div className={`${styles.formGroup} ${styles.formGroupSpan2}`}>
                <div className={styles.namePreviewBox}>
                  <span className={styles.namePreviewLabel}>Target Classroom Name:</span>
                  <strong className={styles.namePreviewValue}>{autoName || "Pending selection..."}</strong>
                </div>
              </div>

              <div className={styles.formActions}>
                <button type="button" className={`${styles.btn} ${styles.btnSecondary}`} onClick={() => setShowForm(false)}>Discard</button>
                <button type="submit" className={`${styles.btn} ${styles.btnPrimary}`} disabled={!autoName}>
                  <CheckCircle size={18} /> {isEditing ? "Update Classroom" : "Create Classroom"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}