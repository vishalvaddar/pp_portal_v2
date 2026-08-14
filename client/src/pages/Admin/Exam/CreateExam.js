import React, { useState, useEffect, useMemo } from "react";
import useCreateExamHooks from "../../../hooks/CreateExamHooks.js";
import AssignStudentsModal from "./AssignStudentsModal.js";
import ExamCreationModal from "./ExamCreationModal.js";
import { useSystemConfig } from "../../../contexts/SystemConfigContext";

import axios from "axios";
import { 
  FaListAlt, 
  FaDownload, 
  FaPlus, 
  FaTrash, 
  FaUserPlus, 
  FaSnowflake, 
  FaSearch,
  FaFilter,
  FaCheckCircle,
  FaClock,
  FaTimes,
  FaEdit, FaSave ,
} from "react-icons/fa";
import { GrView } from 'react-icons/gr';
import classes from "./CreateExam.module.css";

const API_BASE_URL = process.env.REACT_APP_BACKEND_API_URL;

const CreateExam = () => {
  const [isCreateModalOpen, setCreateModalOpen] = useState(false);
  const { appliedConfig, loading } = useSystemConfig();
    const currentYear = appliedConfig?.academic_year ? appliedConfig.academic_year.split("-")[0] : "";
  const [assignExamId, setAssignExamId] = useState(null);
  const [notAssignedExams, setNotAssignedExams] = useState([]);
  const [message, setMessage] = useState("");
  const [activeTab, setActiveTab] = useState("active"); // 'active', 'frozen', 'all'
  const [searchTerm, setSearchTerm] = useState("");
  const [sortBy, setSortBy] = useState("date_desc"); // 'date_desc', 'date_asc', 'name'
 const [showCentres, setShowCentres] = useState(false);
const [centreSearchTerm, setCentreSearchTerm] = useState('');
const [centreFilter, setCentreFilter] = useState('all'); // 'all', 'active', 'inactive'

  
  const {
    entries,
    isLoading,
    error,
    deleteExam,
    toggleFreezeExam,
    newCentre,
    handleCentreChange,
    districts,
    setDistricts,
    usedBlocks,
    isCreatingCentre,
    setIsCreatingCentre,
    newCentreName,
    createCentre,
    setNewCentreName,
    setviewcentres,
    viewcentres,
    deleteCentre,
    editedCentreName,editedCentre,setEditingCentreId,editingCentreId,setEditedCentre,
    updateCentre,
  } = useCreateExamHooks();

  // Filter and categorize exams
  const categorizedExams = useMemo(() => {
    const filtered = entries.filter(entry => {
      const matchesSearch = 
        entry.exam_name.toLowerCase().includes(searchTerm.toLowerCase()) ||
        entry.pp_exam_centre_name.toLowerCase().includes(searchTerm.toLowerCase());
      
      if (activeTab === "active") return !entry.frozen_yn || entry.frozen_yn === "N";
      if (activeTab === "frozen") return entry.frozen_yn === "Y";
      return matchesSearch;
    }).filter(entry => 
      entry.exam_name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      entry.pp_exam_centre_name.toLowerCase().includes(searchTerm.toLowerCase())
    );

    // Sort exams
    return filtered.sort((a, b) => {
      switch(sortBy) {
        case 'date_desc':
          return new Date(b.exam_date) - new Date(a.exam_date);
        case 'date_asc':
          return new Date(a.exam_date) - new Date(b.exam_date);
        case 'name':
          return a.exam_name.localeCompare(b.exam_name);
        default:
          return 0;
      }
    });
  }, [entries, activeTab, searchTerm, sortBy]);

  // Fetch exams created but not assigned any students
 useEffect(() => {
  if (!appliedConfig?.academic_year) return;

  const fetchNotAssignedExams = async () => {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/api/exams/notassigned`,
        {
          params: {
            year: appliedConfig.academic_year, // ✅ dynamic year
          },
        }
      );

      setNotAssignedExams(response.data || []);
    } catch (error) {
      console.error("Error fetching exams:", error);
      setNotAssignedExams([]);
    }
  };

  fetchNotAssignedExams();
}, [appliedConfig]);

  const handleDelete = async (examId) => {
    if (window.confirm("Are you sure you want to delete this exam?")) {
      try {
        await deleteExam(examId);
        setNotAssignedExams(prev => prev.filter(exam => exam.exam_id !== examId));
        setMessage("✅ Exam deleted successfully");
        setTimeout(() => setMessage(""), 3000);
      } catch (err) {
        setMessage("❌ Failed to delete exam");
        setTimeout(() => setMessage(""), 3000);
      }
    }
  };

  const handleFreeze = (examId) => {
    if (window.confirm("Freezing this exam will prevent any further changes. Are you sure?")) {
      toggleFreezeExam(examId);
      setMessage("✅ Exam frozen successfully");
      setTimeout(() => setMessage(""), 3000);
    }
  };

  const filteredCentres = useMemo(() => {
  let filtered = viewcentres;
  
  // Apply status filter
  if (centreFilter === 'active') {
    filtered = filtered.filter(centre => centre.active_yn === 'Y');
  } else if (centreFilter === 'inactive') {
    filtered = filtered.filter(centre => centre.active_yn === 'N');
  }
  
  // Apply search filter
  if (centreSearchTerm) {
    const searchLower = centreSearchTerm.toLowerCase();
    filtered = filtered.filter(centre => 
      centre.pp_exam_centre_name?.toLowerCase().includes(searchLower) ||
      centre.pp_exam_centre_code?.toLowerCase().includes(searchLower) ||
      centre.village?.toLowerCase().includes(searchLower) ||
      centre.contact_person?.toLowerCase().includes(searchLower) ||
      centre.contact_phone?.includes(centreSearchTerm) ||
      centre.pincode?.includes(centreSearchTerm)
    );
  }
  
  return filtered;
}, [viewcentres, centreSearchTerm, centreFilter]);

 const handleCreateCentre = async () => {
  const success = await createCentre();

  if (!success) return;

  setTimeout(() => setMessage(""), 3000);
  setIsCreatingCentre(false);
};


const handleEditCentre = (centre) => {
  setEditingCentreId(centre.pp_exam_centre_id);
  setEditedCentre({
    pp_exam_centre_name: centre.pp_exam_centre_name || '',
    pp_exam_centre_code: centre.pp_exam_centre_code || '',
    sitting_capacity: centre.sitting_capacity || '',
    latitude: centre.latitude || '',
    longitude: centre.longitude || '',
    address: centre.address || '',
    village: centre.village || '',
    pincode: centre.pincode || '',
    contact_person: centre.contact_person || '',
    contact_phone: centre.contact_phone || '',
    contact_email: centre.contact_email || '',
    active_yn: centre.active_yn || 'Y'
  });
};

  return (
    <div className={classes.container}>
      <header className={classes.header}>
        <div className={classes.headerTop}>
         <h1>
  Exam Dashboard{" "}
  {!loading && appliedConfig?.academic_year
    ? appliedConfig.academic_year
    : ""}
</h1>
          <div className={classes.headerActions}>
            <button 
              onClick={() => setCreateModalOpen(true)} 
              className={classes.createButton}
            >
              <FaPlus /> Create New Exam
            </button>
            <button
              type="button"
              className={classes.createButton}
              onClick={() => setIsCreatingCentre(true)}
            >
              <FaPlus /> Create Centre
            </button>
            <button
              type="button"
              className={classes.createButton}
              onClick={() => setShowCentres(true)}
            >
              <GrView /> View Exam Centre
            </button>
          </div>
        </div>
        

        {/* Search and Filter Section */}
        <div className={classes.searchFilterSection}>
          <div className={classes.searchBox}>
            <FaSearch className={classes.searchIcon} />
            <input
              type="text"
              placeholder="  Search exams by name or centre..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className={classes.searchInput}
            />
          </div>
          
          <div className={classes.filterControls}>
            <select 
              value={sortBy} 
              onChange={(e) => setSortBy(e.target.value)}
              className={classes.sortSelect}
            >
              <option value="date_desc">Newest First</option>
              <option value="date_asc">Oldest First</option>
              <option value="name">A to Z</option>
            </select>
          </div>
        </div>



        {/* Tabs for Active/Frozen Exams */}
        <div className={classes.tabContainer}>
          <button 
            className={`${classes.tabButton} ${activeTab === 'active' ? classes.activeTab : ''}`}
            onClick={() => setActiveTab('active')}
          >
            <FaClock /> Active Exams ({entries.filter(e => !e.frozen_yn || e.frozen_yn === "N").length})
          </button>
          <button 
            className={`${classes.tabButton} ${activeTab === 'frozen' ? classes.activeTab : ''}`}
            onClick={() => setActiveTab('frozen')}
          >
            <FaCheckCircle /> Completed Exams ({entries.filter(e => e.frozen_yn === "Y").length})
          </button>
          <button 
            className={`${classes.tabButton} ${activeTab === 'all' ? classes.activeTab : ''}`}
            onClick={() => setActiveTab('all')}
          >
            <FaFilter /> All Exams ({entries.length})
          </button>
        </div>
      </header>

      

      {/* Status Message */}
      {message && (
        <div className={message.includes("✅") ? classes.messageSuccess : classes.messageError}>
          {message}
        </div>
      )}

{showCentres && (
  <div className={classes.modalOverlay}>
    <div className={classes.centreModal}>
      <button
        onClick={() => setShowCentres(false)}
        className={classes.closeButton}
      >
        ×
      </button>

      <h3>Exam Centres</h3>

      {/* Search Section */}
      <div className={classes.centreSearchSection}>
        <div className={classes.searchBox}>
          <FaSearch className={classes.searchIcon} />
          <input
            type="text"
            placeholder="Search by centre name, code, village or contact..."
            value={centreSearchTerm}
            onChange={(e) => setCentreSearchTerm(e.target.value)}
            className={classes.searchInput}
          />
        </div>
        {centreSearchTerm && (
          <button
            className={classes.clearSearchButton}
            onClick={() => setCentreSearchTerm('')}
          >
            <FaTimes /> Clear
          </button>
        )}
      </div>

      {/* Filter Tabs for Active/Inactive Centres */}
      <div className={classes.centreFilterTabs}>
        <button
          className={`${classes.centreTabButton} ${centreFilter === 'all' ? classes.activeCentreTab : ''}`}
          onClick={() => setCentreFilter('all')}
        >
          All ({viewcentres.length})
        </button>
        <button
          className={`${classes.centreTabButton} ${centreFilter === 'active' ? classes.activeCentreTab : ''}`}
          onClick={() => setCentreFilter('active')}
        >
          Active ({viewcentres.filter(c => c.active_yn === 'Y').length})
        </button>
        <button
          className={`${classes.centreTabButton} ${centreFilter === 'inactive' ? classes.activeCentreTab : ''}`}
          onClick={() => setCentreFilter('inactive')}
        >
          Inactive ({viewcentres.filter(c => c.active_yn === 'N').length})
        </button>
      </div>

      {/* Results Count */}
      <div className={classes.centreResultsCount}>
        Showing {filteredCentres.length} of {viewcentres.length} centres
      </div>

      <div className={classes.centresList}>
        {filteredCentres.length === 0 ? (
          <div className={classes.emptyState}>
            <p>No centres found matching your search.</p>
          </div>
        ) : (
          filteredCentres.map((centre) => (
            <div
              key={centre.pp_exam_centre_id}
              className={classes.centreItem}
            >
              {/* LEFT SIDE - Display/Edit Form */}
              <div className={classes.centreInfo}>
                {editingCentreId === centre.pp_exam_centre_id ? (
                  <div className={classes.editForm}>
                    <div className={classes.formField}>
                      <label>Centre Name *</label>
                      <input
                        placeholder="e.g., Delhi Public School"
                        value={editedCentre.pp_exam_centre_name}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          pp_exam_centre_name: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Centre Code</label>
                      <input
                        placeholder="e.g., DEL001"
                        value={editedCentre.pp_exam_centre_code}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          pp_exam_centre_code: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Sitting Capacity</label>
                      <input
                        type="number"
                        placeholder="e.g., 200"
                        value={editedCentre.sitting_capacity}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          sitting_capacity: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Address</label>
                      <input
                        placeholder="e.g., 123 Main Street"
                        value={editedCentre.address}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          address: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Village/City</label>
                      <input
                        placeholder="e.g., New Delhi"
                        value={editedCentre.village}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          village: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Pincode</label>
                      <input
                        placeholder="e.g., 110001"
                        value={editedCentre.pincode}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          pincode: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Contact Person</label>
                      <input
                        placeholder="e.g., Dr. Rajesh Kumar"
                        value={editedCentre.contact_person}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          contact_person: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Contact Phone</label>
                      <input
                        placeholder="e.g., +91 9876543210"
                        value={editedCentre.contact_phone}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          contact_phone: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Contact Email</label>
                      <input
                        type="email"
                        placeholder="e.g., centre@example.com"
                        value={editedCentre.contact_email}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          contact_email: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Latitude</label>
                      <input
                        type="number"
                        step="any"
                        placeholder="e.g., 28.6139"
                        value={editedCentre.latitude}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          latitude: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>

                    <div className={classes.formField}>
                      <label>Longitude</label>
                      <input
                        type="number"
                        step="any"
                        placeholder="e.g., 77.2090"
                        value={editedCentre.longitude}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          longitude: e.target.value
                        })}
                        className={classes.input}
                      />
                    </div>
                    
                    <div className={classes.formField}>
                      <label>Status</label>
                      <select
                        value={editedCentre.active_yn}
                        onChange={(e) => setEditedCentre({
                          ...editedCentre,
                          active_yn: e.target.value
                        })}
                        className={classes.select}
                      >
                        <option value="Y">Active</option>
                        <option value="N">Inactive</option>
                      </select>
                    </div>
                  </div>
                ) : (
                  <div>
                    <div className={classes.centreName}>
                      {centre.pp_exam_centre_name}
                      {centre.active_yn === 'N' && (
                        <span className={classes.inactiveBadge}>Inactive</span>
                      )}
                    </div>
                    {centre.pp_exam_centre_code && (
                      <div className={classes.centreCode}>
                        Code: {centre.pp_exam_centre_code}
                      </div>
                    )}
                    {centre.address && (
                      <div className={classes.centreAddress}>
                        {centre.address}, {centre.village} - {centre.pincode}
                      </div>
                    )}
                    {centre.contact_phone && (
                      <div className={classes.centreContact}>
                        📞 {centre.contact_phone} | ✉️ {centre.contact_email}
                      </div>
                    )}
                    {centre.sitting_capacity && (
                      <div className={classes.centreCapacity}>
                        Capacity: {centre.sitting_capacity} seats
                      </div>
                    )}
                  </div>
                )}
              </div>

              {/* RIGHT SIDE BUTTONS */}
              <div className={classes.actionButtons}>
                {editingCentreId === centre.pp_exam_centre_id ? (
                  <>
                    <button
                      className={classes.saveButton}
                      onClick={() => updateCentre(centre.pp_exam_centre_id)}
                    >
                      <FaSave />
                    </button>
                    <button
                      className={classes.cancelButton}
                      onClick={() => {
                        setEditingCentreId(null);
                        setEditedCentre({
                          pp_exam_centre_name: '',
                          pp_exam_centre_code: '',
                          sitting_capacity: '',
                          latitude: '',
                          longitude: '',
                          address: '',
                          village: '',
                          pincode: '',
                          contact_person: '',
                          contact_phone: '',
                          contact_email: '',
                          active_yn: 'Y'
                        });
                      }}
                    >
                      Cancel
                    </button>
                  </>
                ) : (
                  <button
                    className={classes.editButton}
                    onClick={() => handleEditCentre(centre)}
                  >
                    <FaEdit />
                  </button>
                )}

                <button
                  className={classes.deleteButton}
                  onClick={() => deleteCentre(centre.pp_exam_centre_id)}
                >
                  <FaTrash />
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  </div>
)}

      {/* Modals */}
      {isCreateModalOpen && (
        <div className={classes.modalOverlay}>
          <div className={classes.modalContent}>
            <ExamCreationModal onClose={() => setCreateModalOpen(false)} />
          </div>
        </div>
      )}

     
  

 {/* assigned exam list */}
      {assignExamId && (
        <div className={classes.modalOverlay}>
          <div className={classes.modalContent}>
            <AssignStudentsModal 
              examId={assignExamId} 
              onClose={() => setAssignExamId(null)} 
            />
          </div>
        </div>
      )}

      {/* Create Centre Modal - ADDED BACK */}
      {isCreatingCentre && (
        <div className={classes.modalOverlay}>
          <div className={classes.centreModal}>
            <div className={classes.modalHeader}>
              <h3>Create New Exam Centre</h3>
              <button 
                className={classes.closeButton}
                onClick={() => setIsCreatingCentre(false)}
              >
                <FaTimes />
              </button>
            </div>
            
            <div className={classes.modalBody}>
              <div className={classes.formGrid}>
                <div className={classes.formGroup}>
                  <label htmlFor="centreCode">
                    Centre Code <span className={classes.required}>*</span>
                  </label>
                  <input
                    id="centreCode"
                    type="text"
                    placeholder="USHATAIBGM"
                    value={newCentre.pp_exam_centre_code || ''}
                    onChange={(e) => handleCentreChange("pp_exam_centre_code", e.target.value)}
                    maxLength={20}
                    className={classes.formInput}
                  />
                  <small className={classes.formHelp}>
                    Unique identifier for the centre
                  </small>
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="centreName">
                    Centre Name <span className={classes.required}>*</span>
                  </label>
                  <input
                    id="centreName"
                    type="text"
                    placeholder="Example: Smt Ushatai Gogte Girls High School, Belagavi"
                    value={newCentre.pp_exam_centre_name || ''}
                    onChange={(e) => handleCentreChange("pp_exam_centre_name", e.target.value)}
                    required
                    maxLength={100}
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroupFull}>
                  <label htmlFor="address">Address</label>
                  <input
                    id="address"
                    type="text"
                    placeholder="Full address of the exam centre"
                    value={newCentre.address || ''}
                    onChange={(e) => handleCentreChange("address", e.target.value)}
                    maxLength={200}
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="village">Village/Town</label>
                  <input
                    id="village"
                    type="text"
                    placeholder="Village name"
                    value={newCentre.village || ''}
                    onChange={(e) => handleCentreChange("village", e.target.value)}
                    maxLength={100}
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="pincode">Pincode</label>
                  <input
                    id="pincode"
                    type="text"
                    placeholder="560001"
                    value={newCentre.pincode || ''}
                    onChange={(e) => handleCentreChange("pincode", e.target.value)}
                    maxLength={12}
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="contactPerson">Contact Person</label>
                  <input
                    id="contact Person Name"
                    type="text"
                    placeholder="Person in charge"
                    value={newCentre.contact_person || ''}
                    onChange={(e) => handleCentreChange("contact_person", e.target.value)}
                    maxLength={100}
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="contactPhone">Contact Phone</label>
                  <input
                    id="contactPhone"
                    type="text"
                    placeholder="9876543210"
                    value={newCentre.contact_phone || ''}
                    onChange={(e) => handleCentreChange("contact_phone", e.target.value)}
                    maxLength={12}
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="contactEmail">Contact Email</label>
                  <input
                    id="contactEmail"
                    type="email"
                    placeholder="contact@example.com"
                    value={newCentre.contact_email || ''}
                    onChange={(e) => handleCentreChange("contact_email", e.target.value)}
                    maxLength={200}
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="sittingCapacity">Sitting Capacity</label>
                  <input
                    id="sittingCapacity"
                    type="number"
                    placeholder="100"
                    value={newCentre.sitting_capacity || ''}
                    onChange={(e) => handleCentreChange("sitting_capacity", e.target.value)}
                    min="0"
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="latitude">Latitude</label>
                  <input
                    id="latitude"
                    type="text"
                    placeholder="28.7041"
                    value={newCentre.latitude || ''}
                    onChange={(e) => handleCentreChange("latitude", e.target.value)}
                    className={classes.formInput}
                  />
                </div>

                <div className={classes.formGroup}>
                  <label htmlFor="longitude">Longitude</label>
                  <input
                    id="longitude"
                    type="text"
                    placeholder="77.1025"
                    value={newCentre.longitude || ''}
                    onChange={(e) => handleCentreChange("longitude", e.target.value)}
                    className={classes.formInput}
                  />
                </div>
              </div>

              <div className={classes.formFooter}>
                <button 
                  className={classes.btnPrimary}
                  onClick={handleCreateCentre}
                >
                  <FaPlus /> Create Centre
                </button>
                <button 
                  className={classes.btnSecondary}
                  onClick={() => setIsCreatingCentre(false)}
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Unassigned Exams Section */}
      <div className={classes.notAssignedExamsContainer}>
        <h3 className={classes.sectionTitle}>
          <FaClock style={{ marginRight: '8px' }} />
          Exams Awaiting Assignment ({notAssignedExams.length})
        </h3>
        {notAssignedExams.length === 0 ? (
          <div className={classes.emptyState}>
            <p>No exams awaiting assignment.</p>
          </div>
        ) : (
          <div className={classes.examGrid}>
            {notAssignedExams.map((exam) => (
              <div key={exam.exam_id} className={classes.examCardCompact}>
                <div className={classes.cardHeader}>
                  <h4>{exam.exam_name}</h4>
                  <span className={classes.badgePending}>Pending</span>
                </div>
                <div className={classes.cardBody}>
                  <p>
  <strong>Date:</strong> {new Date(exam.exam_date).toLocaleDateString('en-GB')}
</p>
                  <p><strong>Centre:</strong> {exam.pp_exam_centre_name}</p>
                  <p><strong>Time:</strong> {exam.exam_start_time} - {exam.exam_end_time}</p>
                </div>
                <div className={classes.cardActions}>
                  <button
                    className={classes.assignButton}
                    onClick={() => setAssignExamId(exam.exam_id)}
                  >
                    <FaUserPlus /> Assign Students
                  </button>
                  <button
                    className={classes.deleteButton}
                    onClick={() => handleDelete(exam.exam_id)}
                  >
                    <FaTrash /> Delete
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Main Exams List */}
      <main className={classes.mainContent}>
        {isLoading ? (
          <div className={classes.loadingState}>
            <p>Loading exams...</p>
          </div>
        ) : error ? (
          <div className={classes.errorState}>
            <p>Error: {error}</p>
          </div>
        ) : categorizedExams.length === 0 ? (
          <div className={classes.emptyState}>
            <p>
              {searchTerm 
                ? `No exams found for "${searchTerm}"`
                : activeTab === 'active' 
                  ? 'No active exams found'
                  : 'No completed exams found'
              }
            </p>
          </div>
        ) : (
          <>
            <div className={classes.resultsHeader}>
              <h3>
                {activeTab === 'active' && 'Active Exams'}
                {activeTab === 'frozen' && 'Completed Exams'}
                {activeTab === 'all' && 'All Exams'}
                <span className={classes.resultsCount}> ({categorizedExams.length})</span>
              </h3>
            </div>
            
            <div className={classes.examGrid}>
              {categorizedExams.map((entry) => (
                <div key={entry.exam_id} className={classes.examCard}>
                  <div className={classes.cardHeader}>
                    <h4>{entry.exam_name}</h4>
                    {entry.frozen_yn === "Y" ? (
                      <span className={classes.badgeFrozen}>
                        <FaCheckCircle /> Completed
                      </span>
                    ) : (
                      <span className={classes.badgeActive}>
                        <FaClock /> Active
                      </span>
                    )}
                  </div>
                  
                  <div className={classes.cardBody}>
                    <div className={classes.cardInfo}>
                      <p><strong>Date:</strong> {new Date(entry.exam_date).toLocaleDateString('en-GB')}</p>
                      <p><strong>Time:</strong> {entry.exam_start_time} - {entry.exam_end_time}</p>
                      <p><strong>Centre:</strong> {entry.pp_exam_centre_name}</p>
                      
                      {entry.blocks && entry.blocks.length > 0 ? (
                        <div className={classes.blocksSection}>
                          <p><strong>Blocks Assigned:</strong></p>
                          <div className={classes.blocksList}>
                            {entry.blocks.map((b, index) => (
                              <span key={index} className={classes.blockTag}>
                                {b.name}
                              </span>
                            ))}
                          </div>
                        </div>
                      ) : (
                        <p className={classes.noAssignment}>No blocks assigned yet</p>
                      )}
                    </div>
                    
                    {/* Action buttons for assigned exams */}
                    {entry.district_name && (
                      <div className={classes.actionRow}>
                        <div className={classes.actionButtons}>
                          <a
                            href={`${API_BASE_URL}/api/exams/${entry.exam_id}/student-list`}
                            download
                            className={`${classes.actionButton} ${classes.btnBlue}`}
                          >
                            <FaListAlt /> Calling List
                          </a>
                          
                          <a
                            href={`${API_BASE_URL}/api/exams/${entry.exam_id}/${entry.exam_name}/download-all-hall-tickets`}
                            download
                            className={`${classes.actionButton} ${classes.btnYellow}`}
                          >
                            <FaDownload /> Hall Tickets
                          </a>
                          
                          {!entry.frozen_yn || entry.frozen_yn === "N" ? (
                            <>
                              {/* <button
                                className={`${classes.actionButton} ${classes.btnRed}`}
                                onClick={() => handleDelete(entry.exam_id)}
                              >
                                <FaTrash /> Delete
                              </button> */}
                              
                              <button
                                className={`${classes.actionButton} ${classes.btnGreen}`}
                                onClick={() => handleFreeze(entry.exam_id)}
                              >
                                <FaSnowflake /> Freeze Exam
                              </button>
                            </>
                          ) : (
                            <button
                              className={`${classes.actionButton} ${classes.btnGray}`}
                              disabled
                            >
                              <FaCheckCircle /> Exam Completed
                            </button>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </main>
    </div>
  );
};

export default CreateExam;