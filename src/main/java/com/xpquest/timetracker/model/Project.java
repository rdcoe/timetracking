package com.xpquest.timetracker.model;

/**
 * A trackable project.
 *
 * @param id          surrogate primary key (null before it is persisted)
 * @param code        human-facing project id / code (e.g. "ACME-2026")
 * @param name        display name
 * @param description free-form description
 * @param client      client / customer name
 * @param active      whether it should appear in the tracker dropdown
 */
public record Project(
        Long id,
        String code,
        String name,
        String description,
        String client,
        boolean active) {
}
