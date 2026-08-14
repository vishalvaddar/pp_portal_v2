import React, { useState, useEffect } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import axios from "axios";
import styles from "./EventEditPage.module.css";
import Breadcrumbs from "../../../components/Breadcrumbs/Breadcrumbs"; 
import { useAuth } from "../../../contexts/AuthContext";

import { 
  useFetchStates,
  useFetchEducationDistricts, 
  useFetchBlocks 
} from "../../../hooks/useJurisData";

const EventEditPage = () => {
    const { eventId } = useParams();
    const navigate = useNavigate();
    const API_BASE_URL = process.env.REACT_APP_BACKEND_API_URL;
   const { user } = useAuth(); 

    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");

    const [viewImage, setViewImage] = useState(null);
    const [viewPDF, setViewPDF] = useState(null);

    // --- DYNAMIC BREADCRUMBS PATH ---
    // We removed 'View' from the clickable links to prevent the null info error.
    // 'Events' remains clickable to go back to the dashboard.
    const currentPath = ['Admin', 'Academics', 'Events', 'View', 'Edit'];

    const [formData, setFormData] = useState({
        event_type_id: "",
        event_type_name: "",
        event_title: "",
        event_description: "",
        event_start_date: "",
        event_end_date: "",
        event_location: "",
        event_state: "",
        event_district: "",
        event_block: "",
        cohort_number: "",
        boys_attended: 0,
        girls_attended: 0,
        parents_attended: 0,
    });

    const [existingPhotos, setExistingPhotos] = useState([]);
    const [existingReport, setExistingReport] = useState(null);
    const [photosToDelete, setPhotosToDelete] = useState([]);
    const [newPhotos, setNewPhotos] = useState([]);
    const [newReports, setNewReports] = useState([]);

    const [standardStates, setStandardStates] = useState([]);
    const [standardDistricts, setStandardDistricts] = useState([]);
    const [standardBlocks, setStandardBlocks] = useState([]);
    
    useFetchStates(setStandardStates);
    useFetchEducationDistricts(formData.event_state, setStandardDistricts);
    useFetchBlocks(formData.event_district, setStandardBlocks);

    const getImageUrl = (filePath) => {
        if (!filePath) return "";
        const filename = filePath.replace(/^.*[\\\/]/, '');
        return `${API_BASE_URL}/uploads/events/photos/${filename}`;
    };

    const getReportUrl = (filePath) => {
        if (!filePath) return "";
        const filename = filePath.replace(/^.*[\\\/]/, '');
        return `${API_BASE_URL}/uploads/events/reports/${filename}`;
    };

    useEffect(() => {
        if (!eventId) return;

        const fetchData = async () => {
            try {
                setLoading(true);
                const eventRes = await axios.get(`${API_BASE_URL}/api/events/${eventId}`);
                const data = eventRes.data;
                
                setFormData({
                    ...data,
                    event_start_date: data.event_start_date ? new Date(data.event_start_date).toISOString().split('T')[0] : "",
                    event_end_date: data.event_end_date ? new Date(data.event_end_date).toISOString().split('T')[0] : "",
                });

                if (data.photos) setExistingPhotos(data.photos);
                if (data.reports && data.reports.length > 0) setExistingReport(data.reports[0]);
                
                setError("");
            } catch (err) {
                console.error("Fetch error:", err);
                setError("Failed to load event details.");
            } finally {
                setLoading(false);
            }
        };
        fetchData();
    }, [eventId, API_BASE_URL]);

    useEffect(() => {
        if (loading || !formData.event_type_name) return;
        
        const stateName = standardStates.find(s => String(s.id) === String(formData.event_state))?.name || "";
        const districtName = standardDistricts.find(d => String(d.id) === String(formData.event_district))?.name || "";
        const blockName = standardBlocks.find(b => String(b.id) === String(formData.event_block))?.name || "";

        let parts = [];
        if (formData.event_type_name) parts.push(formData.event_type_name);
        if (formData.event_start_date) parts.push(formData.event_start_date);
        if (formData.cohort_number) parts.push(`Cohort-${formData.cohort_number}`);
        
        const locationName = blockName || districtName || stateName;
        if (locationName) parts.push(locationName);

        const newTitle = parts.join(" - ");
        setFormData(prev => ({ ...prev, event_title: newTitle }));
    }, [
        formData.event_state, 
        formData.event_district, 
        formData.event_block, 
        formData.cohort_number, 
        formData.event_start_date,
        formData.event_type_name,
        standardStates, 
        standardDistricts, 
        standardBlocks, 
        loading
    ]);

    const handleChange = (e) => {
        const { name, value } = e.target;
        setFormData(prev => ({ ...prev, [name]: value }));
    };

    const handleFileChange = (e) => {
        const files = Array.from(e.target.files);
        if (e.target.name === 'photos') setNewPhotos(prev => [...prev, ...files]);
        else if (e.target.name === 'reports') setNewReports(files);
    };

    const handleRemoveExistingPhoto = (photoId) => {
        if (window.confirm("Delete this photo permanently?")) {
            setPhotosToDelete(prev => [...prev, photoId]);
            setExistingPhotos(prev => prev.filter(p => (p.photo_id || p.id) !== photoId));
        }
    };
 
 const handleSubmit = async (e) => {
    e.preventDefault();
    if (submitting) return;

    if (new Date(formData.event_start_date) > new Date(formData.event_end_date)) {
        setError("Start date cannot be greater than the end date.");
        window.scrollTo(0, 0);
        return; 
    }

    setSubmitting(true);
    setError("");
    
    try {
        const data = new FormData();
        
        // Append all existing form data
        Object.keys(formData).forEach(key => data.append(key, formData[key]));
        
        // --- ADD THIS LINE FOR TRACKING ---
        data.append("user_id", user?.user_id || ""); 
        
        newPhotos.forEach(p => data.append("photos", p));
        newReports.forEach(r => data.append("reports", r));
        
        if (photosToDelete.length > 0) {
            data.append("photos_to_delete", JSON.stringify(photosToDelete));
        }

        const res = await axios.put(`${API_BASE_URL}/api/events/${eventId}`, data);
        if (res.data.success) {
            setSuccess("Event updated successfully!");
            setTimeout(() => navigate(`/admin/academics/events/${eventId}`), 1200);
        }
    } catch (err) {
        setError(err.response?.data?.message || "Update failed.");
        setSubmitting(false);
    }
};

    if (loading) return <div className={styles.loadingScreen}><div className={styles.spinner}></div><p>Loading...</p></div>;

    const isSammelan = formData.event_type_name === "Sammelan";

    return (
        <div className={styles.pageWrapper}>
            {/* IMAGE MODAL */}
            {viewImage && (
                <div className={styles.modalOverlay} onClick={() => setViewImage(null)}>
                    <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
                        <button className={styles.modalClose} onClick={() => setViewImage(null)}>&times;</button>
                        <img src={viewImage} className={styles.modalImg} alt="Large View" />
                    </div>
                </div>
            )}

            {/* PDF MODAL */}
            {viewPDF && (
                <div className={styles.modalOverlay}>
                    <div className={styles.pdfModalContent}>
                        <div className={styles.pdfModalHeader}>
                            <h3>Report Preview</h3>
                            <button onClick={() => setViewPDF(null)} className={styles.pdfCloseBtn}>&times;</button>
                        </div>
                        <iframe src={viewPDF} title="PDF Preview" width="100%" height="90%"></iframe>
                    </div>
                </div>
            )}

            <div className={styles.container}>
                {/* FIX: We add 'View' to nonLinkSegments. 
                   Now 'Events' is clickable (goes to list), but 'View' and 'Edit' are just text. 
                */}
                <Breadcrumbs 
                    path={currentPath} 
                    nonLinkSegments={['Admin', 'Academics', 'View', 'Edit']} 
                />

                <header className={styles.header}>
                    <h1 className={styles.pageTitle}>Edit Event</h1>
                    <button type="button" onClick={() => navigate(-1)} className={styles.backButton}>Cancel</button>
                </header>

                {error && <div className={styles.alertError}>{error}</div>}
                {success && <div className={styles.alertSuccess}>{success}</div>}

                <form onSubmit={handleSubmit} className={styles.formContent}>
                    <section className={styles.card}>
                        <div className={styles.cardHeader}><h2 className={styles.cardTitle}>Basic Information</h2></div>
                        <div className={styles.cardBody}>
                            <div className={styles.gridRow}>
                                <div className={styles.formGroup}>
                                    <label htmlFor="eventTitleInput">Event Title</label>
                                    <input
                                        id="eventTitleInput"
                                        type="text"
                                        name="event_title"
                                        value={formData.event_title}
                                        readOnly
                                        className={styles.inputReadOnly}
                                        placeholder="auto generated title"
                                    />
                                </div>
                            </div>
                            <div className={styles.gridRow}>
                                <div className={styles.formGroup}>
                                    <label className={styles.label}>Start Date</label>
                                    <input type="date" name="event_start_date" value={formData.event_start_date} onChange={handleChange} className={styles.input} />
                                </div>
                                <div className={styles.formGroup}>
                                    <label className={styles.label}>End Date</label>
                                    <input type="date" name="event_end_date" value={formData.event_end_date} onChange={handleChange} className={styles.input} />
                                </div>
                                <div className={styles.formGroup}>
                                    <label className={styles.label}>Cohort Number</label>
                                    <input type="number" name="cohort_number" value={formData.cohort_number} onChange={handleChange} className={styles.input} />
                                </div>
                            </div>
                            <div className={styles.formGroup}>
                                <label className={styles.label}>Description</label>
                                <textarea name="event_description" rows="3" value={formData.event_description} onChange={handleChange} className={styles.textarea} />
                            </div>
                        </div>
                    </section>

                    <section className={styles.card}>
                        <div className={styles.cardHeader}><h2 className={styles.cardTitle}>Venue Details</h2></div>
                        <div className={styles.cardBody}>
                            <div className={styles.gridRow}>
                                <select name="event_state" value={formData.event_state} onChange={handleChange} className={styles.input}>
                                    <option value="">State</option>{standardStates.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                                </select>
                                <select name="event_district" value={formData.event_district} onChange={handleChange} disabled={!formData.event_state} className={styles.input}>
                                    <option value="">District</option>{standardDistricts.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
                                </select>
                                <select name="event_block" value={formData.event_block} onChange={handleChange} disabled={!formData.event_district} className={styles.input}>
                                    <option value="">Block</option>{standardBlocks.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
                                </select>
                            </div>
                        </div>
                    </section>

                    <section className={styles.card}>
                        <div className={styles.cardHeader}><h2 className={styles.cardTitle}>Attendance & Multimedia</h2></div>
                        <div className={styles.cardBody}>
                            <div className={styles.gridRow}>
                                {!isSammelan && (
                                    <>
                                        <div className={styles.formGroup}><label className={styles.label}>Boys</label>
                                        <input type="number" name="boys_attended" value={formData.boys_attended} onChange={handleChange} className={styles.input} /></div>
                                        <div className={styles.formGroup}><label className={styles.label}>Girls</label>
                                        <input type="number" name="girls_attended" value={formData.girls_attended} onChange={handleChange} className={styles.input} /></div>
                                    </>
                                )}
                                <div className={styles.formGroup}><label className={styles.label}>Parents Attended</label>
                                <input type="number" name="parents_attended" value={formData.parents_attended} onChange={handleChange} className={styles.input} /></div>
                            </div>

                            <div className={styles.mediaPreviewSection}>
                                {existingPhotos.length > 0 && (
                                    <div className={styles.photoGrid}>
                                        {existingPhotos.map((p, idx) => (
                                            <div key={idx} className={styles.photoContainer}>
                                                <img src={getImageUrl(p.file_path)} className={styles.photoThumb} alt="Preview" />
                                                <div className={styles.photoOverlay}>
                                                    <button type="button" onClick={() => setViewImage(getImageUrl(p.file_path))} className={styles.viewIconBtn}>👁️</button>
                                                    <button type="button" onClick={() => handleRemoveExistingPhoto(p.photo_id || p.id)} className={styles.removeBtn}>✕</button>
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                )}
                                
                                {isSammelan && (
                                    <div style={{marginTop: '20px'}}>
                                        <p className={styles.subLabel}>Current Report Status:</p>
                                        {existingReport ? (
                                            <button type="button" className={styles.secondaryButton} onClick={() => setViewPDF(getReportUrl(existingReport.file_path))}>
                                                📄 View Current PDF Report
                                            </button>
                                        ) : (
                                            <span className={styles.notSubmittedBadge}>Report not submitted</span>
                                        )}
                                    </div>
                                )}
                            </div>

                            <div className={styles.gridRow} style={{marginTop: '20px'}}>
                                <div className={styles.formGroup}><label className={styles.label}>Add Photos</label><input type="file" name="photos" multiple accept="image/*" onChange={handleFileChange} className={styles.input} /></div>
                                
                                {isSammelan && (
                                    <div className={styles.formGroup}>
                                        <label className={styles.label}>Replace Report (PDF)</label>
                                        <input type="file" name="reports" accept=".pdf" onChange={handleFileChange} className={styles.input} />
                                    </div>
                                )}
                            </div>
                        </div>
                    </section>

                    <div className={styles.footerActions}>
                        <button type="submit" className={styles.saveBtn} disabled={submitting}>
                            {submitting ? "Updating..." : "Save Changes"}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
};

export default EventEditPage;