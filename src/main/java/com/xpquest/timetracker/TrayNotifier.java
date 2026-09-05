package com.xpquest.timetracker;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Thin wrapper over the AWT system tray, used to raise native OS notifications
 * (Windows Action Center toasts, Linux notification-daemon popups) that the user
 * still sees when the widget is not on top, minimised, or on another virtual
 * desktop.
 *
 * <p>Entirely best-effort. If the platform has no system tray, or the AWT classes
 * are unavailable at runtime (some native-image configurations), every method
 * becomes a silent no-op and the caller falls back to the in-window status line,
 * which always carries the same message.
 */
final class TrayNotifier {

    private static final System.Logger LOG = System.getLogger(TrayNotifier.class.getName());

    private TrayIcon trayIcon;

    /**
     * Installs a tray icon if the platform supports one. Call once at startup.
     * Silently does nothing (leaving notifications as no-ops) on any failure.
     */
    void install() {
        try {
            if (!SystemTray.isSupported()) {
                LOG.log(System.Logger.Level.INFO,
                        "System tray not supported; sleep/wake notifications disabled");
                return;
            }
            TrayIcon icon = new TrayIcon(iconImage(), "XP Quest Time Tracker");
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
            this.trayIcon = icon;
            LOG.log(System.Logger.Level.INFO, "System tray notifier installed");
        } catch (AWTException | RuntimeException | LinkageError e) {
            // No usable tray — notify() will be a no-op from here on.
            this.trayIcon = null;
            LOG.log(System.Logger.Level.WARNING, "Tray notifier unavailable: " + e);
        }
    }

    /** Shows an OS notification, or does nothing when no tray icon is installed. */
    void notify(String caption, String text) {
        TrayIcon icon = this.trayIcon;
        if (icon == null) {
            return;
        }
        try {
            EventQueue.invokeLater(
                    () -> icon.displayMessage(caption, text, TrayIcon.MessageType.INFO));
        } catch (RuntimeException | LinkageError e) {
            LOG.log(System.Logger.Level.WARNING, "Tray notification failed: " + e);
        }
    }

    /** Removes the tray icon. Safe to call even if nothing was installed. */
    void remove() {
        TrayIcon icon = this.trayIcon;
        if (icon == null) {
            return;
        }
        try {
            SystemTray.getSystemTray().remove(icon);
        } catch (RuntimeException | LinkageError e) {
            // Nothing more we can do; the icon goes away with the process anyway.
        }
        this.trayIcon = null;
    }

    /**
     * A tiny green disc drawn pixel-by-pixel (no {@code Graphics2D}, keeping the
     * AWT surface minimal for native image) so we don't ship an image resource
     * that would also need a native-image resource-config entry.
     */
    private static Image iconImage() {
        int size = 16;
        double centre = (size - 1) / 2.0;
        double radiusSq = centre * centre;
        int green = 0xFFA6E3A1; // matches the app's "tracking" green
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = x - centre;
                double dy = y - centre;
                img.setRGB(x, y, dx * dx + dy * dy <= radiusSq ? green : 0x00000000);
            }
        }
        return img;
    }
}
