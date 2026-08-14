import React, { useState, useEffect, useCallback } from "react";
import { useNavigate, useLocation } from "react-router-dom"; // Added useLocation
import axios from "axios";
import styles from "./EventsDashboardPage.module.css";

import EventList from "./EventList";
import EventForm from "./EventForm";
import AddEventTypeModal from "./AddEventTypeModal";
import Breadcrumbs from "../../../components/Breadcrumbs/Breadcrumbs"; 

const EventsDashboardPage = () => {
  const [view, setView] = useState("list");
  const [allEvents, setAllEvents] = useState([]);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);

  const navigate = useNavigate();
  const location = useLocation(); // Initialize location
  const API_BASE_URL = process.env.REACT_APP_BACKEND_API_URL;

  // --- CRITICAL FIX: WATCH FOR PATH CHANGES ---
  // This ensures that when you click "Events" in Breadcrumbs, 
  // the 'view' state is forced back to 'list'
  useEffect(() => {
    if (location.pathname === "/admin/academics/events") {
      setView("list");
    }
  }, [location]);

  // --- DYNAMIC BREADCRUMBS LOGIC ---
  const currentPath = view === "form" 
    ? ['Admin', 'Academics', 'Events', 'Add-Event'] 
    : ['Admin', 'Academics', 'Events'];

  const pageHeading = view === "form" ? "Add New Event" : "Events Management";

  // --- Fetch Events List ---
  const fetchEvents = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const response = await axios.get(`${API_BASE_URL}/api/events`);
      setAllEvents(response.data?.data || response.data || []);
    } catch (err) {
      setError("Failed to load events.");
    } finally {
      setIsLoading(false);
    }
  }, [API_BASE_URL]);

  useEffect(() => {
    if (view === "list") {
      fetchEvents();
    }
  }, [view, fetchEvents]);

 const handleSaveEvent = async (formData) => {
  try {
    setIsLoading(true);
    // This 'formData' now contains the 'user_id' appended by the EventForm
    await axios.post(`${API_BASE_URL}/api/events`, formData, {
      headers: { "Content-Type": "multipart/form-data" },
    });
    alert("Event saved successfully!");
    setView("list");
  } catch (err) {
    alert("Error saving event.");
    setIsLoading(false);
  }
};

  const handleViewDetails = (eventId) => {
    navigate(`/admin/academics/events/${eventId}`);
  };

  const handlePostUpdateClick = () => {
    navigate(`/admin/academics/events/attendance/manage?mode=update`);
  };

  const handleDeleteEvent = async (eventId) => {
    if (!window.confirm("Are you sure?")) return;
    try {
      await axios.delete(`${API_BASE_URL}/api/events/${eventId}`);
      fetchEvents();
    } catch (error) {
      alert("Failed to delete event.");
    }
  };

  const renderContent = () => {
    if (view === "form") {
      return (
        <EventForm 
          onSave={handleSaveEvent} 
          onCancel={() => setView("list")} 
          onOpenAddTypeModal={() => setIsModalOpen(true)}
        />
      );
    }

    if (isLoading) return <div className={styles.loadingContainer}><div className={styles.spinner}></div></div>;

    return <EventList events={allEvents} onViewDetails={handleViewDetails} onDelete={handleDeleteEvent} />;
  };

  const handleSaveEventType = async (payload) => {
  try {
    // payload will be { event_type_name: "...", user_id: "..." }
    await axios.post(`${API_BASE_URL}/api/event-types`, payload);
    
    // 2. Show the success message
    alert("Event Type saved successfully!");
    
    // 3. Optional: Refresh event types if needed, or simply close modal
    setIsModalOpen(false);
  } catch (err) {
    console.error("Error saving event type:", err);
    // Re-throw so the modal's internal error handling can catch it
    throw err; 
  }
};
  return (
    <div className={styles.dashboardContainer} key={location.pathname}> {/* Key forces re-render */}
      <Breadcrumbs 
        path={currentPath} 
        nonLinkSegments={
          view === 'form' 
            ? ['Admin', 'Academics', 'Add-Event'] 
            : ['Admin', 'Academics', 'Events']
        }
      />

      <header className={styles.header}>
        <div className={styles.headerTitle}>
          <h1>{pageHeading}</h1>
        </div>
        {view === 'list' && (
          <div className={styles.buttonGroup}>
            <button className={styles.primaryButton} onClick={() => setView('form')}>
              + Add New Event
            </button>
            <button className={styles.secondaryButton} onClick={handlePostUpdateClick}>
              📅 Post Event Update
            </button>
          </div>
        )}
      </header>

      <main className={styles.content}>{renderContent()}</main>

      <AddEventTypeModal
      isOpen={isModalOpen}
      onClose={() => setIsModalOpen(false)}
      onSave={handleSaveEventType} 
    />
    </div>
  );
};

export default EventsDashboardPage;