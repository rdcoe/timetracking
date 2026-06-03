package com.xpquest.timetracker;

import com.xpquest.timetracker.dao.ProjectDao;
import com.xpquest.timetracker.dao.TimeEntryDao;
import com.xpquest.timetracker.db.Database;
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

import java.time.LocalDateTime;
import java.util.List;

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

        Region vSpacer = new Region();
        VBox.setVgrow(vSpacer, Priority.ALWAYS);

        VBox root = new VBox(10, projectRow, timerLabel, stats, toggleButton, vSpacer, bottomBar);
        root.setPadding(new Insets(12));
        root.setAlignment(Pos.TOP_CENTER);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 320, 280);
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
        toggleButton.setText("Start");
        timerLabel.setText(formatHms(0));
        statusLabel.setText(status);
        refreshTotals(project);
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
