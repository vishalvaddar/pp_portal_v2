-- pp.event_students -- the event-attendance link table (which students attended which event).
--
-- This table EXISTS in production but was missing from the V1 baseline pg_dump (an incomplete dump).
-- The Node events module depends on it in ~8 places: /attendance/students-list (LEFT JOIN for the
-- is_marked flag), /attendance/save (DELETE + INSERT ... ON CONFLICT (event_id, student_id) DO NOTHING),
-- the sammelan attendee count-sync, and delete-event's child cleanup. Recreated here so the Java port is
-- byte-compatible with production behavior. Columns/keys inferred from the live Node SQL:
--   * only two columns are ever referenced: event_id, student_id
--   * eventModel.js:433 `ON CONFLICT (event_id, student_id) DO NOTHING` => a composite PK/unique on the pair
--   * event_id  -> pp.event_master(event_id)   (integer)
--   * student_id -> pp.student_master(student_id) (numeric(14,0))
-- deleteEvent removes event_students rows manually before deleting the event_master row, so a plain
-- (RESTRICT) FK to event_master is sufficient -- no ON DELETE CASCADE is relied upon.
CREATE TABLE IF NOT EXISTS pp.event_students (
    event_id integer NOT NULL,
    student_id numeric(14,0) NOT NULL,
    CONSTRAINT event_students_pkey PRIMARY KEY (event_id, student_id),
    CONSTRAINT event_students_event_id_fkey FOREIGN KEY (event_id) REFERENCES pp.event_master(event_id),
    CONSTRAINT event_students_student_id_fkey FOREIGN KEY (student_id) REFERENCES pp.student_master(student_id)
);
