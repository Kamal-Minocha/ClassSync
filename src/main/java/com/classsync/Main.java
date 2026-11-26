package com.classsync;

import com.classsync.config.AppConfig;
import com.classsync.view.AppShell;
import com.classsync.view.StartupView;
import com.classsync.view.Theme;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFX application lifecycle:
 *   init()  – background launcher thread: load configuration (no UI work allowed here)
 *   start() – JavaFX Application Thread: build the Stage and Scene
 *   stop()  – JavaFX Application Thread: release background threads
 */
public class Main extends Application {

    private static final Logger LOG = Logger.getLogger("classsync");
    private AppContext context;
    private Throwable configError;

    @Override
    public void init() {
        try {
            context = new AppContext(AppConfig.load());
        } catch (RuntimeException e) {
            configError = e;
            LOG.log(Level.SEVERE, "Configuration error", e);
        }
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("ClassSync");
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        var icon = Main.class.getResourceAsStream("/images/thapar-mark.png");
        if (icon != null) {
            stage.getIcons().add(new Image(icon));
        }

        Scene scene = new Scene(new javafx.scene.layout.StackPane(), 1280, 800);
        Theme.apply(scene, Theme.loadLocalPreference());
        stage.setScene(scene);

        if (configError != null) {
            scene.setRoot(StartupView.configurationError(configError.getMessage()));
        } else {
            AppShell shell = new AppShell(context, scene);
            shell.showStartup();
        }
        stage.show();
    }

    @Override
    public void stop() {
        if (context != null) {
            context.session().logout();
            context.background().shutdown();
        }
    }
}
