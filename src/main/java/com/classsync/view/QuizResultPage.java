package com.classsync.view;

import com.classsync.AppContext;
import com.classsync.model.AttemptMode;
import com.classsync.model.AttemptStatus;
import com.classsync.model.QuestionOption;
import com.classsync.model.Quiz;
import com.classsync.model.QuizAttempt;
import com.classsync.model.ReviewPolicy;
import com.classsync.model.User;
import com.classsync.service.Errors;
import com.classsync.service.QuizService.AttemptResult;
import com.classsync.service.QuizService.AttemptReview;
import com.classsync.service.QuizService.ReviewItem;
import com.classsync.service.QuizService.StudentQuiz;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Result of one attempt. Before release it only confirms submission; after release it shows the score
 * and an answer-by-answer review with explanations, filterable to incorrect answers for revision.
 */
public class QuizResultPage extends Page {

    private final long attemptId;

    public QuizResultPage(AppContext ctx, AppShell shell, long attemptId) {
        super(ctx, shell);
        this.attemptId = attemptId;
    }

    @Override
    public Nav nav() {
        return Nav.QUIZZES;
    }

    private record Data(AttemptResult result, AttemptReview review, StudentQuiz overview) {
    }

    @Override
    public Node build() {
        User u = user();
        StackPane root = new StackPane(Ui.loading("Loading result…"));
        load(() -> {
            AttemptResult r = ctx.quizzes().result(u, attemptId);
            AttemptReview review = r.released() ? ctx.quizzes().review(u, attemptId) : null;
            return new Data(r, review, ctx.quizzes().forStudent(u, r.quiz().id()));
        }, d -> root.getChildren().setAll(Ui.page(content(u, d))),
                e -> root.getChildren().setAll(Ui.errorState(Errors.userMessage(e), null)));
        return root;
    }

    private Node content(User u, Data d) {
        Quiz q = d.result().quiz();
        QuizAttempt a = d.result().attempt();
        Button back = Ui.button("Quizzes", Icons.CHEVRON_LEFT, "btn-ghost");
        back.setOnAction(e -> shell.open(Nav.QUIZZES));
        HBox actions = new HBox(8);
        if (d.overview().practiceAllowed()) {
            Button practice = Ui.button("Practice again", Icons.REVISION);
            practice.setOnAction(e -> QuizWidgets.confirmStart(ctx, shell, u, q, AttemptMode.PRACTICE));
            actions.getChildren().add(practice);
        }
        if (q.lectureId() != null) {
            Button lecture = Ui.button("Open lecture", Icons.BOOK_OPEN, "btn-ghost");
            lecture.setOnAction(e -> shell.open(new LectureDetailPage(ctx, shell, q.lectureId())));
            actions.getChildren().add(lecture);
        }
        VBox header = new VBox(8, back, Ui.row(6, Ui.badge(q.courseCode(), "primary"),
                        Ui.badge(a.mode() == AttemptMode.PRACTICE ? "Practice attempt " + a.attemptNo() : "Official attempt",
                                a.mode() == AttemptMode.PRACTICE ? "warning" : "neutral"),
                        a.status() == AttemptStatus.EXPIRED ? Ui.badge("Auto-submitted at the deadline", "info") : Ui.label("")),
                Ui.row(16, Ui.label(q.title(), "h1"), Ui.spacer(), actions));

        VBox body = new VBox(20, header);
        if (!d.result().released()) {
            String when = switch (q.reviewPolicy()) {
                case AFTER_SUBMIT -> "right away";
                case AFTER_CLOSE -> "after the quiz closes on " + q.closesAt().atZone(ctx.quizzes().zone()).format(Ui.DATE_TIME);
                case MANUAL -> "when your professor releases them";
            };
            body.getChildren().add(Ui.card(Ui.emptyState(Icons.CHECK, "Your answers were submitted",
                    "Submitted " + a.submittedAt().atZone(ctx.quizzes().zone()).format(Ui.DATE_TIME)
                            + ". Your score and the correct answers will be visible " + when + ".", null)));
            return body;
        }

        AttemptReview review = d.review();
        long correct = review.items().stream().filter(ReviewItem::correct).count();
        long unanswered = review.items().stream().filter(i -> i.chosenOptionId() == null).count();
        long wrong = review.items().size() - correct - unanswered;
        body.getChildren().add(new HBox(16,
                Ui.statCard(Icons.TROPHY, a.score() + " / " + a.maxScore(), "Score", Ui.percent(a.fraction()), null),
                Ui.statCard(Icons.CHECK, String.valueOf(correct), "Correct", null, "stat-green"),
                Ui.statCard(Icons.X, String.valueOf(wrong), "Incorrect", null, "stat-amber"),
                Ui.statCard(Icons.ALERT, String.valueOf(unanswered), "Unanswered", null, "stat-violet")));

        VBox list = new VBox(14);
        CheckBox onlyWrong = new CheckBox("Show only questions I got wrong");
        Runnable render = () -> {
            list.getChildren().clear();
            for (ReviewItem item : review.items()) {
                if (!onlyWrong.isSelected() || !item.correct()) {
                    list.getChildren().add(itemCard(item));
                }
            }
            if (list.getChildren().isEmpty()) {
                list.getChildren().add(Ui.emptyState(Icons.TROPHY, "Nothing to revise here", "You answered every question correctly.", null));
            }
        };
        onlyWrong.setOnAction(e -> render.run());
        onlyWrong.setSelected(correct < review.items().size());
        render.run();
        body.getChildren().addAll(Ui.row(12, Ui.label("Answer review", "h2"), Ui.spacer(), onlyWrong), list);
        if (q.reviewPolicy() == ReviewPolicy.AFTER_SUBMIT && a.mode() == AttemptMode.OFFICIAL) {
            body.getChildren().add(Ui.wrapping("Answers are shown right after submission for this quiz, as set by your professor.", "faint", "small"));
        }
        return body;
    }

    private Node itemCard(ReviewItem item) {
        var q = item.question();
        String verdict = item.chosenOptionId() == null ? "Unanswered" : item.correct() ? "Correct" : "Incorrect";
        VBox options = new VBox(8);
        char letter = 'A';
        for (QuestionOption o : q.options()) {
            boolean chosen = Long.valueOf(o.id()).equals(item.chosenOptionId());
            String note = o.correct() && chosen ? "Your answer · correct" : o.correct() ? "Correct answer" : chosen ? "Your answer" : "";
            Node mark = o.correct() ? Icons.of(Icons.CHECK, 16) : chosen ? Icons.of(Icons.X, 16) : Ui.label("  ");
            HBox row = new HBox(12, mark, Ui.label(letter + ".", "body-strong"), Ui.wrapping(o.text()), Ui.spacer(),
                    Ui.label(note, "small", "muted"));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("option-row");
            if (o.correct()) {
                row.getStyleClass().add("option-correct");
            } else if (chosen) {
                row.getStyleClass().add("option-wrong");
            }
            options.getChildren().add(row);
            letter++;
        }
        VBox card = Ui.card(
                Ui.row(8, Ui.label("QUESTION " + q.position(), "caption"),
                        q.topicName() == null ? Ui.label("") : Ui.badge(q.topicName(), "neutral"), Ui.spacer(),
                        Ui.badge(verdict + " · " + (item.correct() ? q.marks() : 0) + "/" + q.marks(),
                                item.correct() ? "success" : item.chosenOptionId() == null ? "neutral" : "danger")),
                Ui.wrapping(q.prompt(), "h3"), options);
        if (!q.explanation().isBlank()) {
            card.getChildren().add(new VBox(4, Ui.label("Explanation", "field-label"), Ui.wrapping(q.explanation(), "muted")));
        }
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }
}
