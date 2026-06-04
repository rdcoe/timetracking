package com.xpquest.timetracker.dao;

import com.xpquest.timetracker.db.Database;
import com.xpquest.timetracker.model.DailySummaryRow;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Per-project tracked totals for a single day, one row per project that has
     * completed (stopped) time on that date. Joined to {@code project} so the
     * summary carries the code/name/description/client the daily-log skill needs.
     * Open entries are ignored. Ordered by code for a stable summary.
     */
    public List<DailySummaryRow> dailySummary(LocalDate date) {
        String sql = "SELECT p.code, p.name, p.description, p.client_name, "
                + "SUM(DATEDIFF(SECOND, te.start_time, te.end_time)) AS seconds "
                + "FROM time_entry te JOIN project p ON p.id = te.project_id "
                + "WHERE te.end_time IS NOT NULL AND CAST(te.start_time AS DATE) = ? "
                + "GROUP BY p.id, p.code, p.name, p.description, p.client_name "
                + "HAVING SUM(DATEDIFF(SECOND, te.start_time, te.end_time)) > 0 "
                + "ORDER BY p.code";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                List<DailySummaryRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new DailySummaryRow(
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("client_name"),
                            rs.getLong("seconds")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to build daily summary", e);
        }
    }
}
