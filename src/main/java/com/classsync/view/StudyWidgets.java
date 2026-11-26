package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.StudyDao.Target;
import com.classsync.model.User;
import javafx.scene.control.Button;

/** Bookmark toggle shared by the library, lecture pages and doubt threads. */
final class StudyWidgets {

    private StudyWidgets() {
    }

    /** A bookmark button whose state is loaded in the background (null initial = look it up). */
    static Button bookmark(AppContext ctx, AppShell shell, Target target, long id, Boolean initiallyOn) {
        User u = ctx.session().requireUser();
        Button b = Ui.iconButton(Icons.BOOKMARK, "Bookmark");
        boolean[] on = {Boolean.TRUE.equals(initiallyOn)};
        Runnable paint = () -> {
            b.getStyleClass().remove("btn-primary");
            if (on[0]) {
                b.getStyleClass().add("btn-primary");
            }
            String text = on[0] ? "Remove bookmark" : "Bookmark";
            b.getTooltip().setText(text);
            b.setAccessibleText(text);
        };
        if (initiallyOn == null) {
            ctx.background().run(() -> ctx.study().isBookmarked(u, target, id), v -> {
                on[0] = v;
                paint.run();
            }, e -> { });
        }
        paint.run();
        b.setOnAction(e -> {
            boolean next = !on[0];
            ctx.background().run(() -> {
                ctx.study().bookmark(u, target, id, next);
                return null;
            }, r -> {
                on[0] = next;
                paint.run();
                shell.toast().success(next ? "Bookmarked." : "Bookmark removed.");
            }, shell::showError);
        });
        return b;
    }
}
