package com.classsync.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Teaching plans (professor), bookmarks and study tasks (private per user).
 * Every bookmark/task statement filters by user_id, so one user can never read or change another's items.
 */
public class StudyDao {

    public enum Target { MATERIAL, LECTURE, DISCUSSION }

    public record BookmarkRow(long id, Target target, long targetId, Instant createdAt) {
    }

    public record TaskRow(long id, Long courseId, String courseCode, String title, LocalDate dueDate, Instant doneAt,
                          Instant createdAt) {
    }

    public record PlanEntry(long topicId, LocalDate plannedDate, int plannedHours, String notes) {
    }

    // ------------------------------------------------------------------ teaching plans

    public Map<Long, PlanEntry> plans(Connection con, long courseId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT p.* FROM teaching_plans p JOIN topics t ON t.id = p.topic_id WHERE t.course_id = ?""")) {
            ps.setLong(1, courseId);
            Map<Long, PlanEntry> map = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getLong("topic_id"), new PlanEntry(rs.getLong("topic_id"),
                            rs.getObject("planned_date", LocalDate.class), rs.getInt("planned_hours"), rs.getString("notes")));
                }
            }
            return map;
        }
    }

    public void upsertPlan(Connection con, long topicId, LocalDate date, int hours, String notes) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO teaching_plans (topic_id, planned_date, planned_hours, notes) VALUES (?, ?, ?, ?)
                ON CONFLICT (topic_id) DO UPDATE SET planned_date = EXCLUDED.planned_date,
                    planned_hours = EXCLUDED.planned_hours, notes = EXCLUDED.notes, updated_at = now()""")) {
            ps.setLong(1, topicId);
            ps.setObject(2, date);
            ps.setInt(3, hours);
            ps.setString(4, notes);
            ps.executeUpdate();
        }
    }

    /** Rewrites topic order (and unit) for a course: topicIds in their new order. */
    public void reorder(Connection con, List<Long> topicIds) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE topics SET sequence = ? WHERE id = ?")) {
            for (int i = 0; i < topicIds.size(); i++) {
                ps.setInt(1, i + 1);
                ps.setLong(2, topicIds.get(i));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void setTopicUnit(Connection con, long topicId, int unit) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE topics SET unit_no = ? WHERE id = ?")) {
            ps.setInt(1, unit);
            ps.setLong(2, topicId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ bookmarks

    /** Adds a bookmark; false if it already existed (partial unique indexes). */
    public boolean addBookmark(Connection con, long userId, Target target, long targetId) throws SQLException {
        String column = column(target);
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO bookmarks (user_id, " + column + ") VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setLong(1, userId);
            ps.setLong(2, targetId);
            return ps.executeUpdate() == 1;
        }
    }

    public void removeBookmark(Connection con, long userId, Target target, long targetId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM bookmarks WHERE user_id = ? AND " + column(target) + " = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, targetId);
            ps.executeUpdate();
        }
    }

    public boolean isBookmarked(Connection con, long userId, Target target, long targetId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM bookmarks WHERE user_id = ? AND " + column(target) + " = ?)")) {
            ps.setLong(1, userId);
            ps.setLong(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    public List<BookmarkRow> bookmarks(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM bookmarks WHERE user_id = ? ORDER BY created_at DESC")) {
            ps.setLong(1, userId);
            List<BookmarkRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Target t;
                    long id;
                    long m = rs.getLong("material_id");
                    if (!rs.wasNull()) {
                        t = Target.MATERIAL;
                        id = m;
                    } else {
                        long l = rs.getLong("lecture_id");
                        if (!rs.wasNull()) {
                            t = Target.LECTURE;
                            id = l;
                        } else {
                            t = Target.DISCUSSION;
                            id = rs.getLong("discussion_id");
                        }
                    }
                    list.add(new BookmarkRow(rs.getLong("id"), t, id, rs.getObject("created_at", OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }

    /** Column chosen from the enum, never from user text. */
    private static String column(Target t) {
        return switch (t) {
            case MATERIAL -> "material_id";
            case LECTURE -> "lecture_id";
            case DISCUSSION -> "discussion_id";
        };
    }

    // ------------------------------------------------------------------ tasks

    public long addTask(Connection con, long userId, Long courseId, String title, LocalDate due) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO study_tasks (user_id, course_id, title, due_date) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            MaterialDao.setNullableLong(ps, 2, courseId);
            ps.setString(3, title);
            ps.setObject(4, due);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    /** Returns false when the task does not belong to the user (nothing changes). */
    public boolean setTaskDone(Connection con, long userId, long taskId, boolean done) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE study_tasks SET done_at = " + (done ? "coalesce(done_at, now())" : "NULL") + " WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, taskId);
            ps.setLong(2, userId);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean deleteTask(Connection con, long userId, long taskId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM study_tasks WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, taskId);
            ps.setLong(2, userId);
            return ps.executeUpdate() == 1;
        }
    }

    public List<TaskRow> tasks(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT s.*, c.code FROM study_tasks s LEFT JOIN courses c ON c.id = s.course_id
                WHERE s.user_id = ?
                ORDER BY (s.done_at IS NOT NULL), s.due_date NULLS LAST, s.created_at""")) {
            ps.setLong(1, userId);
            List<TaskRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long c = rs.getLong("course_id");
                    Long courseId = rs.wasNull() ? null : c;
                    OffsetDateTime done = rs.getObject("done_at", OffsetDateTime.class);
                    list.add(new TaskRow(rs.getLong("id"), courseId, rs.getString("code"), rs.getString("title"),
                            rs.getObject("due_date", LocalDate.class), done == null ? null : done.toInstant(),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }
}
