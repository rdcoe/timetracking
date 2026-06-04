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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
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
import java.util.List;
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
    private TextField addTimeField;
    private HBox manualAddRow;
    private Label statusLabel;
    private Timeline ticker;

    private Long runningEntryId;
    private LocalDateTime runningSince;
    // Wall-clock time of the previous tick. The gap between ticks is normally
    // ~1s; a much larger gap means the JVM was frozen by a system suspend, so we
    // stop tracking as of this instant (the last moment we know we were awake).
    private LocalDateTime lastTick;
    // Completed totals for the selected project, captured so the live session
    // can be added on top each tick without re-querying every second.
    private long baseTodaySeconds;
    private long baseTotalSeconds;

    // A tick gap beyond this is treated as a system sleep rather than a hiccup.
    private static final long SLEEP_GAP_SECONDS = 30;

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

        Button manageButton = new Button("＋");
        manageButton.setTooltip(new Tooltip("Register a project"));
        manageButton.setOnAction(e -> showProjectDialog(stage));

        HBox projectRow = new HBox(6, projectCombo, manageButton);
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

        // Manually log a block of time (HH:MM) onto the selected project.
        // Disabled while the timer runs so it can't race the live session.
        Label addCaption = new Label("Add time");
        addCaption.getStyleClass().add("stat-caption");
        addTimeField = new TextField();
        addTimeField.setPromptText("HH:MM");
        addTimeField.setPrefColumnCount(5);
        addTimeField.setMaxWidth(64);
        addTimeField.setOnAction(e -> addManualTime()); // Enter submits
        Button addButton = new Button("Add");
        addButton.setOnAction(e -> addManualTime());
        manualAddRow = new HBox(6, addCaption, addTimeField, addButton);
        manualAddRow.setAlignment(Pos.CENTER);

        // Writes today's per-workstream time summary to disk for the
        // xpquest-daily-log skill to pick up. Allowed while tracking — it only
        // reads completed entries, so the live session simply isn't included yet.
        Button summaryButton = new Button("Daily Summary");
        summaryButton.setTooltip(new Tooltip(
                "Write today's per-workstream time summary for the daily-log skill"));
        summaryButton.setOnAction(e -> writeDailySummary());
        HBox summaryRow = new HBox(summaryButton);
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
            lastTick = runningSince;
            runningEntryId = timeEntryDao.start(project.id(), runningSince);
            refreshTotals(project); // capture the committed base before this session
            projectCombo.setDisable(true);
            manualAddRow.setDisable(true);
            toggleButton.setText("Stop");
            statusLabel.setText("Tracking — " + project.name());
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
        runningEntryId = null;
        runningSince = null;
        lastTick = null;
        projectCombo.setDisable(false);
        manualAddRow.setDisable(false);
        toggleButton.setText("Start");
        timerLabel.setText(formatHms(0));
        statusLabel.setText(status);
        refreshTotals(project);
    }

    /**
     * Logs a manually-entered block of time (HH:MM) onto the selected project as
     * a completed entry ending now, so it counts toward Today and Total. No-op
     * while the timer is running, to avoid racing the live session.
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
        long seconds = parseHhmm(addTimeField.getText());
        if (seconds <= 0) {
            statusLabel.setText("Enter time as HH:MM");
            return;
        }
        LocalDateTime end = LocalDateTime.now();
        timeEntryDao.addManual(project.id(), end.minusSeconds(seconds), end);
        addTimeField.clear();
        refreshTotals(project);
        statusLabel.setText("Added " + formatHms(seconds));
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
     * Writes today's per-project tracked totals to
     * {@code daily-summary-<DATE>.json} for the xpquest-daily-log skill to consume.
     * Each project is tagged with a workstream inferred from its code (see
     * {@link #workstream}) so the skill can route XP Quest engineering/SR&ED hours
     * into the daily logs and client hours into a separate log.
     *
     * <p>Target directory is {@code $XPQUEST_SUMMARY_DIR} when set, otherwise
     * {@code user.home/.xpquest} — which is {@code %USERPROFILE%\.xpquest} on
     * Windows and {@code $HOME/.xpquest} on Linux (one code path, OS-native home,
     * the same proven-writable dir that already holds the H2 database file).
     */
    private void writeDailySummary() {
        LocalDate today = LocalDate.now();
        List<DailySummaryRow> rows = timeEntryDao.dailySummary(today);
        if (rows.isEmpty()) {
            statusLabel.setText("No completed time logged today");
            return;
        }
        Path out = summaryDir().resolve("daily-summary-" + today + ".json");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, buildSummaryJson(today, rows), StandardCharsets.UTF_8);
            statusLabel.setText("Wrote " + out.getFileName());
        } catch (IOException ex) {
            statusLabel.setText("Summary write failed: " + ex.getMessage());
        }
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
     * Maps a project code to its workstream: {@code xpq-eng*} → engineering,
     * {@code xpq-sred*} → sred, anything else (e.g. {@code acme-corp}) → client.
     */
    private static String workstream(String code) {
        String c = code == null ? "" : code.trim().toLowerCase();
        if (c.startsWith("xpq-sred")) {
            return "sred";
        }
        if (c.startsWith("xpq-eng")) {
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
     * Per-second tick. Detects a system sleep (a tick gap far larger than the
     * expected ~1s, because the JVM was frozen while suspended) and, if found,
     * stops tracking as of the last awake instant so the slept time isn't billed.
     */
    private void onTick() {
        if (runningSince == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (lastTick != null
                && java.time.Duration.between(lastTick, now).getSeconds() > SLEEP_GAP_SECONDS) {
            stopTracking(lastTick, "Stopped — system was asleep");
            return;
        }
        lastTick = now;
        updateTimerLabel();
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

    private void showProjectDialog(Stage owner) {
        Dialog<Project> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Register Project");

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField code = new TextField();
        code.setPromptText("e.g. ACME-2026");
        TextField name = new TextField();
        name.setPromptText("Required");
        TextField client = new TextField();
        TextArea description = new TextArea();
        description.setPrefRowCount(3);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Project ID"), code);
        grid.addRow(1, new Label("Name"), name);
        grid.addRow(2, new Label("Client"), client);
        grid.addRow(3, new Label("Description"), description);
        dialog.getDialogPane().setContent(grid);

        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.setDisable(true);
        name.textProperty().addListener((o, was, now) -> saveButton.setDisable(now.trim().isEmpty()));

        dialog.setResultConverter(button -> {
            if (button == saveType) {
                return new Project(null,
                        code.getText().trim(),
                        name.getText().trim(),
                        description.getText().trim(),
                        client.getText().trim(),
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

    @Override
    public void stop() {
        if (runningEntryId != null) {
            // Don't lose an in-progress session on a hard close.
            timeEntryDao.stop(runningEntryId, LocalDateTime.now());
        }
        if (database != null) {
            database.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
