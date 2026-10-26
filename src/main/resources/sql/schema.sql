-- Automated Academic Timetable Generator - database schema.
-- Written for MySQL 8; also runs on the embedded H2 fallback in MySQL mode.
-- Every statement is idempotent so the application can run it at each start.

CREATE TABLE IF NOT EXISTS settings (
    setting_key   VARCHAR(64)  NOT NULL PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS room (
    id        INT AUTO_INCREMENT PRIMARY KEY,
    code      VARCHAR(32) NOT NULL UNIQUE,
    building  VARCHAR(64) NOT NULL,
    capacity  INT         NOT NULL CHECK (capacity > 0),
    room_type VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS faculty (
    id   INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS faculty_unavailability (
    faculty_id   INT NOT NULL,
    day_index    INT NOT NULL,
    period_index INT NOT NULL,
    PRIMARY KEY (faculty_id, day_index, period_index),
    CONSTRAINT fk_unavail_faculty FOREIGN KEY (faculty_id) REFERENCES faculty (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS batch (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(64) NOT NULL UNIQUE,
    student_count INT         NOT NULL CHECK (student_count > 0)
);

CREATE TABLE IF NOT EXISTS course (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    code              VARCHAR(32)  NOT NULL,
    title             VARCHAR(128) NOT NULL,
    faculty_id        INT          NOT NULL,
    meetings_per_week INT          NOT NULL CHECK (meetings_per_week > 0),
    room_type         VARCHAR(16)  NOT NULL,
    CONSTRAINT fk_course_faculty FOREIGN KEY (faculty_id) REFERENCES faculty (id)
);

-- Batches attending a course: one row normally, several for a combined class.
CREATE TABLE IF NOT EXISTS course_batch (
    course_id INT NOT NULL,
    batch_id  INT NOT NULL,
    PRIMARY KEY (course_id, batch_id),
    CONSTRAINT fk_cb_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_cb_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);

CREATE TABLE IF NOT EXISTS timetable_run (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    label      VARCHAR(128) NOT NULL,
    soft_cost  DOUBLE       NOT NULL,
    notes      TEXT
);

CREATE TABLE IF NOT EXISTS timetable_entry (
    run_id        INT NOT NULL,
    course_id     INT NOT NULL,
    meeting_index INT NOT NULL,
    day_index     INT NOT NULL,
    period_index  INT NOT NULL,
    room_id       INT NOT NULL,
    PRIMARY KEY (run_id, course_id, meeting_index),
    CONSTRAINT fk_entry_run FOREIGN KEY (run_id) REFERENCES timetable_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_entry_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_entry_room FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE CASCADE
);
