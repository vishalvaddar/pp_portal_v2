import React from "react";
import { Link } from "react-router-dom";
import styles from "./EventList.module.css";

const EventList = ({ events, onDelete }) => {
  const calculateTotalAttendees = (event) => {
    return (
      Number(event.boys_attended || event.boys_count || 0) +
      Number(event.girls_attended || event.girls_count || 0) +
      Number(event.parents_attended || event.parents_count || 0)
    );
  };

  const formatDate = (dateString) => {
    // Check for both possible keys from backend: start_date or event_start_date
    if (!dateString) return "—";
    return new Date(dateString).toLocaleDateString("en-IN", {
      day: "2-digit",
      month: "short",
      year: "numeric",
    });
  };

  const handleDelete = (e, eventId) => {
    e.stopPropagation();
    if (window.confirm("Delete this event permanently?")) {
      onDelete(eventId);
    }
  };

  if (!events?.length) {
    return (
      <div className={styles.emptyState}>
        <p className={styles.emptyTitle}>No events found</p>
      </div>
    );
  }

  return (
    <div className={styles.container}>
      <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th className={styles.th}>Event</th>
              <th className={styles.th}>Type</th>
              <th className={styles.th}>Date</th>
              <th className={styles.th}>Location</th>
              <th className={styles.th}>Attendees</th>
              <th className={`${styles.th} ${styles.thActions}`}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {events.map((event) => {
              // FIX 1: Map backend keys to variables to handle different names
              const typeName = event.event_type || event.event_type_name || "Event";
              const startDate = event.start_date || event.event_start_date;

              return (
                <tr key={event.event_id} className={styles.row}>
                  <td className={styles.cell}>
                    <div className={styles.eventTitle}>{event.event_title}</div>
                  </td>
                  <td className={styles.cell}>
                    {/* FIX 2: Use the typeName variable here */}
                    <span className={`${styles.badge} ${styles[typeName.toLowerCase()] || styles.badgeDefault}`}>
                      {typeName}
                    </span>
                  </td>
                  <td className={styles.cell}>
                    {/* FIX 3: Use the startDate variable here */}
                    {formatDate(startDate)}
                  </td>
                  <td className={styles.cell}>{event.event_location || "—"}</td>
                  <td className={`${styles.cell} ${styles.numberCell}`}>{calculateTotalAttendees(event)}</td>
                  <td className={`${styles.cell} ${styles.actionsCell}`}>
                    <div className={styles.actionButtons}>
                      {/* FIX 4: Underline is removed via CSS, see below */}
                      <Link to={`/admin/academics/events/${event.event_id}`} className={`${styles.btn} ${styles.btnView}`}>
                        View
                      </Link>
                      <button type="button" className={`${styles.btn} ${styles.btnDelete}`} onClick={(e) => handleDelete(e, event.event_id)}>
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default EventList;