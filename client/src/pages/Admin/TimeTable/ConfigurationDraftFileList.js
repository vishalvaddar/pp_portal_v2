import React, { useEffect, useState } from "react";
import axios from "axios";
import { FaEdit, FaCopy, FaTrash } from "react-icons/fa";
import { useNavigate } from "react-router-dom";
import styles from "./ConfigurationDraftFileList.module.css";
import toast, { Toaster } from "react-hot-toast";

export default function ConfigurationDraftFileList() {
  const [configurationDraftFileDtls, setConfigurationDraftFileDtls] = useState([]);
  const navigate = useNavigate();
  const BASE = `${process.env.REACT_APP_BACKEND_API_URL}/api/timetable`;


  useEffect(() => {
    fetchConfigurationDraftFileDtls();
  }, []);

  const fetchConfigurationDraftFileDtls = async () => {
    try {
      const res = await axios.post(`${BASE}/timeTable/getAllConfigurationDraftFileDtls`);
      setConfigurationDraftFileDtls(res.data);
    } catch (err) {
      toast.error("Error fetching list"); 
      console.error("Error fetching list", err);
    }
  };

// ConfigurationDraftFileList.js


const handleCreate = () => {
  navigate("/admin/academics/time-table-dashboard/configure?mode=add");
};

const handleEdit = (id) => {
  navigate(`/admin/academics/time-table-dashboard/configure/${id}?mode=edit`);
};

const handleCopy = (id) => {
  navigate(`/admin/academics/time-table-dashboard/configure/${id}?mode=copy`);
};


const handleDelete = async (id) => {
  if (!window.confirm("Are you sure you want to delete?")) return;
  try {
    await axios.delete(`${BASE}/timeTable/deleteConfigurationDraftFile/${id}`);
    toast.success("Deleted successfully");
    fetchConfigurationDraftFileDtls(); 
  } catch (err) {
    console.error("Delete failed", err);
  }
};


  const formatDate = (date) => {
    return new Date(date).toLocaleString();
  };

  return (
    <div className={styles.container}>
      <Toaster position="top-center" toastOptions={{className: styles.customToast, duration: 2000}}/>
      <div className={styles.header}>
        <h2>Configuration Draft Files</h2>
        <button className={styles.primaryBtn} onClick={handleCreate}>
          + New Configuration
        </button>
      </div>

      <div className={styles.tableWrapper}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>File inserted User Name</th>
              <th>File Name</th>
              <th>File Creation Date And time</th>
              <th>Actions</th>
            </tr>
          </thead>

          <tbody>
            {configurationDraftFileDtls.length === 0 ? (
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
              configurationDraftFileDtls.map((item) => (
                <tr key={item.time_table_config_id}>
                  <td>{item.time_table_config_file_ins_user_name}</td>
                  <td>{item.time_table_config_file_name}</td>
                  <td>{item.display_time_formatted}</td>
                  
                  <td>
                    <span
                      className={styles.linkIcon}
                      onClick={() => handleEdit(item.time_table_config_id)}
                      title="Edit"
                    >
                      <FaEdit />
                    </span>

                    <span
                      className={styles.linkIcon}
                      onClick={() => handleCopy(item.time_table_config_id)}
                      title="Copy"
                    >
                      <FaCopy />
                    </span>

                    <span
                      className={`${styles.linkIcon} ${styles.delete}`}
                      onClick={() => handleDelete(item.time_table_config_id)}
                      title="Delete"
                    >
                      <FaTrash />
                    </span>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}