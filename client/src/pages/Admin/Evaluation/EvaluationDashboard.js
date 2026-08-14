import React, { useState, useEffect, useMemo } from "react";
import { Link } from "react-router-dom";
import { 
  ClipboardCheck, UserCheck, BarChart, ChevronRight, 
  CheckCircle2, Clock, SearchX, ChevronLeft, 
  ChevronRight as ChevronRightIcon, LayoutGrid, Search 
} from "lucide-react";
import { useSystemConfig } from "../../../contexts/SystemConfigContext"; 
import Breadcrumbs from "../../../components/Breadcrumbs/Breadcrumbs";
import styles from "./EvaluationDashboard.module.css";

const NavCard = ({ title, icon, description, link, colorClass }) => (
  <Link to={link} className={`${styles.navCard} ${styles[colorClass]}`}>
    <div className={styles.navIcon}>{icon}</div>
    <div className={styles.navContent}>
      <h3 className={styles.navTitle}>{title}</h3>
      <p className={styles.navDesc}>{description}</p>
    </div>
    <ChevronRight size={20} className={styles.navArrow} />
  </Link>
);

const EvaluationDashboard = () => {
  const { appliedConfig, loading: configLoading } = useSystemConfig();
  const [overallData, setOverallData] = useState({});
  const [jurisdictions, setJurisdictions] = useState([]);
  const [overallProgress, setOverallProgress] = useState(0);
  const [loading, setLoading] = useState(true);
  
  const [searchTerm, setSearchTerm] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const itemsPerPage = 8;

  const isClassesStarted = useMemo(() => {
    return !configLoading && appliedConfig?.phase?.trim() === "Classes are started";
  }, [appliedConfig, configLoading]);

  const API_BASE_URL = `${process.env.REACT_APP_BACKEND_API_URL}/api/evaluation-dashboard`;

  const displayYear = useMemo(() => {
    if (appliedConfig?.academic_year) return appliedConfig.academic_year.split('-')[0];
    return new Date().getFullYear().toString();
  }, [appliedConfig]);

  useEffect(() => {
    if (isClassesStarted) { setLoading(false); return; }

    const fetchData = async () => {
      try {
        setLoading(true);
        const [ovRes, jurRes, progRes] = await Promise.all([
          fetch(`${API_BASE_URL}/overall/${displayYear}`),
          fetch(`${API_BASE_URL}/jurisdictions/${displayYear}`),
          fetch(`${API_BASE_URL}/overall-progress/${displayYear}`)
        ]);
        const ovData = await ovRes.json();
        const jurData = await jurRes.json();
        const progData = await progRes.json();

        setOverallData(ovData);
        setJurisdictions(jurData);
        setOverallProgress(progData.overallProgress || 0);
      } catch (err) {
        console.error("Dashboard Error:", err);
      } finally {
        setLoading(false);
      }
    };
    if (displayYear) fetchData();
  }, [displayYear, API_BASE_URL, isClassesStarted]);

  const filteredItems = useMemo(() => {
    return jurisdictions.filter(j => 
      j.juris_name.toLowerCase().includes(searchTerm.toLowerCase()) ||
      j.juris_code.toLowerCase().includes(searchTerm.toLowerCase())
    );
  }, [jurisdictions, searchTerm]);

  useEffect(() => { setCurrentPage(1); }, [searchTerm]);

  const totalPages = Math.ceil(filteredItems.length / itemsPerPage);
  const currentItems = filteredItems.slice((currentPage - 1) * itemsPerPage, currentPage * itemsPerPage);

  if (loading || configLoading) return <div className={styles.loader}><span>Syncing Dashboard...</span></div>;

  return (
    <main className={styles.container}>
      <Breadcrumbs path={['Admin', 'Admissions', 'Evaluation']} nonLinkSegments={['Admin', 'Admissions']} />

      <header className={styles.header}>
        <div className={styles.welcome}>
          <h1 className={styles.title}>Evaluation Dashboard</h1>
          <p className={styles.subtitle}>Cycle: {displayYear}-{parseInt(displayYear) + 1}</p>
        </div>
        
        {!isClassesStarted && (
          <div className={styles.progressCard}>
            <div className={styles.progressInfo}>
              <span className={styles.progressLabel}>Total Completion</span>
              <span className={styles.progressPercent}>{overallProgress}%</span>
            </div>
            <div className={styles.progressTrack}>
              <div className={styles.progressFill} style={{ width: `${overallProgress}%` }} />
            </div>
          </div>
        )}
      </header>

      <section className={styles.content}>
        <div className={styles.cardRow}>
          <NavCard title="Marks Entry" icon={<ClipboardCheck size={26} />} description="Score updates" link="marks-entry" colorClass="blueCard" />
          <NavCard title="Interviews" icon={<UserCheck size={26} />} description="Interview and Home verification assignments and record management" link="interview" colorClass="purpleCard" />
          <NavCard title="Tracking" icon={<BarChart size={26} />} description=" Interview and Home verification tracking" link="tracking" colorClass="orangeCard" />
        </div>

        {!isClassesStarted && (
          <>
            {/* BIG STATS TILES IN ONE LINE */}
            <div className={styles.statsGrid}>
              {Object.entries(overallData).map(([label, count]) => (
                <div key={label} className={styles.statTile}>
                  <span className={styles.statValue}>{count}</span>
                  <span className={styles.statName}>{label.replace(/([A-Z])/g, ' $1')}</span>
                </div>
              ))}
            </div>

            <div className={styles.jurisdictionSection}>
              <div className={styles.sectionHeader}>
                <div className={styles.headerLeft}>
                  <div className={styles.titleWithCount}>
                    <LayoutGrid size={22} className={styles.gridIcon} />
                    <h2 className={styles.sectionTitle}>Jurisdictional Status</h2>
                    <span className={styles.totalBadge}>{filteredItems.length} Blocks</span>
                  </div>
                  
                  {/* NICE SEARCH BOX */}
                  <div className={styles.searchBox}>
                    <Search size={18} className={styles.searchIcon} />
                    <input 
                      type="text" 
                      placeholder="Search jurisdiction name or code..." 
                      className={styles.searchInput}
                      value={searchTerm}
                      onChange={(e) => setSearchTerm(e.target.value)}
                    />
                  </div>
                </div>
                
                <div className={styles.pagination}>
                   <button disabled={currentPage === 1} onClick={() => setCurrentPage(p => p - 1)} className={styles.pageBtn}>
                     <ChevronLeft size={16} />
                   </button>
                   <span className={styles.pageText}>{totalPages > 0 ? currentPage : 0} / {totalPages}</span>
                   <button disabled={currentPage === totalPages || totalPages === 0} onClick={() => setCurrentPage(p => p + 1)} className={styles.pageBtn}>
                     <ChevronRightIcon size={16} />
                   </button>
                </div>
              </div>

              {currentItems.length > 0 ? (
                <div className={styles.jurisGrid}>
                  {currentItems.map((j) => (
                    <div key={j.juris_code} className={styles.jurisBox}>
                      <div className={styles.boxHeader}>
                        <div className={styles.jText}>
                          <h4 className={styles.boxTitle}>{j.juris_name}</h4>
                          <p className={styles.boxCode}>{j.juris_code}</p>
                        </div>
                        {j.progress === 100 ? <CheckCircle2 size={18} className={styles.iconSuccess} /> : <Clock size={18} className={styles.iconPending} />}
                      </div>

                      <div className={styles.boxBody}>
                        <div className={styles.countRow}><span>Shortlisted</span><strong>{j.totalShortlisted}</strong></div>
                        <div className={styles.countRow}><span>Pending Marks</span><strong className={styles.dangerText}>{j.counts.pendingEvaluation}</strong></div>
                        <div className={styles.countRow}><span>Pending Interview</span><strong className={styles.infoText}>{(j.counts.totalInterviewRequired || 0) - (j.counts.completedInterview || 0)}</strong></div>
                      </div>

                      <div className={styles.boxFooter}>
                        <div className={styles.miniProgress}><div className={styles.miniFill} style={{width: `${j.progress}%`}} /></div>
                        <span className={styles.percentText}>{j.progress}%</span>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className={styles.emptyState}>
                  <SearchX size={48} strokeWidth={1.5} />
                  <h3>No matches found</h3>
                  <p>Try refining your search keywords.</p>
                </div>
              )}
            </div>
          </>
        )}
      </section>
    </main>
  );
};

export default EvaluationDashboard;