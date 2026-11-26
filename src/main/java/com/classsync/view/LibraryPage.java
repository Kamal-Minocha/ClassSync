package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.StudyDao.Target;
import com.classsync.model.Course;
import com.classsync.model.Material;
import com.classsync.model.MaterialKind;
import com.classsync.model.User;
import com.classsync.service.MaterialService;
import com.classsync.service.StudyService;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Central resource library: every PUBLISHED material, previous-year paper, reference and APPROVED note in the
 * user's courses, organised by course and unit. It reuses the materials records and the same MaterialService
 * access checks as everywhere else — no second copy of files or metadata.
 */
public class LibraryPage extends Page {

    public LibraryPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.LIBRARY;
    }

    @Override
    public Node build() {
        User u = user();
        ComboBox<Object> course = new ComboBox<>();
        course.getItems().add("All courses");
        course.setValue("All courses");
        course.setConverter(QuizForms.converter(o -> o instanceof Course c ? c.display() : o.toString()));
        ComboBox<Object> kind = new ComboBox<>(FXCollections.observableArrayList("All types", MaterialKind.MATERIAL,
                MaterialKind.PYQ, MaterialKind.REFERENCE, MaterialKind.NOTE));
        kind.setValue("All types");
        kind.setConverter(QuizForms.converter(o -> o instanceof MaterialKind k ? k.label() : o.toString()));
        ComboBox<Object> unit = new ComboBox<>();
        unit.getItems().add("All units");
        for (int i = 1; i <= 10; i++) {
            unit.getItems().add(i);
        }
        unit.setValue("All units");
        unit.setConverter(QuizForms.converter(o -> o instanceof Integer i ? "Unit " + i : o.toString()));
        CheckBox recommended = new CheckBox("Recommended only");
        TextField search = new TextField();
        search.setPromptText("Search the library");
        VBox results = new VBox(16, Ui.loading("Loading library…"));

        Runnable reload = () -> {
            Long courseId = course.getValue() instanceof Course c ? c.id() : null;
            List<MaterialKind> kinds = kind.getValue() instanceof MaterialKind k ? List.of(k)
                    : List.of(MaterialKind.MATERIAL, MaterialKind.PYQ, MaterialKind.REFERENCE, MaterialKind.NOTE);
            Integer unitNo = unit.getValue() instanceof Integer i ? i : null;
            results.getChildren().setAll(Ui.loading("Searching…"));
            record Data(List<Material> items, Set<Long> bookmarked) { }
            load(() -> {
                List<Material> items = ctx.materials().list(u, new MaterialService.Query(courseId, null, kinds,
                        search.getText(), "title", 500)).stream()
                        .filter(Material::published)                    // the library shows released resources only
                        .filter(m -> unitNo == null || unitNo.equals(m.unitNo()))
                        .filter(m -> !recommended.isSelected() || m.recommended())
                        .toList();
                Set<Long> marks = ctx.study().bookmarks(u).stream()
                        .filter(b -> b.target() == Target.MATERIAL).map(StudyService.Bookmark::targetId).collect(Collectors.toSet());
                return new Data(items, marks);
            }, d -> show(results, d.items(), d.bookmarked()));
        };
        course.setOnAction(e -> reload.run());
        kind.setOnAction(e -> reload.run());
        unit.setOnAction(e -> reload.run());
        recommended.setOnAction(e -> reload.run());
        search.setOnAction(e -> reload.run());
        load(() -> ctx.courses().myCourses(u, false), mine -> {
            course.getItems().addAll(mine);
            reload.run();
        });
        HBox filters = Ui.row(8, search, course, kind, unit, recommended);
        HBox.setHgrow(search, Priority.SOMETIMES);
        return Ui.page(new VBox(16, Ui.pageHeader("Resource library",
                "Lecture materials, previous-year papers, references and approved student notes, by course and unit."), filters, results));
    }

    private void show(VBox results, List<Material> items, Set<Long> bookmarked) {
        results.getChildren().clear();
        if (items.isEmpty()) {
            results.getChildren().add(Ui.emptyState(Icons.LIBRARY, "Nothing found", "Try another filter or search term.", null));
            return;
        }
        Map<String, List<Material>> groups = new TreeMap<>();
        for (Material m : items) {
            String key = m.courseCode() + " · " + (m.unitNo() == null ? "General" : "Unit " + m.unitNo());
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(m);
        }
        groups.forEach((title, list) -> {
            VBox rows = new VBox(8);
            for (Material m : list) {
                rows.getChildren().add(row(m, bookmarked.contains(m.id())));
            }
            results.getChildren().add(Ui.card(title + "  (" + list.size() + ")", rows));
        });
    }

    private Node row(Material m, boolean bookmarked) {
        HBox tags = Ui.row(6, Ui.badge(m.kind().label(), m.kind() == MaterialKind.NOTE ? "info" : "neutral"));
        if (m.recommended()) {
            tags.getChildren().add(Ui.badge("Recommended", "success"));
        }
        VBox text = new VBox(3, Ui.label(m.title(), "body-strong"), tags,
                Ui.label((m.lectureNo() == null ? "" : "L" + m.lectureNo() + " · ") + "by " + m.uploaderName() + " · "
                        + QuizWidgets.plural(m.downloadCount(), "download")
                        + (m.kind() == MaterialKind.NOTE ? " · " + QuizWidgets.plural(m.votes(), "upvote") : ""), "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        Button mark = StudyWidgets.bookmark(ctx, shell, Target.MATERIAL, m.id(), bookmarked);
        HBox row = Ui.row(12, Widgets.fileTypeChip(m.fileType()), text, mark, Widgets.fileActions(ctx, shell, m));
        row.getStyleClass().add("list-row");
        return row;
    }
}
