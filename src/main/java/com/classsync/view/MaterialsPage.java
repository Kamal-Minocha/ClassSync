package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Course;
import com.classsync.model.CoursePermission;
import com.classsync.model.Material;
import com.classsync.model.MaterialKind;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.MaterialService;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Searchable, filterable, sortable table of study materials across the user's courses. */
public class MaterialsPage extends Page {

    private final TableView<Material> table = new TableView<>();
    private final ComboBox<Object> courseFilter = new ComboBox<>();
    private final ComboBox<Object> kindFilter = new ComboBox<>();
    private final ComboBox<String> sort = new ComboBox<>(FXCollections.observableArrayList("newest", "oldest", "title", "downloads"));
    private final TextField search = new TextField();
    private List<Course> uploadCourses = List.of();
    private java.util.Set<Long> organizeCourses = java.util.Set.of();

    public MaterialsPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.MATERIALS;
    }

    @Override
    public Node build() {
        User u = user();
        search.setPromptText("Search title, description or file name");
        search.setPrefColumnCount(24);
        search.setOnAction(e -> reload());
        // build() runs again on this instance when the page is retried or refreshed: columns are built once,
        // filter items are replaced rather than appended
        courseFilter.setOnAction(null);
        kindFilter.setOnAction(null);
        sort.setOnAction(null);
        courseFilter.getItems().setAll("All courses");
        courseFilter.setValue("All courses");
        kindFilter.getItems().setAll("All types", MaterialKind.MATERIAL, MaterialKind.PYQ, MaterialKind.REFERENCE);
        kindFilter.setValue("All types");
        sort.setValue("newest");
        courseFilter.setOnAction(e -> reload());
        kindFilter.setOnAction(e -> reload());
        sort.setOnAction(e -> reload());
        courseFilter.setConverter(labelConverter());
        Button go = Ui.button("Search", Icons.SEARCH);
        go.setOnAction(e -> reload());

        if (table.getColumns().isEmpty()) {
            buildTable();
        }
        HBox filters = Ui.row(8, search, go, courseFilter, kindFilter, Ui.label("Sort", "field-label"), sort);
        HBox.setHgrow(search, Priority.SOMETIMES);

        Button upload = Ui.primary("Upload material", Icons.UPLOAD);
        upload.setVisible(false);
        upload.setManaged(false);
        upload.setOnAction(e -> Forms.upload(ctx, shell, uploadCourses, null, null, id -> reload()));

        record CourseLists(List<Course> mine, List<Course> canUpload, java.util.Set<Long> canOrganize) { }
        load(() -> {
            List<Course> mine = ctx.courses().myCourses(u, false);
            List<Course> canUpload = new ArrayList<>();
            java.util.Set<Long> canOrganize = new java.util.HashSet<>();
            for (Course c : mine) {
                var perms = ctx.courses().myPermissions(u, c.id());
                if (perms.contains(CoursePermission.UPLOAD_MATERIALS)) {
                    canUpload.add(c);
                }
                if (perms.contains(CoursePermission.ORGANIZE_RESOURCES)) {
                    canOrganize.add(c.id());
                }
            }
            return new CourseLists(mine, canUpload, canOrganize);
        }, lists -> {
            courseFilter.getItems().addAll(lists.mine());
            uploadCourses = lists.canUpload();
            organizeCourses = lists.canOrganize();
            table.refresh();
            upload.setVisible(!uploadCourses.isEmpty());
            upload.setManaged(!uploadCourses.isEmpty());
        });
        reload();

        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPrefHeight(560);
        String subtitle = u.role() == Role.STUDENT ? "Published materials from your courses"
                : "Materials in your courses, including unpublished uploads you manage";
        return Ui.page(new VBox(16, Ui.pageHeader("Study Materials", subtitle, upload), filters, table));
    }

    private void buildTable() {
        table.setPlaceholder(Ui.emptyState(Icons.FILE, "No materials found", "Try another filter or search term.", null));
        TableColumn<Material, Material> type = new TableColumn<>("Type");
        type.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        type.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Material m, boolean empty) {
                super.updateItem(m, empty);
                setGraphic(empty || m == null ? null : Widgets.fileTypeChip(m.fileType()));
            }
        });
        type.setPrefWidth(70);
        type.setSortable(false);
        table.getColumns().add(type);
        table.getColumns().add(CourseDetailPage.column("Title", Material::title, 260));
        table.getColumns().add(CourseDetailPage.column("Course", Material::courseCode, 90));
        table.getColumns().add(CourseDetailPage.column("Lecture", m -> m.lectureNo() == null ? "—" : "L" + m.lectureNo(), 70));
        table.getColumns().add(CourseDetailPage.column("Kind", m -> m.kind().label(), 150));
        table.getColumns().add(CourseDetailPage.column("Uploaded by", Material::uploaderName, 150));
        table.getColumns().add(CourseDetailPage.column("Size", Material::sizeText, 80));
        table.getColumns().add(CourseDetailPage.column("Status", m -> m.published() ? "Published" : "Unpublished", 100));

        TableColumn<Material, Material> actions = new TableColumn<>("");
        actions.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        actions.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Material m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) {
                    setGraphic(null);
                    return;
                }
                Button open = Ui.iconButton(Icons.EYE, "Open " + m.originalFilename());
                open.setOnAction(e -> Widgets.openFile(ctx, shell, m));
                Button download = Ui.iconButton(Icons.DOWNLOAD, "Download " + m.originalFilename());
                download.setOnAction(e -> Widgets.downloadFile(ctx, shell, m));
                HBox box = new HBox(2, open, download);
                User u = ctx.session().user();
                // UI convenience only: MaterialService.setPublished re-checks the permission
                if (u != null && !m.published() && organizeCourses.contains(m.courseId())) {
                    Button publish = Ui.button("Publish", Icons.CHECK, "btn-ghost");
                    publish.setOnAction(e -> ctx.background().run(() -> {
                        ctx.materials().setPublished(u, m.id(), true);
                        return null;
                    }, r -> reload(), shell::showError));
                    box.getChildren().add(publish);
                }
                setGraphic(box);
            }
        });
        actions.setPrefWidth(190);
        actions.setSortable(false);
        table.getColumns().add(actions);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<Material>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && row.getItem().lectureId() != null) {
                    shell.open(new LectureDetailPage(ctx, shell, row.getItem().lectureId()));
                }
            });
            return row;
        });
    }

    private void reload() {
        User u = user();
        Long courseId = courseFilter.getValue() instanceof Course c ? c.id() : null;
        List<MaterialKind> kinds = kindFilter.getValue() instanceof MaterialKind k ? List.of(k) : null;
        MaterialService.Query q = new MaterialService.Query(courseId, null, kinds, search.getText(), sort.getValue(), 300);
        table.setDisable(true);
        load(() -> ctx.materials().list(u, q), items -> {
            table.getItems().setAll(items);
            table.setDisable(false);
        }, e -> {
            table.setDisable(false);
            shell.showError(e);
        });
    }

    private static javafx.util.StringConverter<Object> labelConverter() {
        return new javafx.util.StringConverter<>() {
            @Override public String toString(Object o) { return o instanceof Course c ? c.display() : o == null ? "" : o.toString(); }
            @Override public Object fromString(String s) { return null; }
        };
    }
}
