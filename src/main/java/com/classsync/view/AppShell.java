package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.config.SchemaVersion;
import com.classsync.controller.LoginController;
import com.classsync.model.User;
import com.classsync.service.Errors;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.sql.Connection;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main window structure once signed in:
 *
 *   StackPane (root, hosts toasts above everything)
 *     └ BorderPane
 *         top    = top bar (menu toggle, brand, search, theme, notifications, profile)
 *         left   = collapsible sidebar with role-relevant navigation
 *         center = StackPane content host with the current Page
 */
public class AppShell {

    private static final Logger LOG = Logger.getLogger("classsync");

    private final AppContext ctx;
    private final Scene scene;
    private final StackPane root = new StackPane();
    private final BorderPane frame = new BorderPane();
    private final StackPane content = new StackPane();
    private final VBox sidebar = new VBox();
    private final Map<Nav, Button> navButtons = new EnumMap<>(Nav.class);
    private final Toast toast;
    private TopBar topBar;
    private boolean sidebarCollapsed;
    private Page currentPage;

    public AppShell(AppContext ctx, Scene scene) {
        this.ctx = ctx;
        this.scene = scene;
        root.getChildren().add(frame);
        toast = new Toast(root);
        content.getStyleClass().add("content-host");
    }

    // ------------------------------------------------------------------ startup / login

    /** Readiness check: database reachable and schema at the expected version. */
    public void showStartup() {
        scene.setRoot(StartupView.checking());
        ctx.background().run(() -> {
            try (Connection con = ctx.database().open()) {
                return SchemaVersion.current(con);
            }
        }, version -> {
            if (version < SchemaVersion.REQUIRED) {
                scene.setRoot(StartupView.schemaOutdated(version, SchemaVersion.REQUIRED, this::showStartup));
            } else {
                showLogin();
            }
        }, error -> scene.setRoot(StartupView.connectionFailed(Errors.userMessage(error),
                ctx.database().describe(), this::showStartup)));
    }

    public void showLogin() {
        try {
            FXMLLoader loader = new FXMLLoader(AppShell.class.getResource("/fxml/login.fxml"));
            loader.setControllerFactory(type -> new LoginController(ctx, this));
            Parent login = loader.load();
            scene.setRoot(login);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Cannot load login screen", e);
            scene.setRoot(StartupView.configurationError("The login screen could not be loaded: " + e.getMessage()));
        }
    }

    /** Called by LoginController after a successful sign-in. */
    public void onSignedIn(User user) {
        ctx.session().login(user);
        Theme.apply(scene, "DARK".equals(user.theme()));
        buildFrame(user);
        scene.setRoot(root);
        open(Nav.DASHBOARD);
        toast.success("Welcome back, " + user.firstName() + "!");
    }

    public void logout() {
        ctx.session().logout();          // invalidates results of running tasks
        ctx.background().cancelAll();    // and cancels them
        currentPage = null;
        content.getChildren().clear();
        navButtons.clear();
        scene.getAccelerators().clear();
        if (topBar != null) {
            topBar.dispose();
        }
        topBar = null;
        showLogin();
    }

    // ------------------------------------------------------------------ frame

    private void buildFrame(User user) {
        topBar = new TopBar(ctx, this, user);
        frame.setTop(topBar.node());
        buildSidebar(user);
        frame.setLeft(sidebar);
        frame.setCenter(content);

        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN), this::toggleSidebar);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN), () -> topBar.focusSearch());
    }

    private void buildSidebar(User user) {
        sidebar.getChildren().clear();
        navButtons.clear();
        sidebar.getStyleClass().setAll("sidebar");
        sidebar.setPrefWidth(236);
        sidebar.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        VBox items = new VBox(3);
        Label menuCaption = Ui.label("MENU", "caption");
        items.getChildren().add(menuCaption);
        for (Nav nav : Pages.navFor(user.role())) {
            if (nav == Nav.SETTINGS) {
                continue;
            }
            items.getChildren().add(navButton(nav));
        }
        ScrollPane scroll = new ScrollPane(items);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox roleChip = new VBox(2, Ui.label("Signed in as", "small"), Ui.label(user.role().label(), "body-strong"));
        roleChip.getStyleClass().add("role-chip");
        roleChip.managedProperty().bind(roleChip.visibleProperty());

        sidebar.getChildren().addAll(scroll, navButton(Nav.SETTINGS), roleChip);
        applySidebarCollapsed();
    }

    private Button navButton(Nav nav) {
        Button b = new Button(nav.label(), Icons.of(nav.icon(), 18));
        b.getStyleClass().setAll("button", "nav-item");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setTooltip(new Tooltip(nav.label()));
        b.setOnAction(e -> open(nav));
        navButtons.put(nav, b);
        return b;
    }

    public void toggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed;
        applySidebarCollapsed();
    }

    private void applySidebarCollapsed() {
        sidebar.setPrefWidth(sidebarCollapsed ? 68 : 236);
        if (sidebarCollapsed) {
            sidebar.getStyleClass().add("sidebar-collapsed");
        } else {
            sidebar.getStyleClass().remove("sidebar-collapsed");
        }
        navButtons.values().forEach(b -> b.setContentDisplay(sidebarCollapsed
                ? javafx.scene.control.ContentDisplay.GRAPHIC_ONLY : javafx.scene.control.ContentDisplay.LEFT));
        sidebar.getChildren().stream().filter(n -> n.getStyleClass().contains("role-chip"))
                .forEach(n -> n.setVisible(!sidebarCollapsed));
        sidebar.lookupAll(".caption").forEach(n -> n.setVisible(!sidebarCollapsed));
    }

    // ------------------------------------------------------------------ navigation

    public void open(Nav nav) {
        Page page = Pages.create(nav, ctx, this);
        if (page != null) {
            open(page);
        }
    }

    public void open(Page page) {
        if (!ctx.session().isSignedIn()) {
            showLogin();
            return;
        }
        currentPage = page;
        navButtons.forEach((nav, button) -> {
            button.getStyleClass().remove("nav-item-active");
            if (nav == page.nav()) {
                button.getStyleClass().add("nav-item-active");
            }
        });
        // Park keyboard focus on the page's sidebar entry before swapping content. Otherwise, when the focused
        // control disappears with the old page, JavaFX may move focus into the middle of the new page and the
        // page's ScrollPane scrolls down to show it (pages were sometimes opening half-scrolled).
        Button navFocus = navButtons.get(page.nav());
        if (navFocus != null) {
            navFocus.requestFocus();
        } else {
            content.requestFocus();
        }
        Node view;
        try {
            view = page.build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Page failed to build", e);
            view = Ui.errorState(Errors.userMessage(e), () -> open(page));
        }
        content.getChildren().setAll(view);
        if (topBar != null) {
            topBar.refreshCounters();
        }
    }

    /** Re-opens the current page so it reloads its data. */
    public void refresh() {
        if (currentPage != null) {
            open(currentPage);
        }
    }

    // ------------------------------------------------------------------ helpers for pages

    public void showError(Throwable error) {
        toast.error(Errors.userMessage(error));
    }

    public Toast toast() {
        return toast;
    }

    public Window window() {
        return scene.getWindow();
    }

    public Scene scene() {
        return scene;
    }

    public void toggleTheme() {
        boolean dark = !Theme.isDark(scene);
        Theme.apply(scene, dark);
        User u = ctx.session().user();
        if (u != null) {
            ctx.background().run(() -> {
                ctx.auth().saveTheme(u, dark);
                return null;
            }, ok -> { }, this::showError);
        }
        topBar.updateThemeIcon(dark);
    }

    /** Profile menu shown from the avatar button. */
    ContextMenu profileMenu(User user) {
        MenuItem name = new MenuItem(user.fullName() + "  ·  " + user.role().label());
        name.setDisable(true);
        MenuItem settings = new MenuItem("Settings");
        settings.setOnAction(e -> open(Nav.SETTINGS));
        MenuItem logout = new MenuItem("Log out");
        logout.setOnAction(e -> logout());
        ContextMenu menu = new ContextMenu(name, new SeparatorMenuItem(), settings, logout);
        menu.setStyle("-fx-min-width: 220;");
        return menu;
    }

    static void showBelow(ContextMenu menu, Node anchor) {
        menu.show(anchor, Side.BOTTOM, 0, 6);
    }

    static HBox brand() {
        VBox name = new VBox(-2, Ui.label("ClassSync", "brand-text"),
                Ui.label("THAPAR INSTITUTE OF ENGINEERING & TECHNOLOGY", "brand-sub-title"));
        HBox brand = new HBox(10, Ui.logoMark(34), name);
        brand.setAlignment(Pos.CENTER_LEFT);
        return brand;
    }
}
