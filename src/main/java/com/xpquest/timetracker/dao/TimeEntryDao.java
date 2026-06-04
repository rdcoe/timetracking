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

    /** Inserts a manually-entered completed entry spanning [start, end]. */
    public void addManual(long projectId, LocalDateTime start, LocalDateTime end) {
        String sql = "INSERT INTO time_entry(project_id, start_time, end_time) VALUES (?, ?, ?)";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, projectId);
            ps.setTimestamp(2, Timestamp.valueOf(start));
            ps.setTimestamp(3, Timestamp.valueOf(end));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add manual time entry", e);
        }
    }

    /**
     * Sum of completed (stopped) tracked seconds for a project. Open entries are
     * ignored — the live session is added on top in the UI.
     *
     * @param todayOnly when true, restrict to entries that started today
     */
    public long totalSeconds(long projectId, boolean todayOnly) {
        String sql = "SELECT COALESCE(SUM(DATEDIFF(SECOND, start_time, end_time)), 0) "
                + "FROM time_entry WHERE project_id = ? AND end_time IS NOT NULL"
                + (todayOnly ? " AND CAST(start_time AS DATE) = CURRENT_DATE" : "");
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to total time entries", e);
        }
    }
}
