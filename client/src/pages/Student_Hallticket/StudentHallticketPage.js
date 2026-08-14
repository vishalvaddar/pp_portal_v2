import React, { useState } from "react";
import axios from "axios";

// Import logos
import leftLogo from "../../assets/images.png";
import rightLogo from "../../assets/RCF-PP2.jpg";

const API_BASE_URL = process.env.REACT_APP_BACKEND_API_URL;

const StudentHallticketPage = () => {
  const [hallTicketNo, setHallTicketNo] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

 const handleDownload = async () => {
  if (!hallTicketNo.trim()) {
    setError("Please enter Hall Ticket Number");
    return;
  }

  try {
    setLoading(true);
    setError("");

    const response = await axios.get(
      `${API_BASE_URL}/api/exams/hallticket/${hallTicketNo}`,
      {
        responseType: "blob",
      }
    );

    // Always use hall ticket number as fallback filename
    const fileName = `${hallTicketNo}.pdf`;
    
    // Try to get filename from headers, but fallback to hall ticket number
    const contentDisposition = response.headers["content-disposition"];
    let finalFileName = fileName;
    
    if (contentDisposition) {
      const match = contentDisposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/);
      if (match && match[1]) {
        finalFileName = match[1].replace(/['"]/g, '');
      }
    }

    // Create download
    const url = window.URL.createObjectURL(new Blob([response.data]));
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", finalFileName);
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
    
  } catch (err) {
    console.error(err);
    if (err.response?.status === 404) {
      setError("Hall ticket not found");
    } else {
      setError("Failed to download hall ticket");
    }
  } finally {
    setLoading(false);
  }
};

  const handleKeyPress = (e) => {
    if (e.key === "Enter") {
      handleDownload();
    }
  };

  return (
    <div style={styles.container}>
      {/* Main Card */}
      <div style={styles.card}>
        {/* Header with Logos */}
        <div style={styles.header}>
          <img src={leftLogo} alt="Left Logo" style={styles.leftLogo} />
          <div style={styles.titleSection}>
            <h1 style={styles.title}>Student Portal</h1>
            <p style={styles.subtitle}>Download Your Hall Ticket</p>
          </div>
          <img src={rightLogo} alt="Right Logo" style={styles.rightLogo} />
        </div>

        {/* Instruction Box */}
        <div style={styles.instructionBox}>
          <p style={styles.instructionText}>
            📌 <strong>Instructions:</strong> Enter your Hall Ticket Number to download your exam hall ticket.
            The PDF will be downloaded automatically. Please keep a printed copy for exam day.
          </p>
        </div>

        {/* Input Section */}
        <div style={styles.inputGroup}>
          <label style={styles.label}>Hall Ticket Number</label>
          <input
            type="text"
            placeholder="e.g., 26300021"
            value={hallTicketNo}
            onChange={(e) => setHallTicketNo(e.target.value)}
            onKeyPress={handleKeyPress}
            style={styles.input}
          />
        </div>

        {error && (
          <div style={styles.errorBox}>
            <span style={styles.errorIcon}>⚠️</span>
            <p style={styles.error}>{error}</p>
          </div>
        )}

        {/* Download Button */}
        <button
          onClick={handleDownload}
          style={loading ? styles.buttonLoading : styles.button}
          disabled={loading}
        >
          {loading ? (
            <>
              <span style={styles.spinner}></span>
              Downloading...
            </>
          ) : (
            "📥 Download Hall Ticket"
          )}
        </button>

        {/* Footer Note */}
        <div style={styles.footerNote}>
          <p style={styles.footerText}>
            Having trouble? Contact exam cell at <strong>9606930204</strong>
          </p>
        </div>
      </div>
    </div>
  );
};

const styles = {
  container: {
    minHeight: "100vh",
    width: "100%",
    background: "linear-gradient(135deg, #e6f0ff 0%, #ffffff 100%)",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    padding: "2rem",
    boxSizing: "border-box",
    fontFamily: "'Segoe UI', 'Roboto', 'Helvetica Neue', sans-serif",
  },
  card: {
    maxWidth: "550px",
    width: "100%",
    backgroundColor: "#ffffff",
    borderRadius: "28px",
    boxShadow: "0 20px 35px -10px rgba(0, 20, 80, 0.15)",
    padding: "2rem 2rem 2rem 2rem",
    transition: "transform 0.2s ease, box-shadow 0.2s ease",
    border: "1px solid rgba(59, 130, 246, 0.15)",
  },
  header: {
    display: "flex",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: "2rem",
    flexWrap: "wrap",
    gap: "1rem",
  },
  leftLogo: {
    height: "60px",
    width: "auto",
    objectFit: "contain",
  },
  rightLogo: {
    height: "70px",
    width: "auto",
    objectFit: "contain",
  },
  titleSection: {
    textAlign: "center",
    flex: 1,
  },
  title: {
    fontSize: "1.8rem",
    fontWeight: "700",
    margin: "0 0 0.3rem 0",
    color: "#0a2b5e",
    letterSpacing: "-0.3px",
  },
  subtitle: {
    fontSize: "0.95rem",
    color: "#3b82f6",
    margin: 0,
    fontWeight: "500",
  },
  instructionBox: {
    backgroundColor: "#eff6ff",
    borderLeft: "5px solid #3b82f6",
    borderRadius: "16px",
    padding: "1rem 1.2rem",
    marginBottom: "2rem",
  },
  instructionText: {
    margin: 0,
    fontSize: "0.9rem",
    lineHeight: "1.5",
    color: "#1e293b",
  },
  inputGroup: {
    marginBottom: "1.8rem",
  },
  label: {
    display: "block",
    marginBottom: "0.6rem",
    fontWeight: "600",
    color: "#0f3b6f",
    fontSize: "0.9rem",
  },
  input: {
    width: "100%",
    padding: "0.9rem 1rem",
    fontSize: "1rem",
    border: "1.5px solid #e2e8f0",
    borderRadius: "20px",
    outline: "none",
    transition: "all 0.2s ease",
    boxSizing: "border-box",
    fontFamily: "inherit",
    backgroundColor: "#fafcff",
  },
  inputFocus: {
    borderColor: "#3b82f6",
    boxShadow: "0 0 0 3px rgba(59, 130, 246, 0.2)",
  },
  button: {
    width: "100%",
    backgroundColor: "#3b82f6",
    color: "white",
    border: "none",
    padding: "0.9rem 1rem",
    fontSize: "1rem",
    fontWeight: "600",
    borderRadius: "40px",
    cursor: "pointer",
    transition: "all 0.2s ease",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    gap: "8px",
    marginBottom: "1.5rem",
    boxShadow: "0 4px 10px rgba(59, 130, 246, 0.3)",
  },
  buttonHover: {
    backgroundColor: "#2563eb",
    transform: "scale(1.01)",
  },
  buttonLoading: {
    width: "100%",
    backgroundColor: "#94a3b8",
    color: "white",
    border: "none",
    padding: "0.9rem 1rem",
    fontSize: "1rem",
    fontWeight: "600",
    borderRadius: "40px",
    cursor: "not-allowed",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    gap: "8px",
    marginBottom: "1.5rem",
  },
  spinner: {
    display: "inline-block",
    width: "18px",
    height: "18px",
    border: "2px solid rgba(255,255,255,0.3)",
    borderRadius: "50%",
    borderTopColor: "white",
    animation: "spin 0.8s linear infinite",
  },
  errorBox: {
    backgroundColor: "#fee2e2",
    borderRadius: "16px",
    padding: "0.7rem 1rem",
    marginBottom: "1.5rem",
    display: "flex",
    alignItems: "center",
    gap: "10px",
    border: "1px solid #fecaca",
  },
  errorIcon: {
    fontSize: "1.2rem",
  },
  error: {
    color: "#b91c1c",
    margin: 0,
    fontSize: "0.85rem",
    fontWeight: "500",
  },
  footerNote: {
    textAlign: "center",
    borderTop: "1px solid #eef2ff",
    paddingTop: "1.2rem",
    marginTop: "0.5rem",
  },
  footerText: {
    fontSize: "0.8rem",
    color: "#5b6e8c",
    margin: 0,
  },
};

// Add global keyframes for spinner animation
const styleSheet = document.createElement("style");
styleSheet.textContent = `
  @keyframes spin {
    to { transform: rotate(360deg); }
  }
`;
document.head.appendChild(styleSheet);

export default StudentHallticketPage;