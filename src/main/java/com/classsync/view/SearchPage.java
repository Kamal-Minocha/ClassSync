package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Lecture;
import com.classsync.model.Material;
import com.classsync.model.MaterialKind;
import com.classsync.model.User;
import com.classsync.service.MaterialService;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Scoped search from the top bar: lectures and files in the user's own courses only.
 * Results come from the same permission-checked service methods as the normal pages.
 */
public class SearchPage extends Page {

    private final String query;

    public SearchPage(AppContext ctx, AppShell shell, String query) {
        super(ctx, shell);
        this.query = query;
    }

    @Override
    public Nav nav() {
        return Nav.NONE;
    }

    @Override
    public Node build() {
        User u = user();
        VBox lectures = new VBox(8, Ui.loading("Searching…"));
        VBox files = new VBox(8, Ui.loading("Searching…"));
        record Results(List<Lecture> lectures, List<Material> files) { }
        load(() -> new Results(ctx.lectures().search(u, query, 30),
                ctx.materials().list(u, new MaterialService.Query(null, null,
                        List.of(MaterialKind.MATERIAL, MaterialKind.PYQ, MaterialKind.REFERENCE, MaterialKind.NOTE),
                        query, "newest", 30))), r -> {
            lectures.getChildren().clear();
            files.getChildren().clear();
            if (r.lectures().isEmpty()) {
                lectures.getChildren().add(Ui.label("No matching lectures.", "faint"));
            }
            for (Lecture l : r.lectures()) {
                lectures.getChildren().add(Widgets.lectureRow(l, ctx.lectures().zone(),
                        () -> shell.open(new LectureDetailPage(ctx, shell, l.id()))));
            }
            if (r.files().isEmpty()) {
                files.getChildren().add(Ui.label("No matching files.", "faint"));
            }
            for (Material m : r.files()) {
                files.getChildren().add(Widgets.materialRow(ctx, shell, m));
            }
        });
        return Ui.page(new VBox(18, Ui.pageHeader("Search results", "“" + query + "” in your courses"),
                Ui.card("Lectures", lectures), Ui.card("Files and notes", files)));
    }
}
