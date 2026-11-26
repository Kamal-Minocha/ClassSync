package com.classsync.view;

import javafx.scene.Scene;

import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Light/dark theme switching. base.css holds every component style and refers to colour
 * tokens (-cs-*) that are defined in light.css or dark.css.
 *
 * Preference storage is deliberately simple: the signed-in user's choice is saved in the
 * users.theme column, and the last choice on this computer is kept in java.util.prefs so
 * the login screen opens in the same theme.
 */
public final class Theme {

    private static final Preferences PREFS = Preferences.userNodeForPackage(Theme.class);

    private Theme() {
    }

    public static void apply(Scene scene, boolean dark) {
        scene.getStylesheets().setAll(
                css(dark ? "/css/dark.css" : "/css/light.css"),
                css("/css/base.css"));
        scene.getRoot().getProperties().put("cs-dark", dark);
        saveLocalPreference(dark);
    }

    public static boolean isDark(Scene scene) {
        return scene.getStylesheets().stream().anyMatch(s -> s.endsWith("dark.css"));
    }

    public static boolean loadLocalPreference() {
        try {
            return PREFS.getBoolean("dark", false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void saveLocalPreference(boolean dark) {
        try {
            PREFS.putBoolean("dark", dark);
        } catch (RuntimeException ignored) {
            // preference storage unavailable (e.g. read-only container home): theme still works
        }
    }

    private static String css(String path) {
        return Objects.requireNonNull(Theme.class.getResource(path), path).toExternalForm();
    }
}
