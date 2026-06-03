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
    private Button toggleButton;
    private Label statusLabel;
    private Timeline ticker;

    private Long runningEntryId;
    private LocalDateTime runningSince;

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

        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateTimerLabel()));
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

        toggleButton = new Button("Start");
        toggleButton.setMaxWidth(Double.MAX_VALUE);
        toggleButton.getStyleClass().add("toggle");
        toggleButton.setOnAction(e -> toggleTimer());

        CheckBox pin = new CheckBox("Always on top");
        pin.setSelected(true);
        pin.selectedProperty().addListener((o, was, now) -> stage.setAlwaysOnTop(now));

        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status");

        VBox root = new VBox(10, projectRow, timerLabel, toggleButton, pin, statusLabel);
        root.setPadding(new Insets(12));
        root.setAlignment(Pos.CENTER);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 320, 220);
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
            projectCombo.setDisable(true);
            toggleButton.setText("Stop");
            statusLabel.setText("Tracking — " + project.name());
            ticker.play();
            updateTimerLabel();
        } else {
            timeEntryDao.stop(runningEntryId, LocalDateTime.now());
            ticker.stop();
            runningEntryId = null;
            runningSince = null;
            projectCombo.setDisable(false);
            toggleButton.setText("Start");
            timerLabel.setText("00:00:00");
            statusLabel.setText("Saved");
        }
    }

    private void updateTimerLabel() {
        if (runningSince == null) {
            return;
        }
        long seconds = java.time.Duration.between(runningSince, LocalDateTime.now()).getSeconds();
        timerLabel.setText(String.format("%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60));
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
