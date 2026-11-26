package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.AdminDao;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AdminService;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Dialogs for the admin user-management screens. The service re-checks every rule. */
public final class AdminForms {

    private AdminForms() {
    }

    /** New account of any type. preset = initially selected type (may be null). */
    public static void create(AppContext ctx, AppShell shell, Role preset, Runnable onSaved) {
        User admin = ctx.session().requireUser();
        ComboBox<Role> role = new ComboBox<>(FXCollections.observableArrayList(Role.STUDENT, Role.CR, Role.PROFESSOR, Role.ADMIN));
        role.setConverter(QuizForms.converter(Role::label));
        role.setValue(preset == null ? Role.STUDENT : preset);
        role.setMaxWidth(Double.MAX_VALUE);
        TextField name = field("Full name, e.g. Arjun Verma");
        TextField email = field("name@college.edu");
        TextField enrollment = field("e.g. CS2024050");
        ComboBox<Integer> semester = Forms.numbers(1, 12, 1);
        TextField employee = field("e.g. EMP205");
        TextField designation = field("e.g. Assistant Professor");
        TextField department = field("e.g. Computer Science");
        TextField password = field("8+ characters, a letter and a digit");
        Button generate = Ui.button("Generate", Icons.KEY, "btn-ghost");
        generate.setOnAction(e -> password.setText(ctx.admin().generatePassword()));
        HBox.setHgrow(password, Priority.ALWAYS);

        GridPane grid = Forms.grid();
        VBox learner = new VBox(12, Ui.formRow("Enrollment number", enrollment, "6–15 capital letters or digits"),
                Ui.formRow("Semester", semester, null));
        VBox professor = new VBox(12, Ui.formRow("Employee number", employee, null),
                Ui.formRow("Designation", designation, "Optional — defaults to Professor"));
        VBox departmentRow = Ui.formRow("Department", department, null);
        grid.add(Ui.formRow("Account type", role, null), 0, 0, 2, 1);
        grid.add(Ui.formRow("Full name", name, null), 0, 1, 2, 1);
        grid.add(Ui.formRow("Email", email, "Used to sign in"), 0, 2, 2, 1);
        grid.add(learner, 0, 3, 2, 1);
        grid.add(professor, 0, 4, 2, 1);
        grid.add(departmentRow, 0, 5, 2, 1);
        grid.add(Ui.formRow("Initial password", Ui.row(8, password, generate),
                "Shown here so you can give it to the user privately. Only a hash is stored."), 0, 6, 2, 1);
        Runnable showFields = () -> {
            Role r = role.getValue();
            show(learner, r.isLearner());
            show(professor, r == Role.PROFESSOR);
            show(departmentRow, r != Role.ADMIN);
        };
        role.valueProperty().addListener((o, a, b) -> showFields.run());
        showFields.run();

        boolean ok = Dialogs.form(shell.window(), "Add user", grid, "Create account", () -> {
            if (name.getText().isBlank() || email.getText().isBlank()) {
                return "Enter the full name and email.";
            }
            if (role.getValue().isLearner() && enrollment.getText().isBlank()) {
                return "Enter the enrollment number.";
            }
            if (role.getValue() == Role.PROFESSOR && employee.getText().isBlank()) {
                return "Enter the employee number.";
            }
            if (role.getValue() != Role.ADMIN && department.getText().isBlank()) {
                return "Enter the department.";
            }
            return password.getText().length() < 8 ? "The password needs at least 8 characters." : null;
        });
        if (!ok) {
            return;
        }
        var form = new AdminService.NewUser(role.getValue(), name.getText(), email.getText(), enrollment.getText(),
                employee.getText(), department.getText(), semester.getValue(), designation.getText(),
                password.getText().toCharArray());
        password.clear();
        ctx.background().run(() -> ctx.admin().create(admin, form), id -> {
            shell.toast().success(form.role().label() + " account created for " + form.fullName().trim() + ".");
            onSaved.run();
        }, shell::showError);
    }

    /** Edit name and profile details. */
    public static void edit(AppContext ctx, AppShell shell, AdminDao.UserRow u, Runnable onSaved) {
        User admin = ctx.session().requireUser();
        TextField name = field("Full name");
        name.setText(u.fullName());
        TextField department = field("Department");
        department.setText(u.department() == null ? "" : u.department());
        ComboBox<Integer> semester = Forms.numbers(1, 12, u.semester() == null ? 1 : u.semester());
        TextField designation = field("Designation");
        designation.setText(u.designation() == null ? "" : u.designation());
        GridPane grid = Forms.grid();
        grid.add(Ui.wrapping(u.role().label() + " · " + u.email()
                + (u.identifier().isBlank() ? "" : " · " + u.identifier()), "muted"), 0, 0, 2, 1);
        grid.add(Ui.formRow("Full name", name, null), 0, 1, 2, 1);
        if (u.role() != Role.ADMIN) {
            grid.add(Ui.formRow("Department", department, null), 0, 2, 2, 1);
        }
        if (u.role().isLearner()) {
            grid.add(Ui.formRow("Semester", semester, null), 0, 3);
        } else if (u.role() == Role.PROFESSOR) {
            grid.add(Ui.formRow("Designation", designation, null), 0, 3, 2, 1);
        }
        grid.add(Ui.wrapping("Email, enrollment and employee numbers identify the account and are not changed here.",
                "field-hint"), 0, 4, 2, 1);
        if (!Dialogs.form(shell.window(), "Edit " + u.fullName(), grid, "Save changes",
                () -> name.getText().trim().length() < 2 ? "Enter the full name." : null)) {
            return;
        }
        var details = new AdminService.Details(name.getText(), department.getText(), semester.getValue(), designation.getText());
        ctx.background().run(() -> {
            ctx.admin().updateDetails(admin, u.id(), details);
            return null;
        }, r -> {
            shell.toast().success("Saved.");
            onSaved.run();
        }, shell::showError);
    }

    /** Set a new password for the account. */
    public static void resetPassword(AppContext ctx, AppShell shell, AdminDao.UserRow u, Runnable onSaved) {
        User admin = ctx.session().requireUser();
        TextField password = field("8+ characters, a letter and a digit");
        Button generate = Ui.button("Generate", Icons.KEY, "btn-ghost");
        generate.setOnAction(e -> password.setText(ctx.admin().generatePassword()));
        HBox.setHgrow(password, Priority.ALWAYS);
        VBox content = new VBox(12, Ui.wrapping("Set a new password for " + u.fullName() + " (" + u.email() + "). "
                        + "The old password stops working immediately. Give the new one to the user privately.", "muted"),
                Ui.formRow("New password", Ui.row(8, password, generate), null));
        if (!Dialogs.form(shell.window(), "Reset password", content, "Set password",
                () -> password.getText().length() < 8 ? "The password needs at least 8 characters." : null)) {
            return;
        }
        char[] pw = password.getText().toCharArray();
        password.clear();
        ctx.background().run(() -> {
            ctx.admin().resetPassword(admin, u.id(), pw);
            return null;
        }, r -> {
            shell.toast().success("Password changed for " + u.fullName() + ".");
            onSaved.run();
        }, shell::showError);
    }

    private static TextField field(String prompt) {
        TextField t = new TextField();
        t.setPromptText(prompt);
        return t;
    }

    private static void show(javafx.scene.Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }
}
