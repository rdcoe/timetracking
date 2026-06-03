package com.xpquest.timetracker.dao;

import com.xpquest.timetracker.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** Writes time-entry rows: one row per start/stop tracking session. */
public final class TimeEntryDao {

    private final Database db;

    public TimeEntryDao(Database db) {
        this.db = db;
    }

    /** Opens a new entry (end_time left null) and returns its id. */
    public long start(long projectId, LocalDateTime startTime) {
        String sql = "INSERT INTO time_entry(project_id, start_time) VALUES (?, ?)";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, projectId);
            ps.setTimestamp(2, Timestamp.valueOf(startTime));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to start time entry", e);
        }
    }

    /** Closes an open entry by stamping its end_time. */
    public void stop(long entryId, LocalDateTime endTime) {
        String sql = "UPDATE time_entry SET end_time = ? WHERE id = ?";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(endTime));
            ps.setLong(2, entryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to stop time entry " + entryId, e);
        }
    }
}
