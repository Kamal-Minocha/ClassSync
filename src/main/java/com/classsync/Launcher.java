package com.classsync;

import javafx.application.Application;

/**
 * Plain main class. Launching through a class that does not extend Application lets the
 * JavaFX jars run from the ordinary classpath (java -cp ... com.classsync.Launcher).
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(Main.class, args);
    }
}
