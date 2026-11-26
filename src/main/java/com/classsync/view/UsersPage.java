package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.AdminDao;
import com.classsync.model.Role;
import com.classsync.model.User;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * User management (administrators): search and filter every account; add, edit, reset password,
 * switch Student ↔ CR, disable / re-enable.
 */
public class UsersPage extends Page {

    private final Role initialRole;
    private final boolean initialDisabled;
    private final TableView<AdminDao.UserRow> table = new TableView<>();
    private final ComboBox<Object> role = new ComboBox<>();
    private final TextField search = new TextField();
    private final CheckBox disabled = new CheckBox("Include disabled accounts");
    private final Label count = Ui.label("", "small", "muted");
    private static final java.time.format.DateTimeFormatter SHORT_DATE =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);

    public UsersPage(AppContext ctx, AppShell shell) {
        this(ctx, shell, null, false);
    }

    public UsersPage(AppContext ctx, AppShell shell, Role initialRole, boolean includeDisabled) {
        super(ctx, shell);
        this.initialRole = initialRole;
        this.initialDisabled = includeDisabled;
    }

    @Override
    public Nav nav() {
        return Nav.USERS;
    }

    static Label roleBadge(Role r) {
        return Ui.badge(r.label(), switch (r) {
            case STUDENT -> "neutral";
            case CR -> "info";
            case PROFESSOR -> "primary";
            case ADMIN -> "warning";
        });
    }

    @Override
    public Node build() {
        // the shell's refresh() calls build() again on this same instance: columns are built once (re-adding them
        // would duplicate them, and clearing them loses the column widths); filters are reset, not appended to
        Object previousRole = role.getValue();
        role.setOnAction(null);
        role.getItems().setAll("All account types", Role.STUDENT, Role.CR, Role.PROFESSOR, Role.ADMIN);
        role.setConverter(QuizForms.converter(o -> o instanceof Role r ? r.label() : o.toString()));
        if (previousRole == null) {
            role.setValue(initialRole == null ? "All account types" : initialRole);
            disabled.setSelected(initialDisabled);
        } else {
            role.setValue(previousRole); // keep the admin's current filter across a refresh
        }
        search.setPromptText("Search name, email, enrollment or employee number");
        search.setPrefColumnCount(28);
        role.setOnAction(e -> reload());
        disabled.setOnAction(e -> reload());
        search.setOnAction(e -> reload());
        Button go = Ui.button("Search", Icons.SEARCH);
        go.setOnAction(e -> reload());
        Button add = Ui.primary("Add user", Icons.PLUS);
        add.setOnAction(e -> AdminForms.create(ctx, shell, role.getValue() instanceof Role r ? r : null, this::reload));

        if (table.getColumns().isEmpty()) {
            buildTable();
        }
        reload();
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPrefHeight(600);
        HBox filters = Ui.row(8, search, go, role, disabled, Ui.spacer(), count);
        HBox.setHgrow(search, Priority.SOMETIMES);
        return Ui.page(new VBox(16, Ui.pageHeader("User Management",
                "Every account in ClassSync. Disabling keeps all academic records; accounts are never deleted.", add),
                filters, table));
    }

    private void buildTable() {
        table.setPlaceholder(Ui.emptyState(Icons.USERS, "No accounts match", "Try another filter or search term.", null));
        table.getColumns().add(withTooltip(CourseDetailPage.column("Name", AdminDao.UserRow::fullName, 150)));
        table.getColumns().add(withTooltip(CourseDetailPage.column("Email", AdminDao.UserRow::email, 180)));
        TableColumn<AdminDao.UserRow, AdminDao.UserRow> type = new TableColumn<>("Type");
        type.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        type.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(AdminDao.UserRow r, boolean empty) {
                super.updateItem(r, empty);
                setGraphic(empty || r == null ? null : roleBadge(r.role()));
            }
        });
        type.setPrefWidth(145);
        type.setMinWidth(145);   // fits the widest badge, "Class Representative"
        table.getColumns().add(type);
        table.getColumns().add(CourseDetailPage.column("ID", AdminDao.UserRow::identifier, 95));
        table.getColumns().add(withTooltip(CourseDetailPage.column("Details", UsersPage::details, 190)));
        TableColumn<AdminDao.UserRow, AdminDao.UserRow> status = new TableColumn<>("Status");
        status.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(AdminDao.UserRow r, boolean empty) {
                super.updateItem(r, empty);
                setGraphic(empty || r == null ? null : r.active() ? Ui.badge("Active", "success") : Ui.badge("Disabled", "danger"));
            }
        });
        status.setPrefWidth(75);
        table.getColumns().add(status);
        TableColumn<AdminDao.UserRow, String> lastSignIn = CourseDetailPage.column("Last sign-in", r -> r.lastLoginAt() == null
                ? "Never" : r.lastLoginAt().atZone(ctx.lectures().zone()).format(SHORT_DATE), 105);
        lastSignIn.setMinWidth(105);   // "26 Nov 2026" in full
        table.getColumns().add(lastSignIn);

        TableColumn<AdminDao.UserRow, AdminDao.UserRow> actions = new TableColumn<>("");
        actions.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        actions.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(AdminDao.UserRow r, boolean empty) {
                super.updateItem(r, empty);
                setGraphic(empty || r == null ? null : actionsFor(r));
            }
        });
        actions.setPrefWidth(290);
        actions.setMinWidth(290);   // room for edit + reset + "Make student" + "Disable"; buttons are never cut to "…"
        actions.setSortable(false);
        table.getColumns().add(actions);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
    }

    /** Text cells that show their full value on hover (long emails and details are clipped to "…"). */
    private static TableColumn<AdminDao.UserRow, String> withTooltip(TableColumn<AdminDao.UserRow, String> col) {
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                setTooltip(empty || text == null || text.isBlank() ? null : new javafx.scene.control.Tooltip(text));
            }
        });
        return col;
    }

    /** Semester/courses (learners) or designation/courses taught (professors), then department: the short facts first. */
    static String details(AdminDao.UserRow r) {
        String dept = r.department() == null ? "" : " · " + r.department();
        if (r.semester() != null) {
            return "Sem " + r.semester() + " · " + QuizWidgets.plural(r.coursesEnrolled(), "course") + dept;
        }
        if (r.role() == Role.PROFESSOR) {
            return r.designation() + " · teaches " + r.coursesTaught() + dept;
        }
        return "Account administration";
    }

    private Node actionsFor(AdminDao.UserRow r) {
        User me = user();
        Button edit = Ui.iconButton(Icons.NOTE, "Edit " + r.fullName());
        edit.setOnAction(e -> AdminForms.edit(ctx, shell, r, this::reload));
        Button reset = Ui.iconButton(Icons.KEY, "Reset password for " + r.fullName());
        reset.setOnAction(e -> AdminForms.resetPassword(ctx, shell, r, this::reload));
        HBox box = new HBox(2, edit, reset);
        if (r.role().isLearner()) {
            Role target = r.role() == Role.CR ? Role.STUDENT : Role.CR;
            Button switchRole = Ui.button(target == Role.CR ? "Make CR" : "Make student", null, "btn-ghost");
            switchRole.setOnAction(e -> {
                String note = target == Role.STUDENT
                        ? " Their class-representative rights in every course are removed." : " A professor still has to grant rights in each course.";
                if (Dialogs.confirm(shell.window(), "Change account type",
                        r.fullName() + " becomes a " + target.label().toLowerCase() + "." + note, "Change")) {
                    act(() -> ctx.admin().changeRole(me, r.id(), target), r.fullName() + " is now a " + target.label().toLowerCase() + ".");
                }
            });
            box.getChildren().add(switchRole);
        }
        if (r.id() != me.id()) {
            Button toggle = Ui.button(r.active() ? "Disable" : "Enable", null, "btn-ghost");
            toggle.setOnAction(e -> {
                if (r.active() && !Dialogs.confirm(shell.window(), "Disable account",
                        r.fullName() + " will not be able to sign in. All their records are kept and the account can be enabled again.",
                        "Disable")) {
                    return;
                }
                act(() -> ctx.admin().setActive(me, r.id(), !r.active()), r.active() ? "Account disabled." : "Account enabled.");
            });
            box.getChildren().add(toggle);
        }
        box.getChildren().forEach(b -> ((Button) b).setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE));
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    private interface Action {
        void run() throws Exception;
    }

    private void act(Action a, String done) {
        ctx.background().run(() -> {
            a.run();
            return null;
        }, r -> {
            shell.toast().success(done);
            reload();
        }, shell::showError);
    }

    private void reload() {
        User u = user();
        Role r = role.getValue() instanceof Role x ? x : null;
        String text = search.getText();
        boolean includeDisabled = disabled.isSelected();
        table.setDisable(true);
        load(() -> ctx.admin().list(u, r, text, includeDisabled), rows -> {
            table.getItems().setAll(rows);
            count.setText(QuizWidgets.plural(rows.size(), "account"));
            table.setDisable(false);
        }, e -> {
            table.setDisable(false);
            shell.showError(e);
        });
    }
}
