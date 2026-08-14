const pool = require("../config/db");
const EventModel = require("../models/eventModel");
const fs = require('fs');
const path = require('path');

/* =========================================================
    EVENT TYPE CONTROLLERS
========================================================= */
exports.createEventType = async (req, res) => {
    try {
        const { event_type_name } = req.body;
        
        if (!event_type_name) {
            return res.status(400).json({ message: "Event type name is required" });
        }

        // Only passing the name, NO userid
        const data = await EventModel.createEventType(event_type_name);
        res.status(201).json(data);
    } catch (err) {
        console.error("Error:", err);
        res.status(500).json({ message: "Failed to create event type" });
    }
    };

exports.updateEventType = async (req, res) => {
    try {
        const { id } = req.params;
        const { event_type_name } = req.body;
        const data = await EventModel.updateEventType(id, event_type_name);
        res.json(data);
    } catch (err) {
        res.status(500).json({ message: "Failed to update event type" });
    }
};

exports.getEventTypes = async (req, res) => {
    try {
        const data = await EventModel.getEventTypes();
        res.json(data);
    } catch (err) {
        res.status(500).json({ message: "Failed to fetch event types" });
    }
};

/* =========================================================
    EVENT MASTER CONTROLLERS
========================================================= */

exports.createEvent = async (req, res) => {
    const client = await pool.connect();
    try {
        await client.query("BEGIN");
        const {
            event_type_id, event_title, event_description,
            event_start_date, event_end_date, event_district,
            event_block, event_location, pincode, cohort_number,
            boys_attended = 0, girls_attended = 0, parents_attended = 0,
            user_id // <--- IMPORTANT: Catch this from the frontend request body
        } = req.body;

        // Fallback logic: check session first, then check request body
        const userId = req.user?.user_id || user_id || null;

        // We must pass exactly 15 values to match the 15 placeholders in the model
        const eventValues = [
            event_type_id,      // $1
            event_title,        // $2
            event_description,  // $3
            event_start_date,   // $4
            event_end_date,     // $5
            event_district,     // $6
            event_block,        // $7
            event_location,     // $8
            pincode,            // $9
            cohort_number,      // $10
            boys_attended,      // $11
            girls_attended,     // $12
            parents_attended,   // $13
            userId,             // $14 (created_by)
            userId              // $15 (updated_by)
        ];

        const eventId = await EventModel.createEvent(client, eventValues);

        if (req.files?.photos) {
            for (const file of req.files.photos) {
                // Pass userId to photos table as well
                await EventModel.insertPhoto(client, [eventId, file.path, file.originalname, userId]);
            }
        }

        await client.query("COMMIT");
        res.status(201).json({ success: true, message: "Event created", event_id: eventId });
    } catch (err) {
        await client.query("ROLLBACK");
        console.error("Save Error:", err.message);
        res.status(500).json({ success: false, message: "Failed to create event" });
    } finally { client.release(); }
};

exports.updateEvent = async (req, res) => {
    const client = await pool.connect();
    const { id } = req.params;
    try {
        await client.query("BEGIN");
        
        const {
            event_type_id, event_title, event_description,
            event_start_date, event_end_date, event_district,
            event_block, event_location, pincode, 
            cohort_number, boys_attended, girls_attended, 
            parents_attended, event_type_name, photos_to_delete
        } = req.body;

        const userId = req.user?.user_id || null;

        // 1. Handle Photo Deletions
        if (photos_to_delete) {
            const deleteIds = JSON.parse(photos_to_delete);
            if (deleteIds.length > 0) {
                await client.query(`DELETE FROM pp.event_photos WHERE photo_id = ANY($1::int[])`, [deleteIds]);
            }
        }

        // 2. Initial Master Update
        await EventModel.updateEvent(client, [
            event_type_id, event_title, event_description,
            event_start_date, event_end_date, event_district,
            event_block, event_location, pincode, 
            cohort_number, boys_attended || 0, girls_attended || 0, 
            parents_attended || 0, userId, id
        ]);

        // 3. Sammelan Auto-Count Sync
        if (event_type_name === 'Sammelan') {
            // Get counts from student_master based on currently linked students in event_students
            const countQuery = `
                SELECT 
                    COUNT(*) FILTER (WHERE UPPER(gender) IN ('M','MALE')) as boys,
                    COUNT(*) FILTER (WHERE UPPER(gender) IN ('F','FEMALE')) as girls
                FROM pp.student_master sm
                JOIN pp.event_students es ON sm.student_id = es.student_id
                WHERE es.event_id = $1
            `;
            const { rows: counts } = await client.query(countQuery, [id]);
            
            // Sync counts back to master table
            await client.query(
                `UPDATE pp.event_master SET boys_attended = $1, girls_attended = $2 WHERE event_id = $3`,
                [counts[0].boys || 0, counts[0].girls || 0, id]
            );
        }

        // 4. File Uploads
        if (req.files?.photos) {
            for (const file of req.files.photos) {
                await EventModel.insertPhoto(client, [id, file.path, file.filename, userId]);
            }
        }
        if (req.files?.reports && req.files.reports.length > 0) {
            await EventModel.deleteOldReport(client, id);
            const report = req.files.reports[0];
            await EventModel.insertEventReport(client, [id, 'SAMMELAN_REPORT', report.path, report.filename, userId]);
        }

        await client.query("COMMIT");
        res.json({ success: true, message: "Updated successfully" });
    } catch (err) {
        await client.query("ROLLBACK");
        res.status(500).json({ success: false, message: err.message });
    } finally { client.release(); }
};

exports.deleteEvent = async (req, res) => {
    try {
        const { id } = req.params;
        await EventModel.deleteEvent(id);
        res.json({ success: true, message: "Deleted successfully" });
    } catch (err) { res.status(500).json({ success: false, message: "Delete failed" }); }
};

exports.getAllEvents = async (req, res) => {
    try {
        const data = await EventModel.getAllEvents();
        res.json(data);
    } catch (err) { res.status(500).json({ success: false, message: "Fetch failed" }); }
};

exports.getEventById = async (req, res) => {
    try {
        const { id } = req.params;
        const event = await EventModel.getEventById(id);
        if (!event) return res.status(404).json({ message: "Not found" });
        const photos = await EventModel.getEventPhotos(id);
        const reports = await EventModel.getEventReports(id);
        res.json({ ...event, photos, reports });
    } catch (err) { res.status(500).json({ success: false, message: "Fetch failed" }); }
};

/* --- SAMMELAN HELPERS --- */
exports.getSammelanEvents = async (req, res) => {
    try {
        const events = await EventModel.getSammelanEvents();
        res.status(200).json({ success: true, data: events });
    } catch (err) { res.status(500).json({ success: false, msg: err.message }); }
};

exports.getJurisdictionData = async (req, res) => {
    try {
        const { type, stateName, divisionNames, districtNames } = req.query;
        let data;
        if (type === 'state') data = await EventModel.getStates();
        else if (type === 'division') data = await EventModel.getDivisionsByState(stateName);
        else if (type === 'district') data = await EventModel.getDistrictsByDivisions(divisionNames);
        else if (type === 'block') data = await EventModel.getBlocksByMultiDistricts(stateName, divisionNames, districtNames);
        res.status(200).json({ success: true, data });
    } catch (err) { res.status(500).json({ success: false, msg: err.message }); }
};

exports.fetchStudentAttendanceList = async (req, res) => {
    try {
        const { eventTitle, stateName, districtNames, blockNames, page } = req.body;
        
        // 1. Get the Event Info (to get the cohort_number) based on Title
        const { rows: eventRows } = await pool.query(
            `SELECT event_id, cohort_number FROM pp.event_master WHERE event_title = $1`, 
            [eventTitle]
        );

        if (eventRows.length === 0) {
            return res.status(404).json({ success: false, msg: "Event not found" });
        }

        const { event_id, cohort_number } = eventRows[0];

        const filters = {
            eventId: event_id,
            cohortNumber: cohort_number,
            stateName: stateName || null,
            districtNames: (Array.isArray(districtNames) && districtNames.length > 0) ? districtNames : null,
            blockNames: (Array.isArray(blockNames) && blockNames.length > 0) ? blockNames : null,
            limit: 15,
            offset: ((page || 1) - 1) * 15
        };

        // 2. Fetch the student list
        const students = await EventModel.getSammelanStudentList(filters);
        res.status(200).json({ success: true, data: students });
    } catch (err) {
        console.error("Fetch Student List Error:", err);
        res.status(500).json({ success: false, msg: "Internal Server Error" });
    }
};

exports.submitAttendance = async (req, res) => {
    const client = await pool.connect(); 
    try {
        await client.query("BEGIN");
        const { eventId, studentIds, parents_attended, user_id } = req.body;
        const parsedStudentIds = typeof studentIds === 'string' ? JSON.parse(studentIds) : studentIds;
        
        // Priority: Auth Middleware > Request Body
        const userId = req.user?.user_id || user_id || null;

        // 1. Save students list
        await EventModel.saveSammelanAttendance(client, eventId, parsedStudentIds);

        // 2. Calculate Gender Counts
        const genderQuery = `
            SELECT gender, COUNT(*) as count 
            FROM pp.student_master 
            WHERE student_id = ANY($1::int[]) 
            GROUP BY gender`;
        const { rows: genderCounts } = await client.query(genderQuery, [parsedStudentIds]);

        let boys = 0, girls = 0;
        genderCounts.forEach(row => {
            const g = row.gender?.toUpperCase();
            if (g === 'MALE' || g === 'M') boys = parseInt(row.count);
            if (g === 'FEMALE' || g === 'F') girls = parseInt(row.count);
        });

        // 3. UPDATED: Save counts AND track who updated the record
        const updateMasterQuery = `
            UPDATE pp.event_master 
            SET boys_attended = $1, 
                girls_attended = $2, 
                parents_attended = $3,
                updated_by = $4,
                updated_at = CURRENT_TIMESTAMP
            WHERE event_id = $5`;
        await client.query(updateMasterQuery, [boys, girls, parents_attended || 0, userId, eventId]);

        // 4. Handle Multimedia (Tracks uploader/generator)
        if (req.files?.photos) {
            for (const file of req.files.photos) {
                await EventModel.insertPhoto(client, [eventId, file.path, file.filename, userId]);
            }
        }
        if (req.files?.reports && req.files.reports.length > 0) {
            const report = req.files.reports[0];
            await EventModel.insertEventReport(client, [eventId, 'SAMMELAN_REPORT', report.path, report.filename, userId]);
        }

        await client.query("COMMIT");
        res.status(200).json({ success: true, msg: "Attendance updated successfully!" });
    } catch (err) {
        await client.query("ROLLBACK");
        res.status(500).json({ success: false, msg: "Server Error: " + err.message });
    } finally { client.release(); }
};
