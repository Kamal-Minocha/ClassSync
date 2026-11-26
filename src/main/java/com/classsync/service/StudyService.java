package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.StudyDao;
import com.classsync.dao.StudyDao.Target;
import com.classsync.model.Discussion;
import com.classsync.model.Lecture;
import com.classsync.model.Material;
import com.classsync.model.User;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Bookmarks and personal study tasks (Module 15). Everything is private to its owner: every query is
 * filtered by the signed-in user's id. A bookmark is only created for something the user can see now,
 * and when listing, items the user can no longer see (unenrolled, archived, unpublished, hidden) are
 * silently left out — a bookmark never becomes a back door.
 */
public class StudyService {

    /** A bookmark resolved for display. */
    public record Bookmark(long id, Target target, long targetId, String title, String subtitle, Material material,
                           Instant createdAt) {
    }

    private final Database db;
    private final AccessPolicy access;
    private final MaterialService materials;
    private final LectureService lectures;
    private final DiscussionService discussions;
    private final StudyDao dao = new StudyDao();

    public StudyService(Database db, AccessPolicy access, MaterialService materials, LectureService lectures,
                        DiscussionService discussions) {
        this.db = db;
        this.access = access;
        this.materials = materials;
        this.lectures = lectures;
        this.discussions = discussions;
    }

    // ---------------------------------------------------------------- bookmarks

    public void bookmark(User u, Target target, long targetId, boolean on) throws SQLException {
        Tx.inTransaction(db, con -> {
            if (on) {
                requireVisible(con, u, target, targetId);
                dao.addBookmark(con, u.id(), target, targetId);
            } else {
                dao.removeBookmark(con, u.id(), target, targetId);
            }
            return null;
        });
    }

    public boolean isBookmarked(User u, Target target, long targetId) throws SQLException {
        try (Connection con = db.open()) {
            return dao.isBookmarked(con, u.id(), target, targetId);
        }
    }

    public List<Bookmark> bookmarks(User u) throws SQLException {
        List<Bookmark> out = new ArrayList<>();
        try (Connection con = db.open()) {
            for (StudyDao.BookmarkRow r : dao.bookmarks(con, u.id())) {
                try {
                    out.add(resolve(con, u, r));
                } catch (NotFoundException | AppException.AccessDeniedException hiddenNow) {
                    // no longer visible to this user: leave it out (the row stays, in case access returns)
                }
            }
        }
        return out;
    }

    private Bookmark resolve(Connection con, User u, StudyDao.BookmarkRow r) throws SQLException {
        return switch (r.target()) {
            case MATERIAL -> {
                Material m = materials.visible(con, u, r.targetId());
                if (m.archivedAt() != null) {
                    throw new NotFoundException("archived");
                }
                yield new Bookmark(r.id(), r.target(), r.targetId(), m.title(),
                        m.courseCode() + " · " + m.kind().label() + (m.lectureNo() == null ? "" : " · L" + m.lectureNo()),
                        m, r.createdAt());
            }
            case LECTURE -> {
                Lecture l = lectures.visibleLecture(con, u, r.targetId());
                yield new Bookmark(r.id(), r.target(), r.targetId(), l.title(), l.courseCode() + " · Lecture", null, r.createdAt());
            }
            case DISCUSSION -> {
                Discussion d = discussions.thread(u, r.targetId()).discussion();
                yield new Bookmark(r.id(), r.target(), r.targetId(), d.title(), d.courseCode() + " · Doubt"
                        + (d.resolved() ? " (resolved)" : ""), null, r.createdAt());
            }
        };
    }

    private void requireVisible(Connection con, User u, Target target, long id) throws SQLException {
        switch (target) {
            case MATERIAL -> materials.visible(con, u, id);
            case LECTURE -> lectures.visibleLecture(con, u, id);
            case DISCUSSION -> discussions.thread(u, id);
        }
    }

    // ---------------------------------------------------------------- tasks

    public long addTask(User u, String title, LocalDate due, Long courseId) throws SQLException {
        String t = Validation.text(title, "Task", 2, 200);
        try (Connection con = db.open()) {
            if (courseId != null) {
                access.requireMember(con, u, courseId);
            }
            return dao.addTask(con, u.id(), courseId, t, due);
        }
    }

    public void setTaskDone(User u, long taskId, boolean done) throws SQLException {
        try (Connection con = db.open()) {
            if (!dao.setTaskDone(con, u.id(), taskId, done)) {
                throw new NotFoundException("Task not found.");
            }
        }
    }

    public void deleteTask(User u, long taskId) throws SQLException {
        try (Connection con = db.open()) {
            if (!dao.deleteTask(con, u.id(), taskId)) {
                throw new NotFoundException("Task not found.");
            }
        }
    }

    public List<StudyDao.TaskRow> tasks(User u) throws SQLException {
        try (Connection con = db.open()) {
            return dao.tasks(con, u.id());
        }
    }
}
