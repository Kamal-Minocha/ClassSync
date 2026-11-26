package com.classsync.dao;

import com.classsync.model.Notification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * In-app notifications. Every insert uses ON CONFLICT (user_id, dedupe_key) DO NOTHING, so repeating an
 * action (retry, double click, re-run) can never notify the same person about the same event twice.
 * Callers pass the caller's open transaction so the notification commits or rolls back with the action.
 */
public class NotificationDao {

    /** What to notify about; dedupeKey identifies the event (e.g. "announcement:42"). */
    public record Message(String kind, String title, String body, String linkType, Long linkId, String dedupeKey) {
    }

    /** Notifies the given users. Returns how many new notifications were created. */
    public int notifyUsers(Connection con, List<Long> userIds, Message m) throws SQLException {
        if (userIds.isEmpty()) {
            return 0;
        }
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO notifications (user_id, kind, title, body, link_type, link_id, dedupe_key)
                SELECT u, ?, ?, ?, ?, ?, ? FROM unnest(?::bigint[]) AS u
                ON CONFLICT (user_id, dedupe_key) DO NOTHING""")) {
            bind(ps, m);
            ps.setArray(7, con.createArrayOf("bigint", userIds.toArray()));
            return ps.executeUpdate();
        }
    }

    /** Notifies every student/CR currently enrolled in the course, except `exceptUserId` (the actor). */
    public int notifyCourseLearners(Connection con, long courseId, long exceptUserId, Message m) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO notifications (user_id, kind, title, body, link_type, link_id, dedupe_key)
                SELECT e.student_id, ?, ?, ?, ?, ?, ? FROM enrollments e
                WHERE e.course_id = ? AND e.student_id <> ?
                ON CONFLICT (user_id, dedupe_key) DO NOTHING""")) {
            bind(ps, m);
            ps.setLong(7, courseId);
            ps.setLong(8, exceptUserId);
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, Message m) throws SQLException {
        ps.setString(1, m.kind());
        ps.setString(2, truncate(m.title(), 200));
        ps.setString(3, truncate(m.body() == null ? "" : m.body(), 1000));
        ps.setString(4, m.linkType());
        MaterialDao.setNullableLong(ps, 5, m.linkId());
        ps.setString(6, m.dedupeKey());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public List<Notification> list(Connection con, long userId, boolean unreadOnly, int limit) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT * FROM notifications
                WHERE user_id = ? AND (NOT ? OR read_at IS NULL)
                ORDER BY created_at DESC, id DESC LIMIT ?""")) {
            ps.setLong(1, userId);
            ps.setBoolean(2, unreadOnly);
            ps.setInt(3, limit);
            List<Notification> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long link = rs.getLong("link_id");
                    Long linkId = rs.wasNull() ? null : link;
                    OffsetDateTime read = rs.getObject("read_at", OffsetDateTime.class);
                    list.add(new Notification(rs.getLong("id"), rs.getString("kind"), rs.getString("title"),
                            rs.getString("body"), rs.getString("link_type"), linkId,
                            read == null ? null : read.toInstant(), rs.getObject("created_at", OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }

    public int unreadCount(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Marks one of the user's own notifications read (the user_id condition stops reading anyone else's). */
    public void markRead(Connection con, long userId, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE notifications SET read_at = now() WHERE id = ? AND user_id = ? AND read_at IS NULL")) {
            ps.setLong(1, id);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public int markAllRead(Connection con, long userId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE notifications SET read_at = now() WHERE user_id = ? AND read_at IS NULL")) {
            ps.setLong(1, userId);
            return ps.executeUpdate();
        }
    }
}
