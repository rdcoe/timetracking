package com.xpquest.timetracker;

import com.xpquest.timetracker.dao.ProjectDao;
import com.xpquest.timetracker.dao.TimeEntryDao;
import com.xpquest.timetracker.db.Database;
import com.xpquest.timetracker.model.DailySummaryRow;
import com.xpquest.timetracker.model.Project;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XP Quest Time Tracker — an always-on-top JavaFX widget.
 *
 * <p>Pick a project from the dropdown, hit Start, hit Stop. Each session is
 * persisted as a {@code time_entry} row. Projects are registered through the
 * "＋" dialog. The embedded H2 engine is started in {@link #start} and stopped
 * in {@link #stop}, so it lives and dies with the window.
 */
public class App extends Application {

    private Database database;
    private ProjectDao projectDao;
    private TimeEntryDao timeEntryDao;

    private ComboBox<Project> projectCombo;
    private Label timerLabel;
    private Label todayLabel;
    private Label totalLabel;
    private Button toggleButton;
    private DatePicker addDateField;
    private TextField addTimeField;
    private HBox manualAddRow;
    private Label statusLabel;
    private Timeline ticker;
    private final TrayNotifier tray = new TrayNotifier();

    private Long runningEntryId;
    private LocalDateTime runningSince;

    // Independent 1s daemon that watches the wall clock for a large forward jump —
    // the tell-tale of the JVM having been frozen by an OS suspend. It runs on its
    // own thread rather than the JavaFX ticker, so it keeps sampling while the
    // window is minimised and isn't subject to animation-pulse throttling: it
    // neither misses a real suspend nor mistakes a long minimise for one.
    private ScheduledExecutorService sleepWatch;
    private long sleepWatchWallMs;
    private long sleepWatchNanos;

    // Completed totals for the selected project, captured so the live session
    // can be added on top each tick without re-querying every second.
    private long baseTodaySeconds;
    private long baseTotalSeconds;

    // A wall-clock jump beyond this between two 1s watch samples is treated as a
    // system sleep rather than a scheduling hiccup.
    private static final long SLEEP_GAP_SECONDS = 30;

    private static final System.Logger LOG = System.getLogger(App.class.getName());

    @Override
    public void start(Stage stage) {
        database = new Database();
        database.start();
        projectDao = new ProjectDao(database);
        timeEntryDao = new TimeEntryDao(database);

        stage.setScene(buildScene(stage));
        stage.setTitle("XP Quest Time Tracker");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.show();

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> onTick()));
        ticker.setCycleCount(Animation.INDEFINITE);

        // Best-effort OS tray notifier for sleep/wake events, since the widget may
        // be off-screen or not on top when the machine wakes.
        tray.install();

        refreshProjects();
    }

    private Scene buildScene(Stage stage) {
        projectCombo = new ComboBox<>();
        projectCombo.setMaxWidth(Double.MAX_VALUE);
        projectCombo.setPromptText("Select a project…");
        projectCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Project p) {
                return p == null ? "" : "[" + p.code() + "] " + p.name();
            }

            @Override
            public Project fromString(String s) {
                return null;
            }
        });

        Button addProjectButton = new Button("+");
        addProjectButton.getStyleClass().add("icon-button");
        addProjectButton.setTooltip(new Tooltip("Register a new project"));
        addProjectButton.setOnAction(e -> showAddProjectDialog(stage));

        Button editProjectButton = new Button("…");
        editProjectButton.getStyleClass().add("icon-button");
        editProjectButton.setTooltip(new Tooltip("Edit the selected project"));
        editProjectButton.setOnAction(e -> showEditProjectDialog(stage, projectCombo.getValue()));
        editProjectButton.disableProperty().bind(projectCombo.valueProperty().isNull());

        HBox projectRow = new HBox(6, projectCombo, addProjectButton, editProjectButton);
        HBox.setHgrow(projectCombo, Priority.ALWAYS);

        timerLabel = new Label("00:00:00");
        timerLabel.getStyleClass().add("timer");

        // Cumulative totals for the selected project: today and all-time.
        todayLabel = new Label("00:00:00");
        totalLabel = new Label("00:00:00");
        HBox stats = new HBox(28, statBox("Today", todayLabel), statBox("Total to date", totalLabel));
        stats.setAlignment(Pos.CENTER);

        // React to a different project being picked (combo is disabled while tracking).
        projectCombo.valueProperty().addListener((o, was, now) -> {
            if (runningEntryId == null) {
                refreshTotals(now);
            }
        });

        toggleButton = new Button("Start");
        toggleButton.setMaxWidth(Double.MAX_VALUE);
        toggleButton.getStyleClass().add("toggle");
        toggleButton.setOnAction(e -> toggleTimer());

        // Manually log a block of time (HH:MM) onto the selected project, on the
        // chosen date (defaults to today). Disabled while the timer runs so it
        // can't race the live session.
        addDateField = new DatePicker(LocalDate.now());
        addDateField.setPrefWidth(118);
        addDateField.setTooltip(new Tooltip("Date to log the time on"));
        addTimeField = new TextField();
        addTimeField.setPromptText("HH:MM");
        addTimeField.setPrefColumnCount(5);
        addTimeField.setMaxWidth(64);
        addTimeField.setTooltip(new Tooltip("Duration to add, HH:MM"));
        addTimeField.setOnAction(e -> addManualTime()); // Enter submits
        Button addButton = new Button("Add");
        addButton.setOnAction(e -> addManualTime());
        manualAddRow = new HBox(6, addDateField, addTimeField, addButton);
        manualAddRow.setAlignment(Pos.CENTER);

        // Writes a per-day time summary for every day from the last checkpoint
        // through today, then advances the checkpoint. Allowed while tracking — it
        // only reads completed entries, so the live session isn't included yet.
        Button summaryButton = new Button("Daily Summary");
        summaryButton.setTooltip(new Tooltip(
                "Write per-day time summaries from the last checkpoint through today"));
        summaryButton.setOnAction(e -> writeDailySummary());

        Button summaryHelpButton = new Button("?");
        summaryHelpButton.getStyleClass().add("icon-button");
        summaryHelpButton.setTooltip(new Tooltip("How export & categorization work"));
        summaryHelpButton.setOnAction(e -> showSummaryHelp(stage));

        HBox summaryRow = new HBox(6, summaryButton, summaryHelpButton);
        summaryRow.setAlignment(Pos.CENTER);

        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status");

        // Bottom bar: status on the left, the always-on-top toggle in the lower-right
        // corner with its own adjacent label (clicking the label toggles it too).
        Label pinLabel = new Label("Always on top");
        pinLabel.getStyleClass().add("pin-label");
        CheckBox pin = new CheckBox();
        pin.setSelected(true);
        pin.selectedProperty().addListener((o, was, now) -> stage.setAlwaysOnTop(now));
        pinLabel.setOnMouseClicked(e -> pin.setSelected(!pin.isSelected()));

        HBox pinBox = new HBox(6, pinLabel, pin);
        pinBox.setAlignment(Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(8, statusLabel, spacer, pinBox);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setMaxWidth(Double.MAX_VALUE);

        // Equal-growing spacers above and below the main controls centre them
        // vertically; the status / always-on-top bar stays pinned at the bottom.
        Region vSpacerTop = new Region();
        VBox.setVgrow(vSpacerTop, Priority.ALWAYS);
        Region vSpacerBottom = new Region();
        VBox.setVgrow(vSpacerBottom, Priority.ALWAYS);

        VBox root = new VBox(10, vSpacerTop, projectRow, timerLabel, stats, toggleButton,
                manualAddRow, summaryRow, vSpacerBottom, bottomBar);
        root.setPadding(new Insets(12));
        root.setAlignment(Pos.TOP_CENTER);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 320, 320);
        scene.getStylesheets().add(App.class.getResource("/styles.css").toExternalForm());
        return scene;
    }

    private void refreshProjects() {
        Project selected = projectCombo.getValue();
        List<Project> projects = projectDao.listActive();
        projectCombo.getItems().setAll(projects);
        if (selected != null) {
            projects.stream()
                    .filter(p -> p.id().equals(selected.id()))
                    .findFirst()
                    .ifPresent(projectCombo::setValue);
        }
    }

    private void toggleTimer() {
        if (runningEntryId == null) {
            Project project = projectCombo.getValue();
            if (project == null) {
                statusLabel.setText("Pick a project first");
                return;
            }
            runningSince = LocalDateTime.now();
            runningEntryId = timeEntryDao.start(project.id(), runningSince);
            refreshTotals(project); // capture the committed base before this session
            projectCombo.setDisable(true);
            manualAddRow.setDisable(true);
            toggleButton.setText("Stop");
            statusLabel.setText("Tracking — " + project.name());
            startSleepWatch();
            ticker.play();
            updateTimerLabel();
        } else {
            stopTracking(LocalDateTime.now(), "Saved");
        }
    }

    /** Stops the running session, stamping the given end time, and refreshes totals. */
    private void stopTracking(LocalDateTime endTime, String status) {
        if (runningEntryId == null) {
            return;
        }
        Project project = projectCombo.getValue();
        timeEntryDao.stop(runningEntryId, endTime);
        ticker.stop();
        stopSleepWatch();
        runningEntryId = null;
        runningSince = null;
        projectCombo.setDisable(false);
        manualAddRow.setDisable(false);
        toggleButton.setText("Start");
        timerLabel.setText(formatHms(0));
        statusLabel.setText(status);
        refreshTotals(project);
    }

    /**
     * Logs a manually-entered block of time (HH:MM) onto the selected project as
     * a completed entry on the chosen date, so it counts toward Total (and Today
     * when the date is today). For today the block ends now (matching the live
     * session and never landing in the future); for any other date it is anchored
     * at 09:00 so its {@code start_time} falls on that date — daily-summary
     * attribution groups by {@code CAST(start_time AS DATE)}. No-op while the
     * timer is running, to avoid racing the live session.
     */
    private void addManualTime() {
        if (runningEntryId != null) {
            statusLabel.setText("Stop the timer first");
            return;
        }
        Project project = projectCombo.getValue();
        if (project == null) {
            statusLabel.setText("Pick a project first");
            return;
        }
        LocalDate date = addDateField.getValue();
        if (date == null) {
            statusLabel.setText("Pick a date");
            return;
        }
        long seconds = parseHhmm(addTimeField.getText());
        if (seconds <= 0) {
            statusLabel.setText("Enter time as HH:MM");
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime start;
        LocalDateTime end;
        if (date.equals(today)) {
            end = LocalDateTime.now();
            start = end.minusSeconds(seconds);
        } else {
            start = date.atTime(9, 0);
            end = start.plusSeconds(seconds);
        }
        timeEntryDao.addManual(project.id(), start, end);
        addTimeField.clear();
        addDateField.setValue(today);
        refreshTotals(project);
        String where = date.equals(today) ? "" : " on " + date;
        statusLabel.setText("Added " + formatHms(seconds) + where);
    }

    /** Parses "H:MM" / "HH:MM" into seconds; returns -1 if malformed. */
    private static long parseHhmm(String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = Pattern.compile("^\\s*(\\d+):([0-5]?\\d)\\s*$").matcher(text);
        if (!m.matches()) {
            return -1;
        }
        return Long.parseLong(m.group(1)) * 3600 + Long.parseLong(m.group(2)) * 60;
    }

    /**
     * Filename of the date checkpoint, co-located with the summary files. It only
     * tracks how far summary generation has reached.
     */
    private static final String CHECKPOINT_NAME = ".daily-summary-checkpoint";

    /**
     * Writes one {@code daily-summary-<DATE>.json} per day, for every day from the
     * last checkpoint through today, then advances the checkpoint to today. Each
     * file holds that day's per-project tracked totals, each project tagged with a
     * workstream inferred from its code (see {@link #workstream}). Days with no
     * completed time are skipped; today's file is rewritten on every press (its
     * tracking is still in progress).
     *
     * <p>The checkpoint ({@value #CHECKPOINT_NAME}) lives in the same directory as
     * the summaries and records how far this app has generated — nothing more. The
     * first press with no checkpoint covers everything from the earliest completed
     * entry.
     *
     * <p>Target directory is {@code $XPQUEST_SUMMARY_DIR} when set, otherwise
     * {@code user.home/.xpquest} — which is {@code %USERPROFILE%\.xpquest} on
     * Windows and {@code $HOME/.xpquest} on Linux (one code path, OS-native home,
     * the same proven-writable dir that already holds the H2 database file).
     */
    private void writeDailySummary() {
        LocalDate today = LocalDate.now();
        Path dir = summaryDir();
        Path checkpoint = dir.resolve(CHECKPOINT_NAME);
        LocalDate start = readCheckpoint(checkpoint, today);
        if (start.isAfter(today)) {
            start = today;
        }
        try {
            Files.createDirectories(dir);
            int written = 0;
            LocalDate lastWritten = null;
            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
                List<DailySummaryRow> rows = timeEntryDao.dailySummary(day);
                if (rows.isEmpty()) {
                    continue;
                }
                Files.writeString(dir.resolve("daily-summary-" + day + ".json"),
                        buildSummaryJson(day, rows), StandardCharsets.UTF_8);
                written++;
                lastWritten = day;
            }
            // Advance the checkpoint to today: this app has now generated through
            // today (today's file will be rewritten on the next press as it fills).
            Files.writeString(checkpoint, today + System.lineSeparator(), StandardCharsets.UTF_8);
            if (written == 0) {
                statusLabel.setText("No completed time to summarise");
            } else {
                statusLabel.setText("Wrote " + written + " day" + (written == 1 ? "" : "s")
                        + " through " + lastWritten);
            }
        } catch (IOException ex) {
            statusLabel.setText("Summary write failed: " + ex.getMessage());
        }
    }

    /**
     * Reads the checkpoint date (the start of the range to generate). Falls back to
     * the earliest completed entry when the file is missing or unreadable, and to
     * {@code today} when there are no entries at all.
     */
    private LocalDate readCheckpoint(Path checkpoint, LocalDate today) {
        try {
            if (Files.exists(checkpoint)) {
                String text = Files.readString(checkpoint, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    return LocalDate.parse(text);
                }
            }
        } catch (IOException | DateTimeParseException ignored) {
            // Unreadable or malformed — fall back to the earliest entry below.
        }
        LocalDate earliest = timeEntryDao.earliestEntryDate();
        return earliest != null ? earliest : today;
    }

    /** Summary output dir: {@code $XPQUEST_SUMMARY_DIR} if set, else {@code user.home/.xpquest}. */
    private static Path summaryDir() {
        String override = System.getenv("XPQUEST_SUMMARY_DIR");
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        return Paths.get(System.getProperty("user.home"), ".xpquest");
    }

    /**
     * Maps a project code to its workstream: any {@code xpq}-prefixed code is an
     * XP Quest project (never a client) — {@code xpq-sred*} → sred, any other
     * {@code xpq*} (e.g. {@code xpq-eng}, {@code xpq-techops}) → engineering.
     * Anything else (e.g. {@code acme-corp}) → client.
     */
    private static String workstream(String code) {
        String c = code == null ? "" : code.trim().toLowerCase();
        if (c.startsWith("xpq-sred")) {
            return "sred";
        }
        if (c.startsWith("xpq")) {
            return "engineering";
        }
        return "client";
    }

    /** Serialises the day's per-project totals as JSON (no JSON lib on the classpath). */
    private static String buildSummaryJson(LocalDate date, List<DailySummaryRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"date\": \"").append(date).append("\",\n");
        sb.append("  \"generated_at\": \"").append(LocalDateTime.now()).append("\",\n");
        sb.append("  \"projects\": [\n");
        for (int i = 0; i < rows.size(); i++) {
            DailySummaryRow r = rows.get(i);
            sb.append("    {\n");
            sb.append("      \"code\": ").append(jsonString(r.code())).append(",\n");
            sb.append("      \"name\": ").append(jsonString(r.name())).append(",\n");
            sb.append("      \"description\": ").append(jsonString(r.description())).append(",\n");
            sb.append("      \"client\": ").append(jsonString(r.client())).append(",\n");
            sb.append("      \"workstream\": \"").append(workstream(r.code())).append("\",\n");
            sb.append("      \"seconds\": ").append(r.seconds()).append(",\n");
            sb.append("      \"hours\": ").append(String.format("%.2f", r.seconds() / 3600.0)).append("\n");
            sb.append("    }").append(i < rows.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Quotes and escapes a string as a JSON string literal (null → ""). */
    private static String jsonString(String v) {
        if (v == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Per-second UI tick: just repaints the running timer. Sleep detection lives
     * in the independent {@link #sleepWatch} daemon, not here — the JavaFX ticker
     * is an unreliable clock across a suspend and while the window is minimised.
     */
    private void onTick() {
        updateTimerLabel();
    }

    /** Starts the wall-clock sleep watchdog for the life of a tracking session. */
    private void startSleepWatch() {
        if (sleepWatch != null) {
            return;
        }
        sleepWatchWallMs = System.currentTimeMillis();
        sleepWatchNanos = System.nanoTime();
        sleepWatch = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xpquest-sleep-watch");
            t.setDaemon(true);
            return t;
        });
        // Fixed delay (not fixed rate): after a freeze it runs once, late, then
        // resumes its 1s cadence — no burst of catch-up calls to dedupe.
        sleepWatch.scheduleWithFixedDelay(this::checkForSleep, 1, 1, TimeUnit.SECONDS);
        LOG.log(System.Logger.Level.INFO, "Sleep watch started");
    }

    /** Stops the watchdog. Safe to call when it isn't running. */
    private void stopSleepWatch() {
        if (sleepWatch != null) {
            sleepWatch.shutdownNow();
            sleepWatch = null;
            LOG.log(System.Logger.Level.INFO, "Sleep watch stopped");
        }
    }

    /**
     * Runs on the watch thread once a second. A wall-clock gap far larger than the
     * ~1s delay means the JVM was frozen by an OS suspend; hand the wake off to the
     * FX thread. The monotonic-clock gap is logged alongside for diagnosis — on a
     * true suspend it typically lags wall-clock (the process wasn't running), while
     * the two moving together points at a "modern standby" that never froze us.
     */
    private void checkForSleep() {
        long wallNow = System.currentTimeMillis();
        long nanoNow = System.nanoTime();
        long wallGapMs = wallNow - sleepWatchWallMs;
        long monoGapMs = (nanoNow - sleepWatchNanos) / 1_000_000L;
        sleepWatchWallMs = wallNow;
        sleepWatchNanos = nanoNow;

        if (wallGapMs <= SLEEP_GAP_SECONDS * 1000L) {
            return;
        }
        LocalDateTime wokeAt = LocalDateTime.now();
        LocalDateTime sleptAt = wokeAt.minus(java.time.Duration.ofMillis(wallGapMs));
        LOG.log(System.Logger.Level.INFO,
                "Wake detected: wall gap " + wallGapMs + "ms, monotonic gap " + monoGapMs
                        + "ms; treating " + sleptAt + " → " + wokeAt + " as slept");
        Platform.runLater(() -> onWakeFromSleep(sleptAt, wokeAt));
    }

    /** FX-thread half of wake handling: resume the running session, if any. */
    private void onWakeFromSleep(LocalDateTime sleptAt, LocalDateTime wokeAt) {
        if (runningSince == null || !sleptAt.isAfter(runningSince)) {
            return; // nothing tracking, or the "sleep" predates this session
        }
        resumeAfterSleep(sleptAt, wokeAt);
    }

    /**
     * Handles a detected system sleep while tracking: closes the pre-sleep entry
     * at the last awake instant (so the slept time isn't billed) and immediately
     * opens a fresh entry for the same project as of the wake instant, leaving the
     * timer running. {@code runningSince} is shifted back by the time already
     * worked this session so the on-screen timer keeps climbing across the gap
     * rather than resetting to zero; the committed totals stay correct because the
     * closed segment isn't folded into the base until the session is stopped.
     *
     * <p>Also raises a native OS notification: the widget may be off-screen, not
     * on top, or on another virtual desktop when the machine wakes.
     */
    private void resumeAfterSleep(LocalDateTime sleptAt, LocalDateTime wokeAt) {
        Project project = projectCombo.getValue();
        if (project == null) {
            // Nothing to resume onto — fall back to just stopping.
            stopTracking(sleptAt, "Stopped — system was asleep");
            tray.notify("Timer stopped", "The machine slept and no project was selected.");
            return;
        }
        long workedSeconds =
                Math.max(0, java.time.Duration.between(runningSince, sleptAt).getSeconds());
        String slept = formatHms(java.time.Duration.between(sleptAt, wokeAt).getSeconds());

        timeEntryDao.stop(runningEntryId, sleptAt);
        runningEntryId = timeEntryDao.start(project.id(), wokeAt);
        runningSince = wokeAt.minusSeconds(workedSeconds);

        statusLabel.setText("Resumed after sleep (" + slept + " asleep) — " + project.name());
        updateTimerLabel();
        tray.notify("Timer resumed",
                "Was asleep " + slept + ". Still tracking " + project.name() + ".");
        LOG.log(System.Logger.Level.INFO,
                "Resumed after sleep: closed entry at " + sleptAt + ", opened entry "
                        + runningEntryId + " at " + wokeAt);
    }

    /** Loads committed today/all-time totals for a project into the labels and the live base. */
    private void refreshTotals(Project project) {
        if (project == null) {
            baseTodaySeconds = 0;
            baseTotalSeconds = 0;
        } else {
            baseTodaySeconds = timeEntryDao.totalSeconds(project.id(), true);
            baseTotalSeconds = timeEntryDao.totalSeconds(project.id(), false);
        }
        todayLabel.setText(formatHms(baseTodaySeconds));
        totalLabel.setText(formatHms(baseTotalSeconds));
    }

    private void updateTimerLabel() {
        if (runningSince == null) {
            return;
        }
        long elapsed = java.time.Duration.between(runningSince, LocalDateTime.now()).getSeconds();
        timerLabel.setText(formatHms(elapsed));
        todayLabel.setText(formatHms(baseTodaySeconds + elapsed));
        totalLabel.setText(formatHms(baseTotalSeconds + elapsed));
    }

    private static String formatHms(long seconds) {
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static VBox statBox(String caption, Label value) {
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("stat-caption");
        value.getStyleClass().add("stat-value");
        VBox box = new VBox(2, captionLabel, value);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** The four fields shared by the add and edit project dialogs. */
    private record ProjectFormFields(TextField code, TextField name, TextField client, TextArea description) {
    }

    /**
     * Dialogs (Add/Edit Project, the export help Alert) open in their own window with
     * its own Scene, so the main Scene's stylesheet (applied in {@link #buildScene})
     * never reaches them — without this they fall back to a default light theme that
     * clashes with the app's dark one.
     */
    private void applyAppTheme(DialogPane pane) {
        pane.getStylesheets().add(App.class.getResource("/styles.css").toExternalForm());
    }

    /** Builds the code/name/client/description grid shared by both project dialogs. */
    private ProjectFormFields buildProjectFormGrid(GridPane grid) {
        TextField code = new TextField();
        code.setPromptText("e.g. ACME-2026");
        TextField name = new TextField();
        name.setPromptText("Required");
        TextField client = new TextField();
        TextArea description = new TextArea();
        description.setPrefRowCount(3);

        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Project ID"), code);
        grid.addRow(1, new Label("Name"), name);
        grid.addRow(2, new Label("Client"), client);
        grid.addRow(3, new Label("Description"), description);

        return new ProjectFormFields(code, name, client, description);
    }

    private void showAddProjectDialog(Stage owner) {
        Dialog<Project> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Register Project");
        applyAppTheme(dialog.getDialogPane());

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        ProjectFormFields fields = buildProjectFormGrid(grid);
        dialog.getDialogPane().setContent(grid);

        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.setDisable(true);
        fields.name().textProperty().addListener((o, was, now) -> saveButton.setDisable(now.trim().isEmpty()));

        dialog.setResultConverter(button -> {
            if (button == saveType) {
                return new Project(null,
                        fields.code().getText().trim(),
                        fields.name().getText().trim(),
                        fields.description().getText().trim(),
                        fields.client().getText().trim(),
                        true);
            }
            return null;
        });

        dialog.showAndWait().ifPresent(p -> {
            Project saved = projectDao.insert(p);
            refreshProjects();
            projectCombo.setValue(saved);
            statusLabel.setText("Registered " + saved.name());
        });
    }

    /** Edits {@code existing}'s code/name/client/description in place. No-op if none is selected. */
    private void showEditProjectDialog(Stage owner, Project existing) {
        if (existing == null) {
            return;
        }
        Dialog<Project> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Edit Project");
        applyAppTheme(dialog.getDialogPane());

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        ProjectFormFields fields = buildProjectFormGrid(grid);
        fields.code().setText(existing.code());
        fields.name().setText(existing.name());
        fields.client().setText(existing.client());
        fields.description().setText(existing.description());
        dialog.getDialogPane().setContent(grid);

        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        fields.name().textProperty().addListener((o, was, now) -> saveButton.setDisable(now.trim().isEmpty()));

        dialog.setResultConverter(button -> {
            if (button == saveType) {
                return new Project(existing.id(),
                        fields.code().getText().trim(),
                        fields.name().getText().trim(),
                        fields.description().getText().trim(),
                        fields.client().getText().trim(),
                        existing.active());
            }
            return null;
        });

        dialog.showAndWait().ifPresent(p -> {
            projectDao.update(p);
            refreshProjects();
            statusLabel.setText("Updated " + p.name());
        });
    }

    /** Explains, in plain terms, how Daily Summary export and workstream categorization work. */
    private void showSummaryHelp(Stage owner) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("Export & categorization");
        alert.setHeaderText("How Daily Summary export works");
        applyAppTheme(alert.getDialogPane());
        alert.setResizable(true);
        alert.setContentText("""
                Pressing "Daily Summary" writes one daily-summary-<DATE>.json file per day, \
                for every day from the last checkpoint through today that has completed \
                (stopped) tracked time. Days with no completed time get no file. Today's \
                file is rewritten on every press, since today's tracking may still be in \
                progress. The checkpoint then advances to today.

                Each project's time is categorized into a workstream purely from its code \
                prefix, computed fresh at export time:
                  • xpq-sred* → sred
                  • any other xpq* (e.g. xpq-eng, xpq-techops) → engineering
                  • anything else → client

                Each entry also carries the project's current name, description, and \
                client — read live from the project record at export time. Editing a \
                project's metadata (the … button) doesn't change files already written, \
                but is reflected in every summary generated afterward.""");
        alert.getDialogPane().setPrefSize(420, 320);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        stopSleepWatch();
        if (runningEntryId != null) {
            // Don't lose an in-progress session on a hard close.
            timeEntryDao.stop(runningEntryId, LocalDateTime.now());
        }
        tray.remove();
        if (database != null) {
            database.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
