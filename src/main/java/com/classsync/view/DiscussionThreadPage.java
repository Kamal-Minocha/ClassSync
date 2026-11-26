package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.Discussion;
import com.classsync.model.DiscussionReply;
import com.classsync.model.User;
import com.classsync.service.DiscussionService;
import com.classsync.service.Errors;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** One doubt with its replies, reply box, upvotes, resolve and moderation actions. */
public class DiscussionThreadPage extends Page {

    private final long discussionId;

    public DiscussionThreadPage(AppContext ctx, AppShell shell, long discussionId) {
        super(ctx, shell);
        this.discussionId = discussionId;
    }

    @Override
    public Nav nav() {
        return Nav.DISCUSSIONS;
    }

    @Override
    public Node build() {
        User u = user();
        StackPane root = new StackPane(Ui.loading("Loading doubt…"));
        load(() -> ctx.discussions().thread(u, discussionId), t -> root.getChildren().setAll(Ui.page(content(u, t))),
                e -> root.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        return root;
    }

    private interface Action {
        void run() throws Exception;
    }

    private void act(Action a, String done) {
        ctx.background().run(() -> {
            a.run();
            return null;
        }, r -> {
            if (done != null) {
                shell.toast().success(done);
            }
            shell.refresh();
        }, shell::showError);
    }

    private Node content(User u, DiscussionService.Thread t) {
        Discussion d = t.discussion();
        Button back = Ui.button("Discussions", Icons.CHEVRON_LEFT, "btn-ghost");
        back.setOnAction(e -> shell.open(Nav.DISCUSSIONS));
        HBox actions = new HBox(8);
        if (u.role().isLearner()) {
            actions.getChildren().add(StudyWidgets.bookmark(ctx, shell, com.classsync.dao.StudyDao.Target.DISCUSSION, d.id(), null));
        }
        if (t.canResolve()) {
            Button resolve = d.resolved() ? Ui.button("Reopen", Icons.REFRESH) : Ui.primary("Mark resolved", Icons.CHECK);
            resolve.setOnAction(e -> act(() -> ctx.discussions().setResolved(u, discussionId, !d.resolved()),
                    d.resolved() ? "Doubt reopened." : "Marked resolved."));
            actions.getChildren().add(resolve);
        }
        if (t.canModerate()) {
            Button hide = Ui.button(d.hidden() ? "Unhide" : "Hide doubt", Icons.EYE_OFF, "btn-ghost");
            hide.setOnAction(e -> act(() -> ctx.discussions().setDoubtHidden(u, discussionId, !d.hidden()), null));
            actions.getChildren().add(hide);
        }
        HBox tags = Ui.row(6, Ui.badge(d.courseCode(), "primary"),
                d.resolved() ? Ui.badge("Resolved by " + d.resolvedByName(), "success") : Ui.badge("Open", "warning"));
        if (d.lectureNo() != null) {
            Button lecture = Ui.button("Lecture " + d.lectureNo(), Icons.BOOK_OPEN, "btn-ghost");
            lecture.setOnAction(e -> shell.open(new LectureDetailPage(ctx, shell, d.lectureId())));
            tags.getChildren().add(lecture);
        }
        if (d.topicName() != null) {
            tags.getChildren().add(Ui.badge(d.topicName(), "info"));
        }
        if (d.hidden()) {
            tags.getChildren().add(Ui.badge("Hidden from students", "danger"));
        }
        VBox head = new VBox(8, tags, Ui.wrapping(d.title(), "h1"),
                Ui.label("Asked by " + d.authorName() + " · " + d.createdAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "muted"));
        HBox.setHgrow(head, Priority.ALWAYS);
        VBox body = new VBox(16, back, new HBox(16, head, actions));
        if (!d.body().isBlank()) {
            body.getChildren().add(Ui.card(Ui.wrapping(d.body())));
        }
        body.getChildren().add(Ui.label(t.replies().isEmpty() ? "No replies yet" : QuizWidgets.plural(t.replies().size(), "reply", "replies"), "h2"));
        for (DiscussionReply r : t.replies()) {
            body.getChildren().add(replyCard(u, r, t.canModerate()));
        }
        if (!d.hidden()) {
            TextArea reply = Forms.area("", "Write a reply — explain, don't just give the answer", 4);
            Button send = Ui.primary("Post reply", Icons.CHAT);
            send.setOnAction(e -> {
                if (reply.getText().trim().length() < 2) {
                    shell.toast().error("Write a reply first.");
                    return;
                }
                String text = reply.getText();
                act(() -> ctx.discussions().reply(u, discussionId, text), "Reply posted.");
            });
            body.getChildren().add(Ui.card("Your reply", reply, Ui.row(8, Ui.spacer(), send)));
        }
        return body;
    }

    private Node replyCard(User u, DiscussionReply r, boolean moderator) {
        HBox top = Ui.row(8, Ui.label(r.authorName(), "body-strong"));
        if (r.professorAnswer()) {
            top.getChildren().add(Ui.badge("Professor answer", "info"));
        }
        if (r.hidden()) {
            top.getChildren().add(Ui.badge("Hidden", "danger"));
        }
        top.getChildren().addAll(Ui.label(r.createdAt().atZone(ctx.lectures().zone()).format(Ui.DATE_TIME), "small", "faint"), Ui.spacer());
        Button vote = Ui.button(String.valueOf(r.votes()), Icons.THUMB_UP, r.votedByMe() ? "btn-primary" : "btn-ghost");
        vote.setAccessibleText((r.votedByMe() ? "Remove upvote from " : "Upvote ") + "reply by " + r.authorName());
        vote.setDisable(r.authorId() == u.id());
        vote.setOnAction(e -> act(() -> ctx.discussions().voteReply(u, r.id(), !r.votedByMe()), null));
        top.getChildren().add(vote);
        if (moderator) {
            Button hide = Ui.iconButton(r.hidden() ? Icons.EYE : Icons.EYE_OFF, r.hidden() ? "Unhide reply" : "Hide reply");
            hide.setOnAction(e -> act(() -> ctx.discussions().setReplyHidden(u, r.id(), !r.hidden()), null));
            top.getChildren().add(hide);
        }
        VBox card = Ui.card(top, Ui.wrapping(r.body()));
        if (r.professorAnswer()) {
            card.getStyleClass().add("stat-teal");
        }
        return card;
    }
}
