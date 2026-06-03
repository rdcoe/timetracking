package com.xpquest.timetracker.dao;

import com.xpquest.timetracker.db.Database;
import com.xpquest.timetracker.model.Project;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** CRUD access for {@link Project} rows. */
public final class ProjectDao {

    private final Database db;

    public ProjectDao(Database db) {
        this.db = db;
    }

    /** Active projects, ordered by name — the contents of the tracker dropdown. */
    public List<Project> listActive() {
        String sql = "SELECT id, code, name, description, client_name, active "
                + "FROM project WHERE active = TRUE ORDER BY name";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Project> out = new ArrayList<>();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list projects", e);
        }
    }

    /** Inserts a project and returns it with its generated id populated. */
    public Project insert(Project p) {
        String sql = "INSERT INTO project(code, name, description, client_name, active) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, p.code());
            ps.setString(2, p.name());
            ps.setString(3, p.description());
            ps.setString(4, p.client());
            ps.setBoolean(5, p.active());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                return new Project(id, p.code(), p.name(), p.description(), p.client(), p.active());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert project", e);
        }
    }

    private Project map(ResultSet rs) throws SQLException {
        return new Project(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("client_name"),
                rs.getBoolean("active"));
    }
}
