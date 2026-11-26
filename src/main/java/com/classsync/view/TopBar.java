package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.User;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Top navigation bar: sidebar toggle, ClassSync identity, scoped search, theme toggle,
 * notifications and the profile/logout menu.
 */
class TopBar {

    private final HBox bar = new HBox();
    private final Button themeButton;
    private final TextField search = new TextField();
    private final AppContext ctx;
    private final javafx.scene.control.Label unread = Ui.label("", "counter-dot");
    private final javafx.animation.Timeline poller;

    TopBar(AppContext ctx, AppShell shell, User user) {
        this.ctx = ctx;
        bar.getStyleClass().add("topbar");

        Button menu = Ui.iconButton(Icons.MENU, "Collapse or expand the menu (Ctrl+B)");
        menu.setOnAction(e -> shell.toggleSidebar());

        themeButton = Ui.iconButton(Theme.isDark(shell.scene()) ? Icons.SUN : Icons.MOON, "Switch light/dark theme");
        themeButton.setOnAction(e -> shell.toggleTheme());

        StackPane avatar = new StackPane(Ui.label(user.initials()));
        avatar.getStyleClass().add("avatar");
        VBox who = new VBox(0, Ui.label(user.fullName(), "body-strong"), Ui.label(user.role().label(), "small", "muted"));
        HBox profileContent = new HBox(10, avatar, who);
        profileContent.setAlignment(Pos.CENTER_LEFT);
        Button profile = new Button();
        profile.setGraphic(profileContent);
        profile.getStyleClass().add("btn-ghost");
        profile.setAccessibleText("Profile menu for " + user.fullName());
        ContextMenu profileMenu = shell.profileMenu(user);
        profile.setOnAction(e -> AppShell.showBelow(profileMenu, profile));

        search.setPromptText("Search lectures and files in your courses  (Ctrl+F)");
        search.setAccessibleText("Search your courses");
        search.setOnAction(e -> {
            String q = search.getText().trim();
            if (q.length() >= 2) {
                shell.open(new SearchPage(ctx, shell, q));
            }
        });
        HBox searchBox = new HBox(Icons.of(Icons.SEARCH, 16), search);
        HBox.setHgrow(search, javafx.scene.layout.Priority.ALWAYS);
        searchBox.getStyleClass().add("search-box");
        searchBox.setPrefWidth(420);
        searchBox.setMaxWidth(460);
        searchBox.setMinWidth(180);

        Button bell = Ui.iconButton(Icons.BELL, "Notifications");
        bell.setOnAction(e -> shell.open(new NotificationsPage(ctx, shell)));
        unread.setVisible(false);
        unread.setMouseTransparent(true);
        StackPane bellBox = new StackPane(bell, unread);
        StackPane.setAlignment(unread, Pos.TOP_RIGHT);
        // simple refresh strategy: re-read the unread count every 30 seconds and after every navigation
        poller = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30), e -> refreshCounters()));
        poller.setCycleCount(javafx.animation.Animation.INDEFINITE);
        poller.play();

        bar.getChildren().addAll(menu, AppShell.brand(), Ui.spacer(), searchBox, Ui.spacer(), bellBox, themeButton, profile);
        bar.setAlignment(Pos.CENTER_LEFT);
    }

    Node node() {
        return bar;
    }

    void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    void refreshCounters() {
        User u = ctx.session().user();
        if (u == null) {
            return;
        }
        ctx.background().run(() -> ctx.notifications().unreadCount(u), n -> {
            unread.setText(n > 99 ? "99+" : String.valueOf(n));
            unread.setVisible(n > 0);
        }, e -> { });
    }

    /** Stops the polling timer (called at logout). */
    void dispose() {
        poller.stop();
    }

    void updateThemeIcon(boolean dark) {
        themeButton.setGraphic(Icons.of(dark ? Icons.SUN : Icons.MOON, 18));
    }
}
