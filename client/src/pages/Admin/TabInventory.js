import React, {
  useMemo,
  useState,
  useCallback,
  useEffect,
  useRef,
} from "react";
import * as XLSX from "xlsx";
import {
  Tablet,
  Search,
  Plus,
  Download,
  AlertCircle,
  Check,
  Clock,
  Zap,
  X,
  MoreVertical,
  Upload,
  FileSpreadsheet,
  ChevronLeft,
  ChevronRight,
  UserCog,
  GraduationCap,
  RefreshCw,
  Layers,
  ArrowRight,
  Filter,
} from "lucide-react";
import { useAuth } from "../../contexts/AuthContext";
import styles from "./TabInventory.module.css";
import Breadcrumbs from "../../components/Breadcrumbs/Breadcrumbs";

// ==========================================
// CONSTANTS
// ==========================================

const API_BASE = process.env.REACT_APP_BACKEND_API_URL + "/api";
const ITEMS_PER_PAGE = 15;

const STATUS_CONFIG = {
  IN_OFFICE: {
    label: "In Office",
    color: "success",
    icon: Check,
    desc: "Ready for assignment",
  },
  ASSIGNED: { label: "Assigned", color: "info", icon: Zap, desc: "In use" },
  RETURNED: {
    label: "Returned",
    color: "warning",
    icon: Clock,
    desc: "Awaiting inspection",
  },
  DAMAGED: {
    label: "Damaged",
    color: "danger",
    icon: AlertCircle,
    desc: "Under repair",
  },
  LOST: {
    label: "Lost",
    color: "critical",
    icon: AlertCircle,
    desc: "Write-off pending",
  },
};

const ALL_STATUSES = Object.keys(STATUS_CONFIG);

// ==========================================
// MULTI-FILTERING HOOK LOGIC
// ==========================================
const useDeviceFilters = (devices, search, activeFilters) => {
  return useMemo(() => {
    return devices.filter((d) => {
      const searchStr =
        `${d.assigned_to || ""} ${d.enr_id || ""} ${d.serial_number || ""} ${d.inventory_id || ""} ${d.brand_name || ""} ${d.model || ""}`.toLowerCase();
      const matchesSearch = searchStr.includes(search.toLowerCase());

      let matchesHolderType = true;
      if (activeFilters.holderType === "STUDENT") {
        matchesHolderType = d.assignment_category === "STUDENT";
      } else if (activeFilters.holderType === "STAFF") {
        matchesHolderType = d.assignment_category === "OFFICIAL";
      }

      let matchesCohort = true;
      if (
        activeFilters.holderType === "STUDENT" &&
        activeFilters.cohortName !== "ALL"
      ) {
        matchesCohort =
          d.assignment_category === "STUDENT" &&
          d.cohort_name === activeFilters.cohortName;
      }

      let matchesStatus = true;
      if (
        !activeFilters.deviceStatus.includes("ALL") &&
        activeFilters.deviceStatus.length > 0
      ) {
        matchesStatus = activeFilters.deviceStatus.includes(d.status);
      }

      return (
        matchesSearch && matchesHolderType && matchesCohort && matchesStatus
      );
    });
  }, [devices, search, activeFilters]);
};

const StatusBadgeComp = ({ status, compact = false }) => {
  const config = STATUS_CONFIG[status] || STATUS_CONFIG.IN_OFFICE;
  const Icon = config.icon;
  return (
    <span
      className={`${styles.badge} ${styles[`badge--${config.color}`]}`}
      title={config.desc}
    >
      <Icon size={14} /> {!compact && <span>{config.label}</span>}
    </span>
  );
};

const StatsCard = React.memo(({ label, value, color, subItems = [] }) => (
  <div className={`${styles.statCard} ${styles[`statCard--${color}`]}`}>
    <div className={styles.statMain}>
      <div className={styles.statLabel}>{label}</div>
      <div className={styles.statValue}>{value}</div>
    </div>
    {subItems.length > 0 && (
      <div className={styles.statSubGrid}>
        {subItems.map((item, idx) => (
          <div key={idx} className={styles.statSubItem}>
            <item.icon size={12} className={styles.subIcon} />
            <span>
              <strong>{item.count}</strong> {item.text}
            </span>
          </div>
        ))}
      </div>
    )}
  </div>
));

const ActionMenu = React.memo(({ device, onAction }) => {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);

  const handleAction = (type) => {
    onAction(type, device);
    setMenuOpen(false);
  };

  useEffect(() => {
    if (!menuOpen) return;
    const handleClickOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [menuOpen]);

  return (
    <div className={styles.actionMenu} ref={menuRef}>
      <button
        className={styles.menuTrigger}
        onClick={() => setMenuOpen(!menuOpen)}
      >
        <MoreVertical size={18} />
      </button>
      {menuOpen && (
        <div className={styles.menuDropdown}>
          <button
            onClick={() => handleAction("STATUS")}
            className={styles.menuItem}
          >
            Change Status
          </button>
          <button
            onClick={() => handleAction("HISTORY")}
            className={styles.menuItem}
          >
            View History
          </button>
          <button
            onClick={() => handleAction("DELETE")}
            className={`${styles.menuItem} ${styles.danger}`}
          >
            Delete Device
          </button>
        </div>
      )}
    </div>
  );
});

const TabInventory = () => {
  const { user } = useAuth();
  const currentUserId = user?.user_id || user?.id;

  const [devices, setDevices] = useState([]);
  const [brands, setBrands] = useState([]);
  const [users, setUsers] = useState([]);
  const [cohorts, setCohorts] = useState([]);
  const [tabStats, setTabStats] = useState({
    total: 0,
    in_office: 0,
    student_assigned: 0,
    official_assigned: 0,
    damaged: 0,
    lost: 0,
    returned_awaiting: 0,
  });
  const [isLoading, setIsLoading] = useState(false);

  const [search, setSearch] = useState("");
  const [toastMessage, setToastMessage] = useState(null);

  const [currentWorkspaceView, setCurrentWorkspaceView] = useState("inventory");
  const [movementReportData, setMovementReportData] = useState([]);

  const [movementForm, setMovementForm] = useState({
    fromCohort: "ALL",
    toCohort: "ALL",
  });

  const [filterSelections, setFilterSelections] = useState({
    holderType: "ALL",
    cohortName: "ALL",
    deviceStatus: ["ALL"],
  });

  const [activeFilters, setActiveFilters] = useState({
    holderType: "ALL",
    cohortName: "ALL",
    deviceStatus: ["ALL"],
  });

  const [viewMode, setViewMode] = useState("grid");
  const [currentPage, setCurrentPage] = useState(1);

  const fileInputRef = useRef(null);

  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [isStatusModalOpen, setIsStatusModalOpen] = useState(false);
  const [isHistoryModalOpen, setIsHistoryModalOpen] = useState(false);
  const [isBulkModalOpen, setIsBulkModalOpen] = useState(false);
  const [isAddingNewBrand, setIsAddingNewBrand] = useState(false);

  const [bulkFile, setBulkFile] = useState(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadError, setUploadError] = useState(null);

  const [selectedDevice, setSelectedDevice] = useState(null);
  const [historyData, setHistoryData] = useState([]);
  const [eligibleStudents, setEligibleStudents] = useState([]);
  const [studentSearch, setStudentSearch] = useState("");
  const [staffSearch, setStaffSearch] = useState("");

  const [addForm, setAddForm] = useState({
    serial_number: "",
    brand_id: "",
    imei: "",
    inventory_id: "",
    tab_purchase_date: "",
    remarks: "",
  });
  const [newBrandForm, setNewBrandForm] = useState({
    brand_name: "",
    model_name: "",
  });
  const [statusForm, setStatusForm] = useState({
    status: "IN_OFFICE",
    assignment_type: "STUDENT",
    student_id: "",
    official_user_id: "",
    remarks: "",
    transaction_date: "",
  });

  const showToastNotification = (msg) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3500);
  };

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    try {
      const [devRes, statRes, brandRes, studentRes, userRes, cohortRes] =
        await Promise.all([
          fetch(`${API_BASE}/tabs`).then((r) => r.json()),
          fetch(`${API_BASE}/tabs/stats`).then((r) => r.json()),
          fetch(`${API_BASE}/tabs/brands`).then((r) => r.json()),
          fetch(`${API_BASE}/tabs/eligible-students`).then((r) => r.json()),
          fetch(`${API_BASE}/tabs/users`).then((r) => r.json()),
          fetch(`${API_BASE}/tabs/cohorts`).then((r) => r.json()),
        ]);

      if (devRes.success) setDevices(devRes.data);
      if (statRes.success) setTabStats(statRes.data);
      if (brandRes.success) setBrands(brandRes.data);
      if (studentRes.success) setEligibleStudents(studentRes.data);
      if (userRes.success) setUsers(userRes.data);
      if (cohortRes.success) setCohorts(cohortRes.data);
    } catch (e) {
      console.error("Fetch Error:", e);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  useEffect(() => {
    if (filterSelections.holderType !== "STUDENT") {
      setFilterSelections((prev) => ({ ...prev, cohortName: "ALL" }));
    }
  }, [filterSelections.holderType]);

  const filteredDevices = useDeviceFilters(devices, search, activeFilters);

  const activeDatasetRows = useMemo(() => {
    if (currentWorkspaceView === "movement") {
      return movementReportData.filter((m) =>
        `${m.serial_number} ${m.inventory_id || ""} ${m.brand_name} ${m.model} ${m.previous_holder} ${m.new_holder}`
          .toLowerCase()
          .includes(search.toLowerCase()),
      );
    }
    return filteredDevices;
  }, [currentWorkspaceView, filteredDevices, movementReportData, search]);

  const totalPages = Math.ceil(activeDatasetRows.length / ITEMS_PER_PAGE);
  const currentData = useMemo(() => {
    const start = (currentPage - 1) * ITEMS_PER_PAGE;
    return activeDatasetRows.slice(start, start + ITEMS_PER_PAGE);
  }, [currentPage, activeDatasetRows]);

  useEffect(() => {
    setCurrentPage(1);
  }, [search, activeFilters, currentWorkspaceView]);

  const filteredStudents = useMemo(() => {
    return eligibleStudents.filter(
      (s) =>
        s.student_name.toLowerCase().includes(studentSearch.toLowerCase()) ||
        (s.enr_id && s.enr_id.toString().includes(studentSearch)),
    );
  }, [eligibleStudents, studentSearch]);

  const filteredStaff = useMemo(() => {
    return users.filter((u) =>
      u.user_name.toLowerCase().includes(staffSearch.toLowerCase()),
    );
  }, [users, staffSearch]);

  const handleStatusPillClick = (statusKey) => {
    setFilterSelections((prev) => {
      let currentStatuses = [...prev.deviceStatus];
      if (statusKey === "ALL") return { ...prev, deviceStatus: ["ALL"] };
      if (currentStatuses.includes("ALL")) currentStatuses = [];

      if (currentStatuses.includes(statusKey)) {
        currentStatuses = currentStatuses.filter((s) => s !== statusKey);
      } else {
        currentStatuses.push(statusKey);
      }
      if (currentStatuses.length === 0) currentStatuses = ["ALL"];
      return { ...prev, deviceStatus: currentStatuses };
    });
  };

  // Counts how many devices would match a given filter selection —
  // used to give immediate feedback in the "Filters applied" toast.
  const countFilteredDevices = (filters) => {
    return devices.filter((d) => {
      let matchesHolderType = true;
      if (filters.holderType === "STUDENT") {
        matchesHolderType = d.assignment_category === "STUDENT";
      } else if (filters.holderType === "STAFF") {
        matchesHolderType = d.assignment_category === "OFFICIAL";
      }

      let matchesCohort = true;
      if (filters.holderType === "STUDENT" && filters.cohortName !== "ALL") {
        matchesCohort =
          d.assignment_category === "STUDENT" &&
          d.cohort_name === filters.cohortName;
      }

      let matchesStatus = true;
      if (!filters.deviceStatus.includes("ALL") && filters.deviceStatus.length > 0) {
        matchesStatus = filters.deviceStatus.includes(d.status);
      }

      return matchesHolderType && matchesCohort && matchesStatus;
    }).length;
  };

  // How many filter groups are currently active (used for the badge + Clear button)
  const activeFilterCount = useMemo(() => {
    let count = 0;
    if (filterSelections.holderType !== "ALL") count++;
    if (filterSelections.holderType === "STUDENT" && filterSelections.cohortName !== "ALL") count++;
    if (!filterSelections.deviceStatus.includes("ALL")) count++;
    return count;
  }, [filterSelections]);

  const DEFAULT_FILTERS = { holderType: "ALL", cohortName: "ALL", deviceStatus: ["ALL"] };

  const handleGenerateReport = () => {
    setCurrentWorkspaceView("inventory");
    setActiveFilters({ ...filterSelections });
    const matchCount = countFilteredDevices(filterSelections);
    showToastNotification(
      `Filters applied — ${matchCount} device${matchCount === 1 ? "" : "s"} loaded.`,
    );
  };

  const handleClearFilters = () => {
    setFilterSelections(DEFAULT_FILTERS);
    setActiveFilters(DEFAULT_FILTERS);
    showToastNotification(`Filters cleared — showing all ${devices.length} devices.`);
  };

  const handleLoadMovementWorkspace = async () => {
    setIsLoading(true);
    try {
      const res = await fetch(
        `${API_BASE}/tabs/movement-report?fromCohort=${movementForm.fromCohort}&toCohort=${movementForm.toCohort}`,
      );
      const result = await res.json();

      if (!result.success || result.data.length === 0) {
        showToastNotification(
          "No movement transfer log records found for selection range.",
        );
        setMovementReportData([]);
        return;
      }

      setMovementReportData(result.data);
      setCurrentWorkspaceView("movement");
      showToastNotification(
        `Loaded ${result.data.length} movement log tracking entries.`,
      );
    } catch (e) {
      console.error(e);
      showToastNotification("Error processing movement reports generation.");
    } finally {
      setIsLoading(false);
    }
  };

  const handleExportExcel = () => {
    if (activeDatasetRows.length === 0) {
      alert("No data to export");
      return;
    }

    let outputRows = [];
    if (currentWorkspaceView === "movement") {
      outputRows = activeDatasetRows.map((m) => ({
        "Serial Number": m.serial_number,
        "Inventory ID": m.inventory_id || "—",
        "Brand Name": m.brand_name,
        Model: m.model,
        "Previous Holder Student": m.previous_holder,
        "From Cohort Branch": m.from_cohort,
        "New Holder Student Received": m.new_holder,
        "To Cohort Branch Received": m.to_cohort,
        "Transfer Transaction Date": new Date(m.moved_at).toLocaleDateString(),
      }));
    } else {
      outputRows = activeDatasetRows.map((d) => ({
        "SERIAL NUMBER": d.serial_number,
        "IMEI": d.imei || "",
        "INVENTORY ID": d.inventory_id || "",
        "BRAND NAME": d.brand_name,
        "MODEL NAME": d.model,
        "ENROLMENT ID": d.enr_id || "",
        "STATUS": d.status,
        "REMARK": d.remarks || "",
        "ASSIGNED DATE": "",
        "RETURN DATE": "",
      }));
    }

    const ws = XLSX.utils.json_to_sheet(outputRows);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(
      wb,
      ws,
      currentWorkspaceView === "movement"
        ? "Cohort_Movements"
        : "Filtered_Inventory",
    );
    XLSX.writeFile(
      wb,
      currentWorkspaceView === "movement"
        ? "Tablet_Cohort_Movement_Logs.xlsx"
        : "Filtered_Tablet_Report.xlsx",
    );
  };

  const downloadSampleXLS = () => {
    const sample = [
      { "SERIAL NUMBER": "SN101", "IMEI": "354678", "INVENTORY ID": "INV-1", "BRAND NAME": "Samsung", "MODEL NAME": "Tab A8", "ENROLMENT ID": "202201", "STATUS": "LOST", "REMARK": "BULK", "ASSIGNED DATE": "2026-01-01", "RETURN DATE": "2026-05-01" },
      { "SERIAL NUMBER": "SN101", "IMEI": "354678", "INVENTORY ID": "INV-1", "BRAND NAME": "Samsung", "MODEL NAME": "Tab A9", "ENROLMENT ID": "202201", "STATUS": "ASSIGNED", "REMARK": "BULK", "ASSIGNED DATE": "2026-01-02", "RETURN DATE": "" },
      { "SERIAL NUMBER": "SN102", "IMEI": "354679", "INVENTORY ID": "INV-3", "BRAND NAME": "Samsung", "MODEL NAME": "Tab A10", "ENROLMENT ID": "", "STATUS": "IN OFFICE", "REMARK": "BULK", "ASSIGNED DATE": "2026-01-03", "RETURN DATE": "" },
      { "SERIAL NUMBER": "SN103", "IMEI": "354679", "INVENTORY ID": "INV-4", "BRAND NAME": "Samsung", "MODEL NAME": "Tab A11", "ENROLMENT ID": "202204", "STATUS": "RETURNED", "REMARK": "BULK", "ASSIGNED DATE": "2026-01-04", "RETURN DATE": "2026-05-01" }
    ];
    const ws = XLSX.utils.json_to_sheet(sample);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "Template");
    XLSX.writeFile(wb, "Template.xlsx");
  };

  const handleFileChange = (e) => {
    const file = e.target.files[0];
    if (file) {
      setBulkFile(file);
      setUploadError(null);
    }
  };

  const excelDateToJSDate = (serial) => {
    if (!serial) return null;
    if (typeof serial === "string" && serial.includes("-")) return serial;
    const utc_days = Math.floor(serial - 25569);
    const date = new Date(utc_days * 86400 * 1000);
    return date.toISOString().split("T")[0];
  };

  const processBulkUpload = async () => {
    if (!bulkFile || !currentUserId) return;
    setIsUploading(true);
    setUploadError(null);

    const reader = new FileReader();
    reader.onload = async (e) => {
      try {
        const data = new Uint8Array(e.target.result);
        const workbook = XLSX.read(data, { type: "array" });
        const worksheet = workbook.Sheets[workbook.SheetNames[0]];
        const json = XLSX.utils.sheet_to_json(worksheet);

        const normalizedJson = json.map(row => {
          const normalized = {};
          Object.keys(row).forEach(key => {
            normalized[key.trim()] = row[key];
          });
          return normalized;
        });

        if (normalizedJson.length === 0) {
          setUploadError(
            <div>
              <strong style={{ color: "#dc2626" }}>❌ File is empty</strong>
              <p style={{ marginTop: "6px", color: "#374151" }}>The uploaded Excel file has no data rows.</p>
            </div>
          );
          setIsUploading(false);
          return;
        }

        const headers = Object.keys(normalizedJson[0]);
        const hasStatus = headers.includes("STATUS");
        const statusVariant = headers.find(h =>
          h !== "STATUS" && h.replace(/\s/g, "").toUpperCase().startsWith("STAT")
        );
        if (!hasStatus && statusVariant) {
          setUploadError(
            <div style={{ textAlign: "left" }}>
              <strong style={{ color: "#dc2626" }}>❌ Column Header Typo — Upload Blocked</strong>
              <p style={{ marginTop: "8px", color: "#374151" }}>
                Found column <strong>"{statusVariant}"</strong> but expected <strong>"STATUS"</strong>.
              </p>
              <p style={{ fontSize: "0.82rem", color: "#6b7280", marginTop: "4px" }}>
                Open the file, fix the header spelling to <strong>STATUS</strong>, save and re-upload.
              </p>
            </div>
          );
          setIsUploading(false);
          return;
        }

        const readField = (item, ...keys) => {
          for (const key of keys) {
            if (item[key] !== undefined && item[key] !== null && item[key] !== "") {
              return item[key];
            }
          }
          return undefined;
        };

        const dataWarnings = [];
        normalizedJson.forEach((item, idx) => {
          const rowNum = idx + 2;
          const invId = (readField(item, "INVENTORY ID", "Inventory ID") ?? "").toString();
          const enrId = (readField(item, "ENROLMENT ID", "Enrolment ID") ?? "").toString();
          const serial = (readField(item, "SERIAL NUMBER", "Serial Number") ?? "").toString();

          if (invId && invId !== invId.trim())
            dataWarnings.push(`Row ${rowNum}: INVENTORY ID "${invId.trim()}" has extra spaces/newlines (auto-cleaned)`);
          if (enrId && enrId !== enrId.trim())
            dataWarnings.push(`Row ${rowNum}: ENROLMENT ID "${enrId.trim()}" has extra spaces (auto-cleaned)`);
          if (serial && serial !== serial.trim())
            dataWarnings.push(`Row ${rowNum}: SERIAL NUMBER "${serial.trim()}" has extra spaces (auto-cleaned)`);
        });

        const STATUS_TYPO_MAP = {
          "ASIGNED": "ASSIGNED", "ASSIGEND": "ASSIGNED", "ASSIGED": "ASSIGNED", "ASSIGND": "ASSIGNED",
          "RETUREND": "RETURNED", "RETRUNED": "RETURNED", "RETUNRED": "RETURNED",
          "DAMGED": "DAMAGED", "DAMMAGED": "DAMAGED",
          "IN_OFICE": "IN_OFFICE", "INOFFICE": "IN_OFFICE", "IN OFFICE": "IN_OFFICE", "IN_OFFICE": "IN_OFFICE",
        };

        const formatted = normalizedJson.map((item, idx) => {
          const serialRaw = readField(item, "SERIAL NUMBER", "Serial Number") ?? "";
          const imeiRaw = readField(item, "IMEI") ?? "";
          const invRaw = readField(item, "INVENTORY ID", "Inventory ID") ?? "";
          const brandRaw = readField(item, "BRAND NAME", "Brand Name") ?? "Unknown";
          const modelRaw = readField(item, "MODEL NAME", "Model Name", "Model") ?? "Unknown";
          const enrRaw = readField(item, "ENROLMENT ID", "Enrolment ID") ?? "";
          const statusRaw = readField(item, "STATUS") ?? "IN_OFFICE";
          const assignedDateRaw = readField(item, "ASSIGNED DATE");
          const returnDateRaw = readField(item, "RETURN DATE");
          const remarkRaw = readField(item, "REMARK", "Remark") ?? "";

          const enrId = enrRaw?.toString().trim();
          const rawStatus = statusRaw.toString().toUpperCase().trim().replace(/\s+/g, "_");
          const status = STATUS_TYPO_MAP[rawStatus] || rawStatus;

          return {
            serial_number: serialRaw?.toString().trim().toUpperCase() || "",
            imei: imeiRaw?.toString().trim() || "",
            inventory_id: invRaw?.toString().trim().replace(/\s+/g, " ") || "",
            brand_name: brandRaw?.toString() || "Unknown",
            model_name: modelRaw?.toString() || "Unknown",
            enr_id: enrId || "",
            status: status,
            assigned_date: excelDateToJSDate(assignedDateRaw) || new Date().toISOString().split("T")[0],
            return_date: returnDateRaw ? excelDateToJSDate(returnDateRaw) : null,
            remarks: remarkRaw?.toString() || "",
            created_by: currentUserId,
            rowNumber: idx + 2,
          };
        });

        const emptySerialRows = formatted.filter(row => !row.serial_number);
        if (emptySerialRows.length > 0) {
          setUploadError(
            <div style={{ textAlign: "left" }}>
              <strong style={{ color: "#dc2626" }}>❌ Missing Serial Numbers — Upload Blocked ({emptySerialRows.length} row{emptySerialRows.length > 1 ? "s" : ""})</strong>
              <ul style={{ margin: "8px 0 0 0", paddingLeft: "20px", color: "#7f1d1d" }}>
                {emptySerialRows.slice(0, 10).map((row, i) => (
                  <li key={i} style={{ marginBottom: "4px" }}>Row {row.rowNumber}: Serial Number is empty.</li>
                ))}
                {emptySerialRows.length > 10 && <li style={{ color: "#6b7280" }}>...and {emptySerialRows.length - 10} more rows.</li>}
              </ul>
              <p style={{ marginTop: "10px", color: "#374151", fontSize: "0.85rem" }}>
                Make sure the column is named exactly <strong>SERIAL NUMBER</strong>.
              </p>
            </div>
          );
          setIsUploading(false);
          return;
        }

        const VALID_STATUSES = ["ASSIGNED", "RETURNED", "DAMAGED", "LOST", "IN_OFFICE"];
        const invalidStatusRows = formatted.filter(row => !VALID_STATUSES.includes(row.status));
        if (invalidStatusRows.length > 0) {
          setUploadError(
            <div style={{ textAlign: "left" }}>
              <strong style={{ color: "#dc2626" }}>❌ Invalid Status Values — Upload Blocked ({invalidStatusRows.length} row{invalidStatusRows.length > 1 ? "s" : ""})</strong>
              <ul style={{ margin: "8px 0 0 0", paddingLeft: "20px", color: "#7f1d1d" }}>
                {invalidStatusRows.slice(0, 15).map((row, i) => (
                  <li key={i} style={{ marginBottom: "4px" }}>
                    Row {row.rowNumber}: <strong>"{row.status}"</strong> — (Serial: {row.serial_number})
                  </li>
                ))}
                {invalidStatusRows.length > 15 && <li style={{ color: "#6b7280" }}>...and {invalidStatusRows.length - 15} more rows.</li>}
              </ul>
              <p style={{ marginTop: "10px", color: "#374151", fontSize: "0.85rem" }}>Valid statuses: <strong>ASSIGNED, RETURNED, DAMAGED, LOST, IN OFFICE</strong></p>
            </div>
          );
          setIsUploading(false);
          return;
        }

        const duplicateErrors = [];
        const seenSerialStatus = {};
        const seenInventoryId = {};

        formatted.forEach((row) => {
          const sn = row.serial_number;
          const inv = row.inventory_id;
          const enr = row.enr_id;
          const status = row.status;

          if (sn) {
            const dupKey = `${sn}|${status}|${enr}`;
            if (seenSerialStatus[dupKey]) {
              duplicateErrors.push(`Rows ${seenSerialStatus[dupKey]} & ${row.rowNumber}: Exact duplicate — Serial "${sn}", Status "${status}", Student "${enr || "none"}". Remove one row.`);
            } else {
              seenSerialStatus[dupKey] = row.rowNumber;
            }
          }

          if (inv && inv !== "") {
            if (seenInventoryId[inv] && seenInventoryId[inv].serial !== sn) {
              duplicateErrors.push(`Rows ${seenInventoryId[inv].rowNum} & ${row.rowNumber}: Inventory ID "${inv}" used for two different tablets ("${seenInventoryId[inv].serial}" and "${sn}").`);
            } else if (!seenInventoryId[inv]) {
              seenInventoryId[inv] = { serial: sn, rowNum: row.rowNumber };
            }
          }
        });

        if (duplicateErrors.length > 0) {
          setUploadError(
            <div style={{ textAlign: "left" }}>
              <strong style={{ color: "#dc2626" }}>❌ Duplicate Data — Upload Blocked ({duplicateErrors.length} issue{duplicateErrors.length > 1 ? "s" : ""})</strong>
              <ul style={{ margin: "8px 0 0 0", paddingLeft: "20px", color: "#7f1d1d" }}>
                {duplicateErrors.map((err, i) => <li key={i} style={{ marginBottom: "4px" }}>{err}</li>)}
              </ul>
            </div>
          );
          setIsUploading(false);
          return;
        }

        const conflictErrors = [];
        const tabHolderMap = {};

        formatted.forEach((row) => {
          const sn = row.serial_number;
          const enr = row.enr_id;
          const status = row.status;
          if (!sn) return;

          const current = tabHolderMap[sn];

          if (status === "ASSIGNED" && enr) {
            if (current && current.enrId !== enr) {
              conflictErrors.push(`Row ${row.rowNumber}: Tab "${sn}" assigned to Student ${enr} but still held by Student ${current.enrId} (Row ${current.rowNum}). Add a RETURNED row first.`);
            } else {
              tabHolderMap[sn] = { enrId: enr, rowNum: row.rowNumber };
            }
          } else if (["RETURNED", "DAMAGED", "LOST", "IN_OFFICE"].includes(status)) {
            tabHolderMap[sn] = null;
          }
        });

        if (conflictErrors.length > 0) {
          setUploadError(
            <div style={{ textAlign: "left" }}>
              <strong style={{ color: "#dc2626" }}>❌ Assignment Conflict — Upload Blocked ({conflictErrors.length} issue{conflictErrors.length > 1 ? "s" : ""})</strong>
              <ul style={{ margin: "8px 0 0 0", paddingLeft: "20px", color: "#7f1d1d" }}>
                {conflictErrors.map((err, i) => <li key={i} style={{ marginBottom: "4px" }}>{err}</li>)}
              </ul>
            </div>
          );
          setIsUploading(false);
          return;
        }

        if (dataWarnings.length > 0) {
          setUploadError(
            <div style={{ textAlign: "left" }}>
              <strong style={{ color: "#b45309" }}>⚠️ Auto-cleaned {dataWarnings.length} row(s) — upload proceeding:</strong>
              <ul style={{ margin: "5px 0 0 0", paddingLeft: "20px", color: "#92400e" }}>
                {dataWarnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            </div>
          );
        }

        let res;
        try {
          res = await fetch(`${API_BASE}/tabs/bulk`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ devices: formatted }),
          });
        } catch (networkErr) {
          setUploadError(<div><strong style={{ color: "#dc2626" }}>❌ Network Error</strong><p style={{ marginTop: "6px", color: "#374151" }}>Could not reach the server.</p></div>);
          setIsUploading(false);
          return;
        }

        const contentType = res.headers.get("content-type") || "";
        if (!contentType.includes("application/json")) {
          setUploadError(<div><strong style={{ color: "#dc2626" }}>❌ Server Error ({res.status})</strong><p style={{ marginTop: "6px", color: "#374151" }}>{res.status === 404 ? "Upload endpoint not found (404)." : `Unexpected server response (${res.status}).`}</p></div>);
          setIsUploading(false);
          return;
        }

        const result = await res.json();

        if (!res.ok) {
          if (result.errors && Array.isArray(result.errors)) {
            setUploadError(
              <div style={{ textAlign: "left" }}>
                <strong style={{ color: "#dc2626" }}>❌ Upload Failed — {result.errors.length} issue{result.errors.length > 1 ? "s" : ""} found:</strong>
                <ul style={{ margin: "8px 0 0 0", paddingLeft: "20px", color: "#7f1d1d" }}>
                  {result.errors.map((err, i) => <li key={i} style={{ marginBottom: "6px" }}>{err}</li>)}
                </ul>
                <p style={{ marginTop: "10px", fontSize: "0.82rem", color: "#6b7280" }}>Upload fully rolled back — no data saved.</p>
              </div>
            );
          } else {
            setUploadError(<div style={{ textAlign: "left" }}><strong style={{ color: "#dc2626" }}>❌ Upload Failed</strong><p style={{ marginTop: "6px", color: "#374151" }}>{result.message || "Server error."}</p></div>);
          }
        } else {
          setIsBulkModalOpen(false);
          setBulkFile(null);
          fetchData();
          showToastNotification(`Successfully processed ${result.count || 0} devices.`);
        }

      } catch (err) {
        setUploadError(<div><strong style={{ color: "#dc2626" }}>❌ Unexpected Error</strong><p style={{ marginTop: "6px", color: "#374151" }}>Make sure it's a valid .xlsx file and try again.</p></div>);
      } finally {
        setIsUploading(false);
      }
    };
    reader.readAsArrayBuffer(bulkFile);
  };

  const handleAction = useCallback(
    async (type, device) => {
      setSelectedDevice(device);
      if (type === "STATUS") {
        setStatusForm({
          status: device.status,
          assignment_type: "STUDENT",
          student_id: "",
          official_user_id: "",
          remarks: device.remarks || "",
          transaction_date: new Date().toISOString().split("T")[0],
        });
        setIsStatusModalOpen(true);
      } else if (type === "HISTORY") {
        const res = await fetch(`${API_BASE}/tabs/${device.tab_id}/history`);
        const result = await res.json();
        if (result.success) {
          setHistoryData(result.data);
          setIsHistoryModalOpen(true);
        }
      } else if (type === "DELETE" && window.confirm(`Delete ${device.serial_number}?`)) {
        await fetch(`${API_BASE}/tabs/${device.tab_id}`, { method: "DELETE" });
        fetchData();
      }
    },
    [fetchData],
  );

  const handleSaveBrand = async () => {
    if (!currentUserId) return;
    const res = await fetch(`${API_BASE}/tabs/brands`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...newBrandForm, created_by: currentUserId }),
    });
    const result = await res.json();
    if (result.success) {
      fetchData();
      setIsAddingNewBrand(false);
      setAddForm((prev) => ({ ...prev, brand_id: result.data.brand_id }));
    }
  };

  const handleAddSubmit = async (e) => {
    e.preventDefault();
    if (!currentUserId) return;
    const res = await fetch(`${API_BASE}/tabs`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...addForm, created_by: currentUserId }),
    });
    if (res.ok) {
      setIsAddModalOpen(false);
      fetchData();
      setAddForm({ serial_number: "", brand_id: "", imei: "", inventory_id: "", tab_purchase_date: "", remarks: "" });
      showToastNotification("Tablet added successfully!");
    } else {
      const errRes = await res.json();
      showToastNotification(errRes.message || "Failed to add device");
    }
  };

  const handleStatusSubmit = async (e) => {
    e.preventDefault();
    if (!currentUserId) { showToastNotification("Session expired. Please log in again."); return; }

    if (selectedDevice.status === "IN_OFFICE" && statusForm.status === "RETURNED") {
      showToastNotification("Invalid Operation: Tablet is already In Office."); return;
    }
    if ((selectedDevice.status === "LOST" || selectedDevice.status === "DAMAGED") && statusForm.status === "RETURNED") {
      showToastNotification("Invalid Operation: Clear the device from Lost/Damaged state before returning."); return;
    }
    if (selectedDevice.status === "ASSIGNED" && statusForm.status === "ASSIGNED") {
      showToastNotification("Invalid Operation: This device is already assigned. Please mark it as 'Returned' first."); return;
    }
    if ((selectedDevice.status === "DAMAGED" || selectedDevice.status === "LOST") && statusForm.status === "ASSIGNED") {
      if (!window.confirm("Warning: This device was marked Lost/Damaged. Are you sure you want to re-assign it?")) return;
    }
    if ((statusForm.status === "DAMAGED" || statusForm.status === "LOST") && (!statusForm.remarks || statusForm.remarks.trim() === "")) {
      showToastNotification(`Required Field: Please provide remarks for ${statusForm.status} status.`); return;
    }

    try {
      const payload = {
        ...statusForm,
        user_id: currentUserId,
        student_id: statusForm.assignment_type === "STUDENT" ? statusForm.student_id : null,
        official_user_id: statusForm.assignment_type === "OFFICIAL" ? statusForm.official_user_id : null,
        transaction_date: statusForm.transaction_date || new Date().toISOString().split("T")[0],
      };

      const res = await fetch(`${API_BASE}/tabs/${selectedDevice.tab_id}/status`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (res.ok) {
        showToastNotification("Status updated successfully!");
        setIsStatusModalOpen(false);
        setStudentSearch("");
        setStaffSearch("");
        fetchData();
      } else {
        const err = await res.json();
        showToastNotification(err.message || "Update failed.");
      }
    } catch (error) {
      console.error(error);
      showToastNotification("An error occurred.");
    }
  };

  return (
    <main className={styles.container}>
      <Breadcrumbs
        path={["Admin", "Academics", "Tabs"]}
        nonLinkSegments={["Admin", "Academics", "Tabs"]}
      />

      {toastMessage && (
        <div className={styles.toastPopupBanner}>
          <Check size={16} /> <span>{toastMessage}</span>
        </div>
      )}

      {/* ── HEADER ─────────────────────────────────────────── */}
      <header className={styles.header}>
        <div className={styles.headerContent}>
          <div className={styles.headerTitle}>
            <div className={styles.headerIconWrap}>
              <Tablet size={22} className={styles.headerIcon} />
            </div>
            <div>
              <h1>Tablet Inventory</h1>
              <p>
                {currentWorkspaceView === "movement"
                  ? "Reviewing historical cohort log pathways"
                  : "Monitor and track device lifecycle"}
              </p>
            </div>
          </div>
          <div className={styles.headerActions}>
            <button className={styles.btnSecondary} onClick={() => setIsBulkModalOpen(true)}>
              <Upload size={16} /> Bulk Upload
            </button>
            <button className={styles.btnPrimary} onClick={() => setIsAddModalOpen(true)}>
              <Plus size={16} /> Add Device
            </button>
          </div>
        </div>

        <div className={styles.statsGrid}>
          <StatsCard
            label="In Office / Ready"
            value={parseInt(tabStats.in_office || 0) + parseInt(tabStats.returned_awaiting || 0)}
            color="success"
            subItems={[
              { icon: Check, count: tabStats.in_office || 0, text: "IN Office (New)" },
              { icon: Clock, count: tabStats.returned_awaiting || 0, text: "Returned" },
            ]}
          />
          <StatsCard
            label="In Use (Total)"
            value={parseInt(tabStats.student_assigned || 0) + parseInt(tabStats.official_assigned || 0)}
            color="info"
            subItems={[
              { icon: GraduationCap, count: tabStats.student_assigned || 0, text: "Students" },
              { icon: UserCog, count: tabStats.official_assigned || 0, text: "Staff" },
            ]}
          />
          <StatsCard
            label="Action Required"
            value={parseInt(tabStats.damaged || 0) + parseInt(tabStats.lost || 0)}
            color="critical"
            subItems={[
              { icon: AlertCircle, count: tabStats.damaged || 0, text: "Damaged" },
              { icon: X, count: tabStats.lost || 0, text: "Lost" },
            ]}
          />
          <StatsCard label="Total Assets" value={tabStats.total} color="neutral" />
        </div>
      </header>

      {/* ── FILTER WORKSPACE ───────────────────────────────── */}
      <div className={styles.filterWorkspace}>

        {/* Row 1: Search + View controls */}
        <div className={styles.workspaceTopBar}>
          <div className={styles.searchWrapper}>
            <Search size={16} className={styles.searchIcon} />
            <input
              type="text"
              placeholder={
                currentWorkspaceView === "movement"
                  ? "Filter results by name or serial..."
                  : "Search holder, ENR ID, serial or brand..."
              }
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <div className={styles.topBarRight}>
            <button className={styles.btnExportXls} onClick={handleExportExcel}>
              <FileSpreadsheet size={15} /> Export Sheet
            </button>
            <div className={styles.viewToggle}>
              <button onClick={() => setViewMode("grid")} className={viewMode === "grid" ? styles.active : ""}>Grid</button>
              <button onClick={() => setViewMode("table")} className={viewMode === "table" ? styles.active : ""}>Table</button>
            </div>
          </div>
        </div>

        {/* ── SECTION A: Inventory Filters ─────────────────── */}
        <div className={styles.filterSection}>
          <div className={styles.filterSectionHeader}>
            <Filter size={13} />
            <span>Filter Inventory</span>
            {activeFilterCount > 0 && (
              <span className={styles.filterCountBadge}>
                {activeFilterCount} active
              </span>
            )}
          </div>

          <div className={styles.filterBody}>
            {/* Holder type */}
            <div className={styles.filterGroup}>
              <label className={styles.filterGroupLabel}>Holder Type</label>
              <div className={styles.pillRow}>
                {[
                  { key: "ALL", label: "All Holders" },
                  { key: "STUDENT", label: "Students", icon: GraduationCap },
                  { key: "STAFF", label: "Staff", icon: UserCog },
                ].map(({ key, label, icon: Icon }) => (
                  <button
                    key={key}
                    type="button"
                    className={filterSelections.holderType === key ? styles.pillActive : styles.pillInactive}
                    onClick={() => setFilterSelections({ ...filterSelections, holderType: key })}
                  >
                    {Icon && <Icon size={14} />} {label}
                  </button>
                ))}
              </div>
            </div>

            {/* Status */}
            <div className={styles.filterGroup}>
              <label className={styles.filterGroupLabel}>Device Status <span className={styles.multiHint}>(multi-select)</span></label>
              <div className={styles.pillRow}>
                <button
                  type="button"
                  className={filterSelections.deviceStatus.includes("ALL") ? styles.pillActive : styles.pillInactive}
                  onClick={() => handleStatusPillClick("ALL")}
                >
                  All
                </button>
                {ALL_STATUSES.map((s) => (
                  <button
                    key={s}
                    type="button"
                    className={`${filterSelections.deviceStatus.includes(s) ? styles.pillActive : styles.pillInactive} ${styles[`statusPill--${STATUS_CONFIG[s].color}`]}`}
                    onClick={() => handleStatusPillClick(s)}
                  >
                    {STATUS_CONFIG[s].label}
                  </button>
                ))}
              </div>
            </div>

            {/* Cohort dropdown — only when STUDENT selected */}
            {filterSelections.holderType === "STUDENT" && (
              <div className={styles.filterGroup}>
                <label className={styles.filterGroupLabel}>Cohort Group</label>
                <div className={styles.selectWrapperCustomIcon}>
                  <Layers size={14} className={styles.customSelectLeadIcon} />
                  <select
                    value={filterSelections.cohortName}
                    onChange={(e) => setFilterSelections({ ...filterSelections, cohortName: e.target.value })}
                  >
                    <option value="ALL">All Cohorts</option>
                    {cohorts.map((c) => (
                      <option key={c.cohort_number} value={c.cohort_name}>{c.cohort_name}</option>
                    ))}
                  </select>
                </div>
              </div>
            )}

            {/* Generate button — right-aligned inside the filter section */}
            <div className={styles.filterApplyRow}>
              {activeFilterCount > 0 && (
                <button
                  type="button"
                  className={styles.btnClearFilters}
                  onClick={handleClearFilters}
                  title="Reset all filters"
                >
                  <X size={14} /> Clear Filters
                </button>
              )}
              <button className={styles.btnGenerateReport} onClick={handleGenerateReport}>
                <RefreshCw size={14} /> Apply Filters
              </button>
            </div>
          </div>
        </div>

        {/* ── SECTION B: Movement Report ───────────────────── */}
        <div className={styles.movementSection}>
          <div className={styles.filterSectionHeader}>
            <RefreshCw size={13} />
            <span>Cohort Movement Report</span>
          </div>
          <div className={styles.movementBody}>
            <p className={styles.movementHint}>Track tablet transfers between cohorts. Select a source and destination cohort, then load the movement log.</p>
            <div className={styles.movementControls}>
              <div className={styles.movementSelectPair}>
                <div className={styles.widgetSelectorField}>
                  <label className={styles.filterGroupLabel}>From Cohort</label>
                  <select
                    value={movementForm.fromCohort}
                    onChange={(e) => setMovementForm({ ...movementForm, fromCohort: e.target.value })}
                  >
                    <option value="ALL">All Cohorts</option>
                    {cohorts.map((c) => (
                      <option key={c.cohort_number} value={c.cohort_name}>{c.cohort_name}</option>
                    ))}
                  </select>
                </div>
                <ArrowRight size={16} className={styles.movementArrow} />
                <div className={styles.widgetSelectorField}>
                  <label className={styles.filterGroupLabel}>To Cohort</label>
                  <select
                    value={movementForm.toCohort}
                    onChange={(e) => setMovementForm({ ...movementForm, toCohort: e.target.value })}
                  >
                    <option value="ALL">All Cohorts</option>
                    {cohorts.map((c) => (
                      <option key={c.cohort_number} value={c.cohort_name}>{c.cohort_name}</option>
                    ))}
                  </select>
                </div>
              </div>
              <button className={styles.btnLoadMovement} type="button" onClick={handleLoadMovementWorkspace}>
                <RefreshCw size={14} /> Load Movement Log
              </button>
            </div>
          </div>
        </div>

      </div>
      {/* ── END FILTER WORKSPACE ──────────────────────────── */}

      {isLoading ? (
        <div className={styles.loader}>Loading...</div>
      ) : (
        <>
          {viewMode === "grid" ? (
            <section className={styles.gridView}>
              {currentData.length === 0 ? (
                <div className={styles.noDataOption} style={{ gridColumn: "1/-1", padding: "4rem" }}>
                  No matching dataset entries found.
                </div>
              ) : currentWorkspaceView === "movement" ? (
                currentData.map((movement, idx) => (
                  <div key={idx} className={styles.deviceCard} style={{ borderLeft: "4px solid #0d9488" }}>
                    <div className={styles.cardHeader}>
                      <span className={`${styles.badge} ${styles["badge--info"]}`}>
                        <RefreshCw size={14} /> <span>Movement Log</span>
                      </span>
                      <small style={{ fontWeight: "700", color: "var(--text-muted)" }}>
                        {new Date(movement.moved_at).toLocaleDateString()}
                      </small>
                    </div>
                    <div className={styles.cardBody}>
                      <div className={styles.serialNumber}>SN: {movement.serial_number}</div>
                      <div className={styles.invId}>Inv ID: {movement.inventory_id || "—"}</div>
                      <div className={styles.deviceModel}><strong>{movement.brand_name}</strong> {movement.model}</div>
                      <div className={styles.assignedInfo} style={{ background: "#f0fdfa", border: "1px solid #ccfbf1" }}>
                        <div style={{ marginBottom: "6px" }}>
                          <small style={{ color: "var(--text-muted)", display: "block" }}>From Cohort Holder:</small>
                          <strong>{movement.previous_holder}</strong> <small>({movement.from_cohort})</small>
                        </div>
                        <div>
                          <small style={{ color: "var(--text-muted)", display: "block" }}>Transferred To:</small>
                          <strong style={{ color: "#0d9488" }}>{movement.new_holder}</strong> <small>({movement.to_cohort})</small>
                        </div>
                      </div>
                    </div>
                  </div>
                ))
              ) : (
                currentData.map((device) => (
                  <div key={device.tab_id} className={styles.deviceCard}>
                    <div className={styles.cardHeader}>
                      <StatusBadgeComp status={device.status} />
                      <ActionMenu device={device} onAction={handleAction} />
                    </div>
                    <div className={styles.cardBody}>
                      <div className={styles.serialNumber}>SN: {device.serial_number}</div>
                      <div className={styles.invId}>Inv ID: {device.inventory_id || "—"}</div>
                      <div className={styles.deviceModel}><strong>{device.brand_name}</strong> {device.model}</div>
                      {device.assigned_to && (
                        <div className={styles.assignedInfo}>
                          <span className={styles.label}>
                            {["ASSIGNED", "IN_OFFICE"].includes(device.status) ? "Held By:" : "Previously Held By:"}
                          </span>
                          <span className={styles.value}>{device.assigned_to} {device.enr_id ? `(${device.enr_id})` : ""}</span>
                        </div>
                      )}
                      {(device.cohort_name || device.batch_name) && (
                        <div className={styles.academicMetaInfo}>
                          <small>{device.cohort_name || "No Cohort"} | {device.batch_name || "No Batch"}</small>
                        </div>
                      )}
                      <div className={styles.imeiText}>IMEI: {device.imei || "—"}</div>
                    </div>
                  </div>
                ))
              )}
            </section>
          ) : (
            <section className={styles.tableView}>
              <table className={styles.table}>
                {currentWorkspaceView === "movement" ? (
                  <>
                    <thead>
                      <tr>
                        <th>Serial / Inv ID</th>
                        <th>Brand / Model</th>
                        <th>Previous Holder</th>
                        <th>From Cohort</th>
                        <th>New Holder</th>
                        <th>To Cohort</th>
                        <th className={styles.alignRight}>Transfer Date</th>
                      </tr>
                    </thead>
                    <tbody>
                      {currentData.length === 0 ? (
                        <tr>
                          <td colSpan={7} className={styles.noDataOption} style={{ textAlign: "center", padding: "3rem" }}>
                            No structural history entries match selection criteria.
                          </td>
                        </tr>
                      ) : (
                        currentData.map((m, idx) => (
                          <tr key={idx} className={styles.tableRow}>
                            <td className={styles.serial}><strong>{m.serial_number}</strong><br /><small>{m.inventory_id}</small></td>
                            <td><strong>{m.brand_name}</strong><br /><small>{m.model}</small></td>
                            <td style={{ fontWeight: "600" }}>{m.previous_holder}</td>
                            <td><span className={styles.categoryBadge}>{m.from_cohort}</span></td>
                            <td style={{ fontWeight: "600", color: "#0d9488" }}>{m.new_holder}</td>
                            <td><span className={styles.categoryBadge} style={{ background: "#e0f2fe", color: "#0369a1" }}>{m.to_cohort}</span></td>
                            <td className={styles.alignRight} style={{ fontWeight: "600" }}>{new Date(m.moved_at).toLocaleDateString()}</td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </>
                ) : (
                  <>
                    <thead>
                      <tr>
                        <th>Serial / Inv ID</th>
                        <th>Brand / Model</th>
                        <th>IMEI</th>
                        <th>Status</th>
                        <th>Currently With</th>
                        <th>Cohort / Batch</th>
                        <th className={styles.alignRight}>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {currentData.map((device) => (
                        <tr key={device.tab_id} className={styles.tableRow}>
                          <td className={styles.serial}><strong>{device.serial_number}</strong><br /><small>{device.inventory_id}</small></td>
                          <td><strong>{device.brand_name}</strong><br /><small>{device.model}</small></td>
                          <td>{device.imei || "—"}</td>
                          <td style={{ fontWeight: "600", fontSize: "0.875rem" }}>{STATUS_CONFIG[device.status]?.label || device.status}</td>
                          <td>
                            <small style={{ display: "block", color: "var(--text-muted)", marginBottom: "2px" }}>
                              {["ASSIGNED", "IN_OFFICE"].includes(device.status) ? "Held By:" : "Previously Held By:"}
                            </small>
                            <strong>{device.assigned_to || "—"}</strong>
                            {device.enr_id && <small style={{ display: "block" }}>({device.enr_id})</small>}
                          </td>
                          <td>
                            <strong style={{ fontSize: "0.85rem" }}>{device.cohort_name || "—"}</strong><br />
                            <small style={{ color: "#666" }}>{device.batch_name || "—"}</small>
                          </td>
                          <td className={styles.alignRight}>
                            <ActionMenu device={device} onAction={handleAction} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </>
                )}
              </table>
            </section>
          )}

          <div className={styles.pagination}>
            <button disabled={currentPage === 1} onClick={() => setCurrentPage((p) => p - 1)} className={styles.pageBtn}>
              <ChevronLeft size={18} /> Previous
            </button>
            <span className={styles.pageInfo}>Page {currentPage} of {totalPages || 1}</span>
            <button disabled={currentPage === totalPages || totalPages === 0} onClick={() => setCurrentPage((p) => p + 1)} className={styles.pageBtn}>
              Next <ChevronRight size={18} />
            </button>
          </div>
        </>
      )}

      {/* BULK UPLOAD MODAL */}
      {isBulkModalOpen && (
        <div className={styles.modalOverlay}>
          <div className={`${styles.modal} ${styles.bulkModal}`}>
            <div className={styles.modalHeader}>
              <h2>Bulk Upload</h2>
              <button onClick={() => setIsBulkModalOpen(false)}><X size={18} /></button>
            </div>
            <div className={styles.modalBody}>
              <div style={{ background: '#f8fafc', padding: '15px', borderRadius: '8px', fontSize: '0.85rem', border: '1px solid #e2e8f0' }}>
                <strong style={{ display: 'block', marginBottom: '8px' }}>Important Rules:</strong>
                <ul style={{ margin: 0, paddingLeft: '20px', color: '#475569' }}>
                  <li>Statuses: ASSIGNED, RETURNED, LOST, DAMAGED, IN OFFICE.</li>
                  <li>For LOST/DAMAGED/RETURNED: If Return Date is blank, current date is used.</li>
                  <li>For IN OFFICE: Enrolment ID and Return Date must be empty.</li>
                  <li>For any reassignments make sure first tab is returned then assigned.</li>
                </ul>
              </div>
              <button onClick={downloadSampleXLS} className={styles.linkBtn}><Download size={14} /> Download Sample Template</button>
              <div className={styles.dropZone} onClick={() => fileInputRef.current.click()}>
                <input type="file" ref={fileInputRef} hidden accept=".xlsx, .xls" onChange={handleFileChange} />
                {bulkFile ? (
                  <div className={styles.fileSelected}><FileSpreadsheet size={32} /><span>{bulkFile.name}</span></div>
                ) : (
                  <><Upload size={32} /><span>Click to Upload Excel</span></>
                )}
              </div>
              {uploadError && <div className={styles.errorBox}><AlertCircle size={16} />{uploadError}</div>}
              <div className={styles.modalFooter}>
                <button className={styles.btnSecondary} onClick={() => setIsBulkModalOpen(false)}>Cancel</button>
                <button className={styles.btnPrimary} disabled={!bulkFile || isUploading} onClick={processBulkUpload}>
                  {isUploading ? "Uploading..." : "Start Upload"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ADD MODAL */}
      {isAddModalOpen && (
        <div className={styles.modalOverlay}>
          <div className={`${styles.modal} ${styles.addModal}`}>
            <div className={styles.modalHeader}>
              <h2>Add Device</h2>
              <button onClick={() => setIsAddModalOpen(false)}><X size={18} /></button>
            </div>
            <form onSubmit={handleAddSubmit} className={styles.modalBody}>
              <input placeholder="Serial Number *" required value={addForm.serial_number} onChange={(e) => setAddForm({ ...addForm, serial_number: e.target.value })} />
              <div className={styles.formGrid}>
                <input placeholder="IMEI *" required value={addForm.imei} onChange={(e) => setAddForm({ ...addForm, imei: e.target.value })} />
                <input placeholder="Inventory ID *" required value={addForm.inventory_id} onChange={(e) => setAddForm({ ...addForm, inventory_id: e.target.value })} />
              </div>
              <div className={styles.formGroup}>
                <div style={{ display: "flex", justifyContent: "space-between" }}>
                  <label>Brand & Model *</label>
                  <button type="button" className={styles.linkBtn} onClick={() => setIsAddingNewBrand(!isAddingNewBrand)}>
                    {isAddingNewBrand ? "Cancel" : "+ New Brand"}
                  </button>
                </div>
                {isAddingNewBrand ? (
                  <div className={styles.newBrandBox}>
                    <input placeholder="Brand *" required value={newBrandForm.brand_name} onChange={(e) => setNewBrandForm({ ...newBrandForm, brand_name: e.target.value })} />
                    <input placeholder="Model *" required value={newBrandForm.model_name} onChange={(e) => setNewBrandForm({ ...newBrandForm, model_name: e.target.value })} />
                    <button type="button" onClick={handleSaveBrand} className={styles.btnPrimary}>Save Brand</button>
                  </div>
                ) : (
                  <select required value={addForm.brand_id} onChange={(e) => setAddForm({ ...addForm, brand_id: e.target.value })}>
                    <option value="">-- Select Brand --</option>
                    {brands.map((b) => <option key={b.brand_id} value={b.brand_id}>{b.brand_name} - {b.model_name}</option>)}
                  </select>
                )}
              </div>
              <div className={styles.formGroup}>
                <label>Purchase Date</label>
                <input type="date" value={addForm.tab_purchase_date} onChange={(e) => setAddForm({ ...addForm, tab_purchase_date: e.target.value })} />
              </div>
              <label className={styles.modalLabel}>Description</label>
              <textarea placeholder="Device Description" rows={2} className={styles.modalTextarea} value={addForm.remarks} onChange={(e) => setAddForm({ ...addForm, remarks: e.target.value })} />
              <button type="submit" className={styles.btnPrimary}>Save Device</button>
            </form>
          </div>
        </div>
      )}

      {/* STATUS MODAL */}
      {isStatusModalOpen && selectedDevice && (
        <div className={styles.modalOverlay}>
          <div className={`${styles.modal} ${styles.statusModal}`}>
            <div className={styles.modalHeader}>
              <h2>Update Status: {selectedDevice.serial_number}</h2>
              <button onClick={() => { setIsStatusModalOpen(false); setStudentSearch(""); setStaffSearch(""); }}><X size={18} /></button>
            </div>
            <form onSubmit={handleStatusSubmit} className={styles.modalBody}>
              <div className={styles.formGroup}>
                <label>Device Status *</label>
                <select value={statusForm.status} onChange={(e) => setStatusForm({ ...statusForm, status: e.target.value })}>
                  {ALL_STATUSES.map((s) => <option key={s} value={s}>{STATUS_CONFIG[s].label}</option>)}
                </select>
              </div>

              {(statusForm.status === "RETURNED" || statusForm.status === "ASSIGNED" || statusForm.status === "DAMAGED" || statusForm.status === "LOST") && (
                <div className={styles.formGroup}>
                  <label style={{ color: 'var(--primary)', fontWeight: '700' }}>Transaction Date *</label>
                  <input type="date" required value={statusForm.transaction_date} onChange={e => setStatusForm({ ...statusForm, transaction_date: e.target.value })} />
                </div>
              )}

              {statusForm.status === "ASSIGNED" && (
                <>
                  <div className={styles.typeToggle}>
                    <button type="button" className={statusForm.assignment_type === "STUDENT" ? styles.activeType : ""} onClick={() => setStatusForm({ ...statusForm, assignment_type: "STUDENT", student_id: "", official_user_id: "" })}>
                      <GraduationCap size={16} /> Student
                    </button>
                    <button type="button" className={statusForm.assignment_type === "OFFICIAL" ? styles.activeType : ""} onClick={() => setStatusForm({ ...statusForm, assignment_type: "OFFICIAL", student_id: "", official_user_id: "" })}>
                      <UserCog size={16} /> Staff
                    </button>
                  </div>

                  {statusForm.assignment_type === "STUDENT" ? (
                    <div className={styles.formGroup}>
                      <div className={styles.modalSearchBox}>
                        <Search size={14} className={styles.modalSearchIcon} />
                        <input className={styles.modalSearchInput} placeholder="Search student name or ID..." value={studentSearch} onChange={(e) => setStudentSearch(e.target.value)} />
                      </div>
                      <div className={styles.customListContainer}>
                        {filteredStudents.length > 0 ? filteredStudents.map((s) => (
                          <div key={s.student_id} className={`${styles.customListItem} ${statusForm.student_id === String(s.student_id) ? styles.selectedItem : ""}`} onClick={() => setStatusForm({ ...statusForm, student_id: String(s.student_id) })}>
                            <span>{s.student_name}</span><small>({s.enr_id || "No ID"})</small>
                          </div>
                        )) : <div className={styles.noDataOption}>No eligible students found</div>}
                      </div>
                    </div>
                  ) : (
                    <div className={styles.formGroup}>
                      <div className={styles.modalSearchBox}>
                        <Search size={14} className={styles.modalSearchIcon} />
                        <input className={styles.modalSearchInput} placeholder="Search staff name..." value={staffSearch} onChange={(e) => setStaffSearch(e.target.value)} />
                      </div>
                      <div className={styles.customListContainer}>
                        {filteredStaff.length > 0 ? filteredStaff.map((u) => (
                          <div key={u.user_id} className={`${styles.customListItem} ${statusForm.official_user_id === String(u.user_id) ? styles.selectedItem : ""}`} onClick={() => setStatusForm({ ...statusForm, official_user_id: String(u.user_id) })}>
                            <span>{u.user_name}</span>
                          </div>
                        )) : <div className={styles.noDataOption}>No staff members found</div>}
                      </div>
                    </div>
                  )}
                </>
              )}

              <label className={styles.modalLabel}>Remarks</label>
              <textarea placeholder="Enter status update details..." rows={2} className={styles.modalTextarea} value={statusForm.remarks} onChange={(e) => setStatusForm({ ...statusForm, remarks: e.target.value })} />
              <button type="submit" className={styles.btnPrimary}>Update Status</button>
            </form>
          </div>
        </div>
      )}

      {/* HISTORY MODAL */}
      {isHistoryModalOpen && selectedDevice && (
        <div className={styles.modalOverlay}>
          <div className={`${styles.modal} ${styles.historyModal}`}>
            <div className={styles.modalHeader}>
              <div>
                <h2>Assignment History</h2>
                <p style={{ fontSize: "0.85rem", color: "var(--text-muted)" }}>
                  {selectedDevice.brand_name} {selectedDevice.model} | SN: {selectedDevice.serial_number}
                </p>
              </div>
              <button onClick={() => setIsHistoryModalOpen(false)}><X size={18} /></button>
            </div>
            <div className={styles.modalBody}>
              {historyData.length === 0 ? (
                <div className={styles.emptyHistory}><Clock size={32} /><p>No assignment records found.</p></div>
              ) : (
                <table className={`${styles.table} ${styles.historyTable}`}>
                  <thead>
                    <tr>
                      <th>Assignment Date</th>
                      <th>Assigned To</th>
                      <th>ID / ENR ID</th>
                      <th>Category</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {historyData.map((h, i) => (
                      <tr key={i}>
                        <td>{new Date(h.assignment_date).toLocaleDateString()}</td>
                        <td style={{ fontWeight: "600" }}>{h.name}</td>
                        <td>{h.enr_id || "—"}</td>
                        <td><span className={styles.categoryBadge}>{h.category}</span></td>
                        <td>
                          {h.return_date ? (
                            <div className={styles.returnInfo}>
                              <span className={styles.returnedText}>Returned</span>
                              <small>{new Date(h.return_date).toLocaleDateString()}</small>
                            </div>
                          ) : (
                            <span className={styles.activeText}>Current Holder</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      )}
    </main>
  );
};

export default TabInventory;