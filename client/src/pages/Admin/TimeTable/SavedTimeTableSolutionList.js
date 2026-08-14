import React, { useEffect, useState } from "react";
import axios from "axios";
import { FaEye } from "react-icons/fa"; 
import { useNavigate } from "react-router-dom";
import styles from "./SavedTimeTableSolutionList.module.css";

export default function TimeTableSolutionList() {
  const [solutionList, setSolutionList] = useState([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  const BASE = `${process.env.REACT_APP_BACKEND_API_URL}/api/timetable`;

  useEffect(() => {
    fetchSolutions();
  }, []);

  const fetchSolutions = async () => {
    try {
      const res = await axios.post(`${BASE}/savedTimeTable/getSavedTimeTableSolutionList`);
      setSolutionList(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      console.error("Error fetching solutions", err);
      setSolutionList([]);
    } finally {
      setLoading(false);
    }
  };

  const handleView = (id) => {
    if (!id) return;
    navigate(`/admin/academics/time-table-dashboard/savedTimeTableSolution/${id}?mode=view`);
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h2>Saved Timetable Solutions</h2>
        <p className={styles.subtitle}>Browse and review generated timetable solution builds</p>
      </div>

      <div className={styles.tableCard}>
        <div className={styles.tableWrapper}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th style={{ width: "30%" }}>User Name</th>
                <th style={{ width: "40%" }}>Solution Name</th>
                <th style={{ width: "20%" }}>Date & Time</th>
                <th style={{ width: "10%" }}>Actions</th>
              </tr>
            </thead>

            <tbody>
              {loading ? (
                <tr>
                  <td colSpan="4" className={styles.center}>
                    <div className={styles.loadingState}>
                      <div className={styles.spinner}></div>
                      <p>Loading solutions...</p>
                    </div>
                  </td>
                </tr>
              ) : solutionList.length === 0 ? (
                <tr>
                  <td colSpan="4" className={styles.center}>
                    <div className={styles.emptyState}>
                      <span className={styles.emptyIcon}>📅</span>
                      <div className={styles.emptyText}>No Solutions Found</div>
                      <p className={styles.emptySubtext}>Generated master schedules will show up right here.</p>
                    </div>
                  </td>
                </tr>
              ) : (
                solutionList.map((item, index) => (
                  <tr key={item?.solution_id || index} className={styles.tableRow}>
                    <td className={styles.userCell}>
                      {item?.solution_file_ins_user_name || "-"}
                    </td>
                    <td className={styles.solutionNameCell}>
                      {/* CRITICAL FIX: Flex wrapper inside standard cell container */}
                      <div className={styles.cellFlexWrapper}>
                        <span className={styles.fileIcon}>⚡</span>
                        <span>{item?.solution_file_name || "-"}</span>
                      </div>
                    </td>
                    <td className={styles.dateCell}>
                      {item?.display_date || "-"}
                    </td>

                    <td>
                      <div className={styles.actionGroup}>
                        <span
                          className={styles.linkIcon}
                          onClick={() => handleView(item?.solution_id)}
                          title="View Details"
                        >
                          <FaEye />
                        </span>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}