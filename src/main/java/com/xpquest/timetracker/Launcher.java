package com.xpquest.timetracker;

/**
 * Plain (non-JavaFX) entry point.
 *
 * <p>Launching the {@link javafx.application.Application} subclass directly from
 * a non-modular / shaded classpath triggers the infamous
 * "JavaFX runtime components are missing" error. Bootstrapping through this
 * separate class avoids that, and is also what the native image entry point uses.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
