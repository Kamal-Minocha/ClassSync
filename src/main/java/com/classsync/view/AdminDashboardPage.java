package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.AdminDao;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AdminService.Overview;
import com.classsync.service.Errors;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZonedDateTime;

/** Administrator home: account totals, quick "add user" actions, newest accounts, recent sign-ins and admin log. */
public class AdminDashboardPage extends Page {

    public AdminDashboardPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.DASHBOARD;
    }

    @Override
    public Node build() {
        User u = user();
        VBox body = new VBox(20, hero(u), Ui.loading("Loading account overview…"));
        load(() -> ctx.admin().overview(u), o -> {
            body.getChildren().setAll(hero(u), stats(o), quickActions(), lists(o));
        }, e -> body.getChildren().setAll(hero(u), Ui.errorState(Errors.userMessage(e), shell::refresh)));
        return Ui.page(body);
    }

    private Node hero(User u) {
        ZonedDateTime now = ZonedDateTime.now(ctx.clock());
        VBox text = new VBox(6, Ui.label(now.format(Ui.DATE).toUpperCase(), "caption", "hero-sub"),
                Ui.label("Hello, " + u.firstName(), "h1"),
                Ui.wrapping("Create accounts for students, class representatives, professors and administrators, "
                        + "and keep them up to date.", "hero-sub"));
        HBox.setHgrow(text, Priority.ALWAYS);
        Button manage = Ui.button("Manage users", Icons.USERS);
        manage.setOnAction(e -> shell.open(Nav.USERS));
        HBox content = new HBox(16, text, manage);
        content.setAlignment(Pos.CENTER_LEFT);
        content.getStyleClass().add("hero-content");
        return Ui.photoBanner(Images.CAMPUS_MAIN, "hero-overlay", 20, false, content);
    }

    private Node stats(Overview o) {
        var a = o.activeByRole();
        HBox row = new HBox(16,
                card(Ui.statCard(Icons.USERS, String.valueOf(a.get(Role.STUDENT)), "Students", "Active accounts", null), Role.STUDENT),
                card(Ui.statCard(Icons.TARGET, String.valueOf(a.get(Role.CR)), "Class representatives", "Active accounts", "stat-violet"), Role.CR),
                card(Ui.statCard(Icons.BOOK_OPEN, String.valueOf(a.get(Role.PROFESSOR)), "Professors", "Active accounts", "stat-teal"), Role.PROFESSOR),
                card(Ui.statCard(Icons.KEY, String.valueOf(a.get(Role.ADMIN)), "Administrators", "Active accounts", "stat-amber"), Role.ADMIN),
                Ui.clickable(Ui.statCard(Icons.X, String.valueOf(o.disabled()), "Disabled accounts", "Cannot sign in; records kept", "stat-green"),
                        () -> shell.open(new UsersPage(ctx, shell, null, true))));
        return row;
    }

    private VBox card(VBox stat, Role role) {
        return Ui.clickable(stat, () -> shell.open(new UsersPage(ctx, shell, role, false)));
    }

    private Node quickActions() {
        HBox row = Ui.row(10, Ui.label("Add a new", "body-strong"));
        for (Role r : new Role[]{Role.STUDENT, Role.CR, Role.PROFESSOR, Role.ADMIN}) {
            Button b = Ui.button(r.label(), Icons.PLUS);
            b.setOnAction(e -> AdminForms.create(ctx, shell, r, shell::refresh));
            row.getChildren().add(b);
        }
        return Ui.card(row);
    }

    private Node lists(Overview o) {
        VBox newest = new VBox(8);
        for (AdminDao.UserRow r : o.newest()) {
            newest.getChildren().add(personRow(r, "Created " + r.createdAt().atZone(ctx.lectures().zone()).format(Ui.DATE)));
        }
        VBox signIns = new VBox(8);
        if (o.recentSignIns().isEmpty()) {
            signIns.getChildren().add(Ui.label("Nobody has signed in yet.", "faint"));
        }
        for (AdminDao.UserRow r : o.recentSignIns()) {
            signIns.getChildren().add(personRow(r, "Signed in " + r.lastLoginAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME)));
        }
        VBox log = new VBox(8);
        if (o.recentActions().isEmpty()) {
            log.getChildren().add(Ui.label("No account changes yet.", "faint"));
        }
        for (AdminDao.AuditRow a : o.recentActions()) {
            VBox text = new VBox(2, Ui.label(describe(a.action()) + (a.details().isBlank() ? "" : " · " + a.details()), "small", "body-strong"),
                    Ui.label(a.actorName() + " · " + a.at().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "small", "faint"));
            text.getStyleClass().add("list-row");
            log.getChildren().add(text);
        }
        VBox c1 = Ui.card("Newest accounts", newest);
        VBox c2 = Ui.card("Recent sign-ins", signIns);
        VBox c3 = Ui.card("Account changes", log);
        for (VBox c : new VBox[]{c1, c2, c3}) {
            HBox.setHgrow(c, Priority.ALWAYS);
            c.setPrefWidth(380);
        }
        return new HBox(20, c1, c2, c3);
    }

    private HBox personRow(AdminDao.UserRow r, String when) {
        VBox text = new VBox(2, Ui.label(r.fullName(), "body-strong"), Ui.label(when, "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox row = Ui.row(10, text, UsersPage.roleBadge(r.role()));
        row.getStyleClass().add("list-row");
        return row;
    }

    static String describe(String action) {
        return switch (action) {
            case "ADMIN_CREATE_USER" -> "Account created";
            case "ADMIN_UPDATE_USER" -> "Details edited";
            case "ADMIN_CHANGE_ROLE" -> "Role changed";
            case "ADMIN_RESET_PASSWORD", "PASSWORD_RESET" -> "Password reset";
            case "ADMIN_DISABLE_USER", "ACCOUNT_DEACTIVATE" -> "Account disabled";
            case "ADMIN_ENABLE_USER", "ACCOUNT_ACTIVATE" -> "Account enabled";
            case "PROVISION" -> "Account provisioned";
            default -> action;
        };
    }
}
