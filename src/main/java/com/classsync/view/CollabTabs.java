package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.dao.FeedbackDao;
import com.classsync.model.Discussion;
import com.classsync.model.Lecture;
import com.classsync.model.Material;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.DiscussionService;
import com.classsync.service.Errors;
import com.classsync.service.FeedbackService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/** Lecture tabs for Phase 8: Feedback, Notes and Doubts. */
final class CollabTabs {

    private CollabTabs() {
    }

    // ---------------------------------------------------------------- feedback

    static Node feedback(AppContext ctx, AppShell shell, User u, Lecture lecture) {
        VBox box = new VBox(16, Ui.loading("Loading feedback…"));
        if (u.role() == Role.PROFESSOR) {
            ctx.background().run(() -> ctx.feedback().report(u, lecture.id()), r -> box.getChildren().setAll(professor(ctx, shell, u, r)),
                    e -> box.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        } else {
            ctx.background().run(() -> ctx.feedback().mine(u, lecture.id()), m -> box.getChildren().setAll(student(ctx, shell, m)),
                    e -> box.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        }
        return box;
    }

    private static List<Node> student(AppContext ctx, AppShell shell, FeedbackService.MyFeedback m) {
        FeedbackDao.Own own = m.own();
        if (own == null) {
            if (!m.eligible()) {
                return List.of(Ui.emptyState(Icons.STAR, "Feedback is not open", m.reason(), null));
            }
            Button give = Ui.primary("Give feedback", Icons.STAR);
            give.setOnAction(e -> CollabForms.feedback(ctx, shell, m.lecture(), null, shell::refresh));
            return List.of(Ui.emptyState(Icons.STAR, "How was this lecture?",
                    "Four quick ratings and optional comments" + (m.lecture().feedbackAnonymous() ? " — anonymous." : "."), give));
        }
        var r = own.ratings();
        VBox card = Ui.card("Your feedback",
                Ui.row(16, Ui.label("Understanding " + r.understanding() + "/5"), Ui.label("Clarity " + r.clarity() + "/5"),
                        Ui.label("Pace " + r.pace() + "/5"), Ui.label("Usefulness " + r.usefulness() + "/5")),
                Ui.label(own.anonymous() ? "Submitted anonymously" : "Submitted with your name", "small", "muted"));
        if (m.eligible()) {
            Button edit = Ui.button("Edit", Icons.NOTE);
            edit.setOnAction(e -> CollabForms.feedback(ctx, shell, m.lecture(), own, shell::refresh));
            card.getChildren().add(edit);
        } else {
            card.getChildren().add(Ui.label("Feedback is closed, so your answer is final.", "small", "faint"));
        }
        return List.of(card);
    }

    private static List<Node> professor(AppContext ctx, AppShell shell, User u, FeedbackService.Report r) {
        Lecture l = r.lecture();
        CheckBox open = new CheckBox("Collect feedback for this lecture");
        open.setSelected(l.feedbackOpen());
        CheckBox anonymous = new CheckBox("Anonymous (names hidden in this report)");
        anonymous.setSelected(l.feedbackAnonymous());
        Button save = Ui.button("Save settings", Icons.CHECK);
        save.setOnAction(e -> ctx.background().run(() -> {
            ctx.lectures().setFeedbackSettings(u, l.id(), open.isSelected(), anonymous.isSelected());
            return null;
        }, x -> {
            shell.toast().success("Feedback settings saved.");
            shell.refresh();
        }, shell::showError));
        VBox settings = Ui.card("Settings", Ui.row(24, open, anonymous, Ui.spacer(), save),
                Ui.wrapping("Feedback already given anonymously stays anonymous even if you switch anonymity off later.", "small", "faint"));

        var s = r.summary();
        HBox stats = new HBox(16,
                Ui.statCard(Icons.USERS, s.count() + " / " + r.enrolled(), "Responses",
                        r.participation().isPresent() ? Ui.percent(r.participation().getAsDouble()) + " participation" : "No students enrolled", null),
                Ui.statCard(Icons.STAR, avg(s.overall()), "Overall", "Average of the four ratings", "stat-amber"),
                Ui.statCard(Icons.BOOK_OPEN, avg(s.understanding()), "Understanding", "Clarity " + avg(s.clarity()), "stat-teal"),
                Ui.statCard(Icons.CLOCK, avg(s.pace()), "Pace", "Usefulness " + avg(s.usefulness()), "stat-violet"));

        VBox comments = new VBox(8);
        if (r.comments().isEmpty()) {
            comments.getChildren().add(Ui.label("No written comments yet.", "faint"));
        }
        for (FeedbackDao.Comment c : r.comments()) {
            VBox item = new VBox(4, Ui.label(c.author() == null ? "Anonymous student" : c.author(), "body-strong"));
            if (!c.difficult().isBlank()) {
                item.getChildren().add(Ui.wrapping("Difficult: " + c.difficult()));
            }
            if (!c.suggestions().isBlank()) {
                item.getChildren().add(Ui.wrapping("Suggestion: " + c.suggestions(), "muted"));
            }
            item.getStyleClass().add("list-row");
            comments.getChildren().add(item);
        }
        return List.of(settings, stats, Ui.card("Difficult concepts and suggestions", comments));
    }

    private static String avg(Double v) {
        return v == null ? "—" : "%.1f".formatted(v);
    }

    // ---------------------------------------------------------------- notes

    static Node notes(AppContext ctx, AppShell shell, User u, Lecture lecture) {
        VBox list = new VBox(10, Ui.loading("Loading notes…"));
        VBox box = new VBox(12);
        if (u.role().isLearner()) {
            Button submit = Ui.primary("Submit my notes for this lecture", Icons.UPLOAD);
            submit.setOnAction(e -> ctx.background().run(() -> ctx.courses().get(u, lecture.courseId()),
                    c -> CollabForms.submitNote(ctx, shell, List.of(c), c.id(), lecture.id(), shell::refresh), shell::showError));
            box.getChildren().add(submit);
        }
        record Data(List<Material> notes, java.util.Set<Long> votes) { }
        ctx.background().run(() -> new Data(ctx.notes().approved(u, lecture.courseId(), lecture.id(), null, "votes"),
                ctx.notes().myVotes(u)), d -> {
            list.getChildren().clear();
            if (d.notes().isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.NOTE, "No approved notes for this lecture yet", null, null));
            }
            for (Material m : d.notes()) {
                list.getChildren().add(NotesPage.noteRow(ctx, shell, u, m, d.votes().contains(m.id())));
            }
        }, e -> list.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        box.getChildren().add(list);
        return box;
    }

    // ---------------------------------------------------------------- doubts

    static Node doubts(AppContext ctx, AppShell shell, User u, Lecture lecture) {
        VBox list = new VBox(10, Ui.loading("Loading doubts…"));
        Button ask = Ui.primary("Ask a doubt about this lecture", Icons.PLUS);
        ask.setOnAction(e -> ctx.background().run(() -> ctx.courses().get(u, lecture.courseId()),
                c -> CollabForms.askDoubt(ctx, shell, List.of(c), c.id(), lecture.id(),
                        id -> shell.open(new DiscussionThreadPage(ctx, shell, id))), shell::showError));
        ctx.background().run(() -> ctx.discussions().list(u, new DiscussionService.Query(lecture.courseId(), lecture.id(),
                null, false, false, 100)), items -> {
            list.getChildren().clear();
            if (items.isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.CHAT, "No doubts about this lecture", null, null));
            }
            for (Discussion d : items) {
                list.getChildren().add(DiscussionsPage.row(ctx, shell, d));
            }
        }, e -> list.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        return new VBox(12, ask, list);
    }
}
