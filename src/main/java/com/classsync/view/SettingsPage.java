package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.StudentProfile;
import com.classsync.model.User;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Optional;

/** Profile details and the theme preference. */
public class SettingsPage extends Page {

    public SettingsPage(AppContext ctx, AppShell shell) {
        super(ctx, shell);
    }

    @Override
    public Nav nav() {
        return Nav.SETTINGS;
    }

    @Override
    public Node build() {
        User u = user();
        StackPane avatar = new StackPane(Ui.label(u.initials()));
        avatar.getStyleClass().addAll("avatar", "avatar-large");

        GridPane details = new GridPane();
        details.setHgap(24);
        details.setVgap(10);
        addRow(details, 0, "Name", u.fullName());
        addRow(details, 1, "Email", u.email());
        addRow(details, 2, "Role", u.role().label());
        VBox profileCard = Ui.card("Profile", Ui.row(16, avatar, details));

        load(() -> u.role().isLearner() ? ctx.auth().studentProfile(u) : Optional.<StudentProfile>empty(),
                profile -> profile.ifPresent(p -> {
                    addRow(details, 3, "Enrollment no.", p.enrollmentNo());
                    addRow(details, 4, "Department", p.department());
                    addRow(details, 5, "Semester", String.valueOf(p.semester()));
                }));

        CheckBox dark = new CheckBox("Use dark theme");
        dark.setSelected(Theme.isDark(shell.scene()));
        dark.selectedProperty().addListener((o, was, now) -> {
            if (now != Theme.isDark(shell.scene())) {
                shell.toggleTheme();
            }
        });
        VBox appearance = Ui.card("Appearance", dark,
                Ui.wrapping("Your choice is saved to your account and used next time you sign in.", "muted", "small"));

        VBox about = Ui.card("About this installation",
                Ui.wrapping("Database: " + ctx.database().describe(), "muted", "small"),
                Ui.wrapping("Academic time zone: " + ctx.config().zone().getId(), "muted", "small"),
                Ui.wrapping("Keyboard: Ctrl+B toggles the menu · Tab moves between controls · Enter activates", "muted", "small"));

        HBox cards = new HBox(20, appearance, about);
        appearance.setPrefWidth(400);
        HBox.setHgrow(about, javafx.scene.layout.Priority.ALWAYS);
        return Ui.page(new VBox(20, Ui.pageHeader("Settings", "Your profile and preferences"), profileCard, cards));
    }

    private static void addRow(GridPane grid, int row, String label, String value) {
        grid.add(Ui.label(label, "field-label"), 0, row);
        grid.add(Ui.label(value), 1, row);
    }
}
