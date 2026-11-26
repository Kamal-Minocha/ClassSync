package com.classsync.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Factory methods for ClassSync's reusable UI building blocks: cards, stat cards, badges,
 * page headers, empty/loading/error states and form rows. All styling is in base.css.
 */
public final class Ui {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private Ui() {
    }

    // ---------- text ----------

    public static Label label(String text, String... styles) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styles);
        return l;
    }

    public static Label wrapping(String text, String... styles) {
        Label l = label(text, styles);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    // ---------- buttons ----------

    public static Button button(String text, String icon, String... styles) {
        Button b = new Button(text);
        if (icon != null) {
            b.setGraphic(Icons.of(icon, 16));
        }
        b.getStyleClass().addAll(styles);
        return b;
    }

    public static Button primary(String text, String icon) {
        return button(text, icon, "btn-primary");
    }

    public static Button iconButton(String icon, String tooltip) {
        Button b = new Button();
        b.setGraphic(Icons.of(icon, 18));
        b.getStyleClass().add("btn-icon");
        b.setTooltip(new Tooltip(tooltip));
        b.setAccessibleText(tooltip);
        return b;
    }

    // ---------- layout ----------

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static HBox row(double spacing, Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static VBox column(double spacing, Node... children) {
        return new VBox(spacing, children);
    }

    /** Scrollable page body with the standard padding. */
    public static ScrollPane page(Node content) {
        VBox wrap = new VBox(content);
        wrap.getStyleClass().add("page");
        ScrollPane sp = new ScrollPane(wrap);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("page-scroll");
        return sp;
    }

    /**
     * Page that fills the window without an outer scroll bar. Used for pages with a TabPane:
     * the header stays put and each tab scrolls its own content (see tabScroll).
     */
    public static VBox pageFixed(Node header, Node tabs) {
        VBox page = new VBox(16, header, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        page.getStyleClass().add("page");
        page.setPadding(new Insets(24, 28, 12, 28));
        return page;
    }

    /** Scrollable tab body. */
    public static ScrollPane tabScroll(Node content) {
        VBox wrap = new VBox(content);
        wrap.setPadding(new Insets(4, 4, 24, 0));
        ScrollPane sp = new ScrollPane(wrap);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("page-scroll");
        return sp;
    }

    public static HBox pageHeader(String title, String subtitle, Node... actions) {
        VBox text = new VBox(4, label(title, "h1"));
        if (subtitle != null && !subtitle.isBlank()) {
            text.getChildren().add(wrapping(subtitle, "muted"));
        }
        HBox header = new HBox(12, text, spacer());
        header.getChildren().addAll(actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));
        return header;
    }

    // ---------- photo backgrounds ----------

    /**
     * A region painted with a bundled photo, scaled to cover its area (like CSS background-size: cover).
     * It never asks for space, so it simply fills whatever its parent gives it.
     */
    public static Region photoLayer(String image) {
        Region photo = new Region();
        photo.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundImage(
                Images.get(image), javafx.scene.layout.BackgroundRepeat.NO_REPEAT, javafx.scene.layout.BackgroundRepeat.NO_REPEAT,
                javafx.scene.layout.BackgroundPosition.CENTER,
                new javafx.scene.layout.BackgroundSize(100, 100, true, true, false, true))));
        photo.setMinSize(0, 0);
        photo.setPrefSize(0, 0);
        photo.setMouseTransparent(true);
        return photo;
    }

    /** A colour/gradient layer defined by a CSS class (e.g. hero-overlay), used over a photo for legible text. */
    public static Region overlay(String styleClass) {
        Region overlay = new Region();
        overlay.getStyleClass().add(styleClass);
        overlay.setMinSize(0, 0);
        overlay.setPrefSize(0, 0);
        overlay.setMouseTransparent(true);
        return overlay;
    }

    /**
     * Photo banner: photo, gradient overlay, then content, clipped to rounded corners (JavaFX does not clip
     * background images to -fx-background-radius by itself). topOnly rounds just the top corners (card headers).
     */
    public static StackPane photoBanner(String image, String overlayClass, double radius, boolean topOnly, Node content) {
        StackPane pane = new StackPane(photoLayer(image), overlay(overlayClass), content);
        pane.getStyleClass().add("hero");
        if (radius > 0) {
            javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
            clip.setArcWidth(radius * 2);
            clip.setArcHeight(radius * 2);
            clip.widthProperty().bind(pane.widthProperty());
            clip.heightProperty().bind(topOnly ? pane.heightProperty().add(radius) : pane.heightProperty());
            pane.setClip(clip);
        }
        return pane;
    }

    /** The Thapar Institute logo mark as a rounded square of the given size. */
    public static Node logoMark(double size) {
        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(Images.get(Images.LOGO_MARK));
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setSmooth(true);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(size, size);
        clip.setArcWidth(size * 0.45);
        clip.setArcHeight(size * 0.45);
        view.setClip(clip);
        return view;
    }

    // ---------- cards ----------

    public static VBox card(Node... children) {
        VBox card = new VBox(12, children);
        card.getStyleClass().add("card");
        return card;
    }

    public static VBox card(String title, Node... children) {
        VBox card = card();
        card.getChildren().add(label(title, "card-title"));
        card.getChildren().addAll(children);
        return card;
    }

    /** Makes a card keyboard-focusable and clickable (Enter/Space activate it). */
    public static <T extends Region> T clickable(T node, Runnable action) {
        node.getStyleClass().add("card-clickable");
        node.setFocusTraversable(true);
        node.setOnMouseClicked(e -> action.run());
        node.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                action.run();
            }
        });
        return node;
    }

    /**
     * Summary card: icon, big value, label, optional sub-text.
     * tone: "stat-teal", "stat-violet", "stat-amber", "stat-green" or null for indigo.
     */
    public static VBox statCard(String icon, String value, String label, String sub, String tone) {
        StackPane iconBg = new StackPane(Icons.of(icon, 20));
        iconBg.getStyleClass().add("stat-icon-bg");
        iconBg.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        VBox card = card(iconBg, label(value, "stat-value"), label(label, "stat-label"));
        card.setSpacing(6);
        if (sub != null) {
            card.getChildren().add(wrapping(sub, "small", "faint"));
        }
        if (tone != null) {
            card.getStyleClass().add(tone);
        }
        card.setMinWidth(180);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    // ---------- badges and progress ----------

    /** tone: primary, success, warning, danger, info, neutral */
    public static Label badge(String text, String tone) {
        Label l = label(text, "badge", "badge-" + tone);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    public static ProgressBar progress(double value, String extraStyle) {
        ProgressBar bar = new ProgressBar(value);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(10);
        if (extraStyle != null) {
            bar.getStyleClass().add(extraStyle);
        }
        return bar;
    }

    // ---------- states ----------

    public static VBox emptyState(String icon, String title, String message, Node action) {
        StackPane bg = new StackPane(Icons.of(icon, 26));
        bg.getStyleClass().add("empty-icon-bg");
        bg.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        VBox box = new VBox(bg, label(title, "h3"));
        if (message != null) {
            Label m = wrapping(message, "muted");
            m.setMaxWidth(420);
            m.setAlignment(Pos.CENTER);
            m.setStyle("-fx-text-alignment: center;");
            box.getChildren().add(m);
        }
        if (action != null) {
            box.getChildren().add(action);
        }
        box.getStyleClass().add("empty-state");
        return box;
    }

    public static VBox loading(String message) {
        ProgressIndicator pi = new ProgressIndicator();
        pi.setPrefSize(36, 36);
        VBox box = new VBox(12, pi, label(message, "muted"));
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        return box;
    }

    public static VBox errorState(String message, Runnable retry) {
        Button again = retry == null ? null : button("Try again", Icons.REFRESH);
        if (again != null) {
            again.setOnAction(e -> retry.run());
        }
        return emptyState(Icons.ALERT, "Could not load this section", message, again);
    }

    // ---------- forms ----------

    /** Label above a control with an optional hint below it. */
    public static VBox formRow(String labelText, Node control, String hint) {
        Label l = label(labelText, "field-label");
        l.setLabelFor(control);
        VBox row = new VBox(5, l, control);
        if (hint != null) {
            row.getChildren().add(wrapping(hint, "field-hint"));
        }
        if (control instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        return row;
    }

    // ---------- dates ----------

    /** Calendar-style chip: day number over month abbreviation. */
    public static VBox dateChip(ZonedDateTime when) {
        VBox chip = new VBox(-2, label(String.valueOf(when.getDayOfMonth()), "date-chip-day"),
                label(when.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)).toUpperCase(), "date-chip-month"));
        chip.getStyleClass().add("date-chip");
        chip.setAlignment(Pos.CENTER);
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    public static String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }
}
