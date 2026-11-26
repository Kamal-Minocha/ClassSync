package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.Material;
import com.classsync.model.User;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Domain-specific UI pieces reused by several pages. */
public final class Widgets {

    private Widgets() {
    }

    public static Label statusBadge(LectureStatus status) {
        return Ui.badge(status.label(), switch (status) {
            case DRAFT -> "neutral";
            case PUBLISHED -> "primary";
            case COMPLETED -> "success";
            case CANCELLED -> "danger";
        });
    }

    /** Lecture summary row: date chip, title, course + time + room, status badge. Clickable. */
    public static HBox lectureRow(Lecture l, ZoneId zone, Runnable onOpen) {
        ZonedDateTime start = l.startsAt().atZone(zone);
        ZonedDateTime end = l.endsAt().atZone(zone);
        Label title = Ui.label(l.title(), "body-strong");
        title.setWrapText(true);
        String where = l.room().isBlank() ? "" : "  ·  " + l.room();
        VBox text = new VBox(3, title,
                Ui.label(l.courseCode() + "  ·  " + start.format(Ui.TIME) + " – " + end.format(Ui.TIME) + where, "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(14, Ui.dateChip(start), text, statusBadge(l.status()));
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().addAll("list-row", "list-row-clickable");
        row.setFocusTraversable(true);
        row.setAccessibleText(l.title() + ", " + start.format(Ui.DATE_TIME));
        row.setOnMouseClicked(e -> onOpen.run());
        row.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                onOpen.run();
            }
        });
        return row;
    }

    /** "Open" and "Download" buttons for a material, both permission-checked by the service. */
    public static HBox fileActions(AppContext ctx, AppShell shell, Material m) {
        Button open = Ui.button("Open", Icons.EYE, "btn-ghost");
        open.setOnAction(e -> openFile(ctx, shell, m));
        Button download = Ui.button("Download", Icons.DOWNLOAD, "btn-ghost");
        download.setOnAction(e -> downloadFile(ctx, shell, m));
        return new HBox(4, open, download);
    }

    public static void openFile(AppContext ctx, AppShell shell, Material m) {
        User u = ctx.session().requireUser();
        ctx.background().run(() -> {
            Path p = ctx.materials().pathForOpening(u, m.id());
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new com.classsync.service.AppException("Opening files is not supported here. Use Download instead.");
            }
            Desktop.getDesktop().open(p.toFile());
            return null;
        }, ok -> { }, shell::showError);
    }

    public static void downloadFile(AppContext ctx, AppShell shell, Material m) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save " + m.originalFilename());
        chooser.setInitialFileName(m.originalFilename());
        File target = chooser.showSaveDialog(shell.window());
        if (target == null) {
            return;
        }
        User u = ctx.session().requireUser();
        ctx.background().run(() -> {
            ctx.materials().download(u, m.id(), target.toPath());
            return null;
        }, ok -> shell.toast().success("Saved " + target.getName()), shell::showError);
    }

    /** Small file-type tile used in material lists. */
    public static Node fileTypeChip(String type) {
        String tone = switch (type) {
            case "pdf" -> "danger";
            case "ppt", "pptx" -> "warning";
            case "doc", "docx" -> "info";
            default -> "success";
        };
        Label l = Ui.badge(type.toUpperCase(), tone);
        l.setMinWidth(46);
        l.setAlignment(javafx.geometry.Pos.CENTER);
        return l;
    }

    /** Compact material row used on dashboards and lecture pages. */
    public static HBox materialRow(AppContext ctx, AppShell shell, Material m) {
        VBox text = new VBox(2, Ui.label(m.title(), "body-strong"),
                Ui.label(m.courseCode() + (m.lectureNo() == null ? "" : " · L" + m.lectureNo())
                        + " · " + m.sizeText() + " · " + m.uploaderName(), "small", "muted"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, fileTypeChip(m.fileType()), text);
        if (!m.published()) {
            row.getChildren().add(Ui.badge("Unpublished", "warning"));
        }
        row.getChildren().add(fileActions(ctx, shell, m));
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("list-row");
        return row;
    }
}
