package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.User;
import javafx.scene.Node;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Base class for every screen shown in the main content area.
 * A page builds its layout on the JavaFX thread and loads data with load(...),
 * which runs the query on a background thread and delivers the result back on the FX thread.
 */
public abstract class Page {

    protected final AppContext ctx;
    protected final AppShell shell;

    protected Page(AppContext ctx, AppShell shell) {
        this.ctx = ctx;
        this.shell = shell;
    }

    /** Sidebar entry highlighted while this page is open. */
    public abstract Nav nav();

    /** Builds the page's root node. Called on the JavaFX Application Thread. */
    public abstract Node build();

    protected User user() {
        return ctx.session().requireUser();
    }

    protected <T> void load(Callable<T> work, Consumer<T> onSuccess) {
        ctx.background().run(work, onSuccess, shell::showError);
    }

    protected <T> void load(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        ctx.background().run(work, onSuccess, onError);
    }
}
