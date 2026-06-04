package com.xpquest.timetracker.model;

/**
 * One project's tracked total for a single day, used to build the daily summary
 * export.
 *
 * @param code        project code (drives workstream inference, e.g. "xpq-eng")
 * @param name        project display name
 * @param description free-form project description
 * @param client      client / customer name (blank for internal XP Quest work)
 * @param seconds     completed (stopped) tracked seconds for the day
 */
public record DailySummaryRow(
        String code,
        String name,
        String description,
        String client,
        long seconds) {
}
