import React, { useState, useEffect } from "react";
import { useParams, Link } from "react-router-dom";
import axios from "axios";
import { Search, Tablet, Plus, Download, AlertCircle, Check, Clock, Zap, X, MoreVertical, Upload, FileSpreadsheet, ChevronLeft, ChevronRight, UserCog, GraduationCap } from "lucide-react"; // Added Search and other icons here
import styles from "./EventDetailsPage.module.css";
import Breadcrumbs from "../../../components/Breadcrumbs/Breadcrumbs";
import { useAuth } from "../../../contexts/AuthContext";

const EventDetailsPage = () => {
  const { eventId } = useParams();
  const isUpdateMode = new URLSearchParams(window.location.search).get("mode") === "update";
  const { user } = useAuth();
  
  const [event, setEvent] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Big View States
  const [selectedBigImage, setSelectedBigImage] = useState(null);
  const [selectedBigPDF, setSelectedBigPDF] = useState(null);

  const API_BASE_URL = process.env.REACT_APP_BACKEND_API_URL;

  // Breadcrumbs Logic
  const currentPath = ['Admin', 'Academics', 'Events', 'View'];

  // Attendance States
  const [sammelanEvents, setSammelanEvents] = useState([]);
  const [selectedEventTitle, setSelectedEventTitle] = useState("");
  const [states, setStates] = useState([]);
  const [selectedState, setSelectedState] = useState("");
  const [divisions, setDivisions] = useState([]);
  const [selectedDivision, setSelectedDivision] = useState("");
  const [availableDistricts, setAvailableDistricts] = useState([]);
  const [selectedDistricts, setSelectedDistricts] = useState([]);
  const [availableBlocks, setAvailableBlocks] = useState([]);
  const [selectedBlocks, setSelectedBlocks] = useState([]);
  
  // Student Table & Search
  const [students, setStudents] = useState([]);
  const [searchTerm, setSearchTerm] = useState("");
  const [presentStudentIds, setPresentStudentIds] = useState([]);
  const [parentsAttended, setParentsAttended] = useState(0);
  const [currentPage, setCurrentPage] = useState(1);
  const [hasNextPage, setHasNextPage] = useState(false);

  // Multimedia
  const [selectedPhotos, setSelectedPhotos] = useState([]);
  const [selectedReports, setSelectedReports] = useState([]);
  const [isSaving, setIsSaving] = useState(false);
  const [attendanceSaved, setAttendanceSaved] = useState(false);

  /* =========================================================
      Fetch Event Details
  ========================================================= */
  useEffect(() => {
    if (!eventId || eventId === "attendance" || eventId === "manage") {
      setLoading(false);
      return;
    }
    const fetchEvent = async () => {
      try {
        setLoading(true);
        const res = await axios.get(`${API_BASE_URL}/api/events/${eventId}`);
        const eventData = res.data;
        
        // Map Photos
        eventData.event_photos = Array.isArray(eventData.photos)
          ? eventData.photos.map((p) => {
              if (!p.file_path) return null;
              const fileName = p.file_path.replace(/\\/g, "/").split("/").pop();
              return `${API_BASE_URL}/uploads/events/photos/${fileName}`;
            }).filter(Boolean) : [];

        // Map Reports (PDFs)
        eventData.event_report_files = Array.isArray(eventData.reports)
          ? eventData.reports.map((r) => {
              if (!r.file_path) return null;
              const fileName = r.file_path.replace(/\\/g, "/").split("/").pop();
              return `${API_BASE_URL}/uploads/events/reports/${fileName}`;
            }).filter(Boolean) : [];

        setEvent(eventData);
      } catch (err) {
        setError("Could not load event details.");
      } finally {
        setLoading(false);
      }
    };
    fetchEvent();
  }, [eventId, API_BASE_URL]);

  /* =========================================================
      Attendance Management Logic (Keep Same)
  ========================================================= */
  useEffect(() => {
    if (!isUpdateMode) return;
    axios.get(`${API_BASE_URL}/api/attendance/sammelan-list`).then((res) => setSammelanEvents(res.data?.data || []));
    axios.get(`${API_BASE_URL}/api/attendance/jurisdictions?type=state`).then((res) => setStates(res.data?.data || []));
  }, [isUpdateMode, API_BASE_URL]);

  useEffect(() => {
    if (selectedState) {
      axios.get(`${API_BASE_URL}/api/attendance/jurisdictions?type=division&stateName=${selectedState}`)
        .then((res) => {
            setDivisions(res.data?.data || []);
            setSelectedDivision("");
            setAvailableDistricts([]);
            setSelectedDistricts([]);
        });
    }
  }, [selectedState, API_BASE_URL]);

  useEffect(() => {
    if (selectedDivision) {
      axios.get(`${API_BASE_URL}/api/attendance/jurisdictions`, { params: { type: "district", divisionNames: [selectedDivision] } })
        .then((res) => { 
            setAvailableDistricts(res.data?.data || []); 
            setSelectedDistricts([]); 
            setAvailableBlocks([]); 
            setSelectedBlocks([]);
        });
    }
  }, [selectedDivision, API_BASE_URL]);

  useEffect(() => {
    if (selectedDistricts.length > 0) {
      const districtNames = selectedDistricts.map((d) => d.juris_name);
      axios.get(`${API_BASE_URL}/api/attendance/jurisdictions`, { 
          params: { type: "block", stateName: selectedState, divisionNames: [selectedDivision], districtNames: districtNames } 
      })
      .then((res) => setAvailableBlocks(res.data?.data || []));
    } else {
      setAvailableBlocks([]);
      setSelectedBlocks([]);
    }
  }, [selectedDistricts, selectedState, selectedDivision, API_BASE_URL]);

  const moveToSelected = (item, type) => {
    if (type === "district") {
      setAvailableDistricts(prev => prev.filter(i => i.juris_code !== item.juris_code));
      setSelectedDistricts(prev => [...prev, item]);
    } else {
      setAvailableBlocks(prev => prev.filter(i => i.juris_code !== item.juris_code));
      setSelectedBlocks(prev => [...prev, item]);
    }
  };

  const moveToAvailable = (item, type) => {
    if (type === "district") {
      setSelectedDistricts(prev => prev.filter(i => i.juris_code !== item.juris_code));
      setAvailableDistricts(prev => [...prev, item]);
    } else {
      setSelectedBlocks(prev => prev.filter(i => i.juris_code !== item.juris_code));
      setAvailableBlocks(prev => [...prev, item]);
    }
  };

  const handleSearchStudents = async (pageNumber = 1) => {
    if (!selectedEventTitle) return alert("Please select a Sammelan Event.");
    try {
      const res = await axios.post(`${API_BASE_URL}/api/attendance/students-list`, {
          eventTitle: selectedEventTitle,
          stateName: selectedState || null,
          districtNames: selectedDistricts.length > 0 ? selectedDistricts.map(d => d.juris_name) : null,
          blockNames: selectedBlocks.length > 0 ? selectedBlocks.map(b => b.juris_name) : null,
          page: pageNumber,
      });
      const data = res.data?.data || [];
      setStudents(data);
      setCurrentPage(pageNumber);
      setHasNextPage(data.length === 15);
    } catch (err) {
      alert("Failed to load students.");
    }
  };

  const filteredStudents = students.filter(s => 
    s.student_name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const handleCheckAll = () => {
    const visibleIds = filteredStudents.map(s => s.student_id);
    setPresentStudentIds(prev => Array.from(new Set([...prev, ...visibleIds])));
  };

  const handleUncheckAll = () => {
    const visibleIds = filteredStudents.map(s => s.student_id);
    setPresentStudentIds(prev => prev.filter(id => !visibleIds.includes(id)));
  };

  const handleSaveAttendance = async () => {
    const actualId = eventId === "attendance" || !eventId 
      ? sammelanEvents.find(e => e.event_title === selectedEventTitle)?.event_id 
      : eventId;

    if (!actualId) return alert("Select an event first.");

    if (!attendanceSaved) {
      if (window.confirm(`Confirm attendance for ${presentStudentIds.length} students?`)) {
        setAttendanceSaved(true);
      }
      return;
    }

    const formData = new FormData();
    formData.append("eventId", actualId);
    formData.append("eventTitle", selectedEventTitle);
    formData.append("studentIds", JSON.stringify(presentStudentIds));
    formData.append("parents_attended", parentsAttended);
    
    // 3. Append the user_id for tracking
    formData.append("user_id", user?.user_id || ""); 

    selectedPhotos.forEach(file => formData.append("photos", file));
    selectedReports.forEach(file => formData.append("reports", file));

    try {
      setIsSaving(true);
      await axios.post(`${API_BASE_URL}/api/attendance/save`, formData);
      alert("Attendance saved and counts updated successfully!");
      setAttendanceSaved(false);
      setStudents([]);
      setPresentStudentIds([]);
    } catch (err) {
      alert("Error saving: " + (err.response?.data?.msg || "Server Error"));
    } finally {
      setIsSaving(false);
    }
  };

  const formatDate = (date) => {
    if (!date) return "N/A";
    return new Date(date).toLocaleDateString("en-IN", { day: "numeric", month: "long", year: "numeric" });
  };

  if (loading) return <div className={styles.loadingContainer}><div className={styles.spinner}></div></div>;

  return (
    <div className={styles.pageWrapper}>
      {/* BIG IMAGE OVERLAY */}
      {selectedBigImage && (
        <div className={styles.modalOverlay} onClick={() => setSelectedBigImage(null)}>
          <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
            <button className={styles.modalClose} onClick={() => setSelectedBigImage(null)}>&times;</button>
            <img src={selectedBigImage} alt="Large View" className={styles.modalImg} />
          </div>
        </div>
      )}

      {/* BIG PDF OVERLAY */}
      {selectedBigPDF && (
        <div className={styles.modalOverlay}>
          <div className={styles.pdfModalContent}>
            <div className={styles.pdfModalHeader}>
              <h3>Event Report Preview</h3>
              <button className={styles.pdfCloseBtn} onClick={() => setSelectedBigPDF(null)}>&times;</button>
            </div>
            <iframe src={selectedBigPDF} title="PDF Preview" width="100%" height="90%"></iframe>
          </div>
        </div>
      )}

      <div className={styles.container}>
        
        {/* ADD BREADCRUMBS HERE */}
        <Breadcrumbs path={currentPath} nonLinkSegments={['Admin', 'Academics', 'View']} />

        {isUpdateMode ? (
          /* ATTENDANCE SECTION */
          <div className={styles.attendanceSammelanSection}>
             <div className={styles.topBar}>
              <Link to="/admin/academics/events" className={styles.backLink}>← Back</Link>
              <h2 className={styles.sectionTitle}>Sammelan Attendance Tracking</h2>
            </div>

            <div className={styles.filterGrid}>
              <div className={styles.formGroup}>
                <label>1. Select Sammelan Event</label>
                <select value={selectedEventTitle} onChange={(e) => setSelectedEventTitle(e.target.value)}>
                  <option value="">-- Choose Event --</option>
                  {sammelanEvents.map((e) => <option key={e.event_id} value={e.event_title}>{e.event_title}</option>)}
                </select>
              </div>
              <div className={styles.formGroup}>
                <label>2. Select State</label>
                <select value={selectedState} onChange={(e) => setSelectedState(e.target.value)}>
                  <option value="">-- State --</option>
                  {states.map((s) => <option key={s.juris_code} value={s.juris_name}>{s.juris_name}</option>)}
                </select>
              </div>
              <div className={styles.formGroup}>
                <label>3. Select Division</label>
                <select value={selectedDivision} onChange={(e) => setSelectedDivision(e.target.value)} disabled={!selectedState}>
                  <option value="">-- Division --</option>
                  {divisions.map((d) => <option key={d.juris_code} value={d.juris_name}>{d.juris_name}</option>)}
                </select>
              </div>
            </div>

            <div className={styles.dualBoxContainer}>
              <div className={styles.box}>
                <h4>Available Districts</h4>
                <ul>{availableDistricts.map((d) => <li key={d.juris_code} onClick={() => moveToSelected(d, "district")}>{d.juris_name} +</li>)}</ul>
              </div>
              <div className={styles.box}>
                <h4>Selected Districts</h4>
                <ul>{selectedDistricts.map((d) => <li key={d.juris_code} onClick={() => moveToAvailable(d, "district")}>{d.juris_name} ×</li>)}</ul>
              </div>
            </div>

            <div className={styles.dualBoxContainer} style={{ marginTop: "20px" }}>
              <div className={styles.box}>
                <h4>Available Blocks</h4>
                <ul>{availableBlocks.map((b) => <li key={b.juris_code} onClick={() => moveToSelected(b, "block")}>{b.juris_name} +</li>)}</ul>
              </div>
              <div className={styles.box}>
                <h4>Selected Blocks</h4>
                <ul>{selectedBlocks.map((b) => <li key={b.juris_code} onClick={() => moveToAvailable(b, "block")}>{b.juris_name} ×</li>)}</ul>
              </div>
            </div>

            <button className={styles.primaryButton} onClick={() => handleSearchStudents(1)}>Search Students</button>

            {students.length > 0 && (
              <div className={styles.studentListSection}>
                <div className={styles.tableActionsBar}>
                  <div className={styles.searchContainer}>
                    <input 
                        type="text" 
                        placeholder="🔍 Search student name (Auto-filter)..." 
                        value={searchTerm} 
                        onChange={(e) => setSearchTerm(e.target.value)} 
                        className={styles.searchBar}
                    />
                  </div>
                  <div className={styles.bulkActions}>
                    <button onClick={handleCheckAll} className={styles.bulkBtn}>Check Visible</button>
                    <button onClick={handleUncheckAll} className={styles.bulkBtn}>Uncheck Visible</button>
                  </div>
                </div>

                <table className={styles.studentTable}>
                  <thead><tr><th>Mark</th><th>Student Name</th><th>District</th><th>Block</th></tr></thead>
                  <tbody>
                    {filteredStudents.length > 0 ? (
                      filteredStudents.map((s) => (
                        <tr key={s.student_id}>
                          <td>
                            <input type="checkbox" checked={presentStudentIds.includes(s.student_id)}
                              onChange={(e) => {
                                if (e.target.checked) setPresentStudentIds(prev => [...prev, s.student_id]);
                                else setPresentStudentIds(prev => prev.filter(id => id !== s.student_id));
                              }} 
                            />
                          </td>
                          <td>{s.student_name}</td><td>{s.district_name}</td><td>{s.block_name}</td>
                        </tr>
                      ))
                    ) : (
                      <tr><td colSpan="4" style={{textAlign:'center', padding:'20px'}}>No matching students found for "{searchTerm}"</td></tr>
                    )}
                  </tbody>
                </table>

                <div className={styles.paginationControls}>
                  <button className={styles.pageBtn} onClick={() => handleSearchStudents(currentPage - 1)} disabled={currentPage === 1}>Prev</button>
                  <span className={styles.pageInfo}>Page {currentPage}</span>
                  <button className={styles.pageBtn} onClick={() => handleSearchStudents(currentPage + 1)} disabled={!hasNextPage}>Next</button>
                </div>

                <div className={styles.tableFooter}>
                  <span>Marked: <strong>{presentStudentIds.length}</strong> students</span>
                  {!attendanceSaved ? (
                    <button className={styles.saveBtn} onClick={handleSaveAttendance}>Confirm Student List</button>
                  ) : (
                    <div className={styles.finalStepContainer}>
                      <div className={styles.formGroup} style={{marginBottom: '20px'}}>
                        <label>How many Parents attended?</label>
                        <input type="number" value={parentsAttended} onChange={(e) => setParentsAttended(e.target.value)} className={styles.inputField} min="0" />
                      </div>
                      <div className={styles.uploadSection}>
                        <div className={styles.uploadCard}>
                          <h4>📸 Sammelan Photos (Max 4)</h4>
                          <input type="file" multiple accept="image/*" onChange={(e) => setSelectedPhotos(Array.from(e.target.files).slice(0, 4))} />
                        </div>
                        <div className={styles.uploadCard}>
                          <h4>📄 Event Report (PDF)</h4>
                          <input type="file" accept=".pdf" onChange={(e) => setSelectedReports(Array.from(e.target.files).slice(0, 1))} />
                        </div>
                      </div>
                      <div className={styles.actionRow}>
                        <button className={styles.saveBtn} onClick={handleSaveAttendance} disabled={isSaving}>
                            {isSaving ? "Saving..." : "Submit & Update Counts"}
                        </button>
                        <button className={styles.backLink} onClick={() => setAttendanceSaved(false)} style={{border:'none', background:'none', cursor:'pointer', marginTop:'10px'}}>← Back to List</button>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        ) : (
          /* CLEAN VIEW MODE */
          <div className={styles.viewContent}>
            <div className={styles.topBar}>
              <Link to="/admin/academics/events" className={styles.backLink}>← Back</Link>
              <Link to={`/admin/academics/events/${eventId}/edit`} className={styles.editButton}>Edit Event</Link>
            </div>
            
            <div className={styles.hero}>
              <span className={styles.eventTypeBadge}>{event?.event_type_name}</span>
              <h1 className={styles.mainTitle}>{event?.event_title}</h1>
              <div className={styles.heroMetaGrid}>
                <div className={styles.metaItem}>📅 <strong>Start:</strong> {formatDate(event?.event_start_date)}</div>
                <div className={styles.metaItem}>⌛ <strong>End:</strong> {formatDate(event?.event_end_date)}</div>
                <div className={styles.metaItem}>📍 <strong>Location:</strong> {event?.event_location}</div>
              </div>
            </div>

            <div className={styles.detailsGrid}>
              <div className={styles.descriptionSection}>
                <h3>Description</h3>
                <p className={styles.descriptionText}>{event?.event_description || "No description provided."}</p>
                
                {/* CONDITIONAL PDF VIEW FOR SAMMELAN */}
                {event?.event_type_name === "Sammelan" && event?.event_report_files?.length > 0 && (
                  <div className={styles.reportViewSection}>
                    <hr className={styles.divider} />
                    <h3>📄 Event Report</h3>
                    <div className={styles.reportLinkWrapper} onClick={() => setSelectedBigPDF(event.event_report_files[0])}>
                       <span>Click to view report: {event.event_report_files[0].split('/').pop()}</span>
                    </div>
                  </div>
                )}
              </div>

              <div className={styles.statsCard}>
                <h3>Attendance Summary</h3>
                <div className={styles.statRow}><span>Boys:</span> <strong>{event?.boys_attended || 0}</strong></div>
                <div className={styles.statRow}><span>Girls:</span> <strong>{event?.girls_attended || 0}</strong></div>
                <div className={styles.statRow}><span>Parents:</span> <strong>{event?.parents_attended || 0}</strong></div>
              </div>
            </div>

            <div className={styles.gallerySection}>
              <h3>📸 Gallery</h3>
              <div className={styles.gallerySingleRow}>
                {event?.event_photos?.length > 0 ? (
                    event.event_photos.map((photo, i) => (
                    <div key={i} className={styles.galleryImgWrapper} onClick={() => setSelectedBigImage(photo)}>
                        <img src={photo} alt="event" className={styles.galleryImgUniform} />
                    </div>
                    ))
                ) : (
                    <p className={styles.emptyGallery}>No photos available for this event.</p>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default EventDetailsPage;