package com.classsync.dao;

import com.classsync.model.Material;
import com.classsync.model.ReviewStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Review workflow and upvotes for student notes (materials with kind = 'NOTE'). */
public class NoteDao {

    /** One entry of a note's review history. */
    public record HistoryRow(long id, String action, String actorName, String comment, Instant createdAt) {
    }

    private static final String SELECT = """
            SELECT m.*, c.code AS course_code, l.lecture_no, u.full_name AS uploader_name,
                   (SELECT count(*) FROM notes_votes v WHERE v.material_id = m.id) AS votes
            FROM materials m
            JOIN courses c ON c.id = m.course_id
            JOIN users u ON u.id = m.uploader_id
            LEFT JOIN lectures l ON l.id = m.lecture_id
            """;

    /** Moves a note to a review status; published follows (only APPROVED notes are published). */
    public void setStatus(Connection con, long noteId, ReviewStatus status) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE materials SET review_status = ?, published = ?, updated_at = now() WHERE id = ? AND kind = 'NOTE'")) {
            ps.setString(1, status.name());
            ps.setBoolean(2, status == ReviewStatus.APPROVED);
            ps.setLong(3, noteId);
            ps.executeUpdate();
        }
    }

    /** Resubmission with new details (and optionally a new file). */
    public void resubmit(Connection con, long noteId, String title, String description, String originalName,
                         String storedPath, String fileType, Long sizeBytes) throws SQLException {
        boolean newFile = storedPath != null;
        String sql = "UPDATE materials SET title = ?, description = ?, review_status = 'PENDING', published = FALSE, updated_at = now()"
                + (newFile ? ", original_filename = ?, stored_path = ?, file_type = ?, size_bytes = ?" : "")
                + " WHERE id = ? AND kind = 'NOTE'";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, title);
            ps.setString(i++, description);
            if (newFile) {
                ps.setString(i++, originalName);
                ps.setString(i++, storedPath);
                ps.setString(i++, fileType);
                ps.setLong(i++, sizeBytes);
            }
            ps.setLong(i, noteId);
            ps.executeUpdate();
        }
    }

    /** Appends to the review history (append-only for the application role). Returns the new row id. */
    public long addHistory(Connection con, long noteId, long actorId, String action, String comment) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO material_approvals (material_id, actor_id, action, comment) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, noteId);
            ps.setLong(2, actorId);
            ps.setString(3, action);
            ps.setString(4, comment == null ? "" : comment);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    public List<HistoryRow> history(Connection con, long noteId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT a.id, a.action, u.full_name, a.comment, a.created_at
                FROM material_approvals a JOIN users u ON u.id = a.actor_id
                WHERE a.material_id = ? ORDER BY a.created_at, a.id""")) {
            ps.setLong(1, noteId);
            List<HistoryRow> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new HistoryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getObject(5, OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }

    /** Pending notes in the given courses that the reviewer did not write. */
    public List<Material> reviewQueue(Connection con, List<Long> courseIds, long reviewerId) throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement ps = con.prepareStatement(SELECT + """
                 WHERE m.kind = 'NOTE' AND m.review_status = 'PENDING' AND m.archived_at IS NULL
                   AND m.course_id = ANY(?) AND m.uploader_id <> ?
                 ORDER BY m.updated_at""")) {
            ps.setArray(1, con.createArrayOf("bigint", courseIds.toArray()));
            ps.setLong(2, reviewerId);
            return map(ps);
        }
    }

    /** All of a student's own notes, any status, newest first. */
    public List<Material> mine(Connection con, long uploaderId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT
                + " WHERE m.kind = 'NOTE' AND m.uploader_id = ? AND m.archived_at IS NULL ORDER BY m.updated_at DESC")) {
            ps.setLong(1, uploaderId);
            return map(ps);
        }
    }

    /** Latest reviewer comment (for showing why a note was rejected). */
    public String latestComment(Connection con, long noteId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT comment FROM material_approvals
                WHERE material_id = ? AND action IN ('APPROVED', 'REJECTED')
                ORDER BY created_at DESC, id DESC LIMIT 1""")) {
            ps.setLong(1, noteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    /** Adds an upvote; false if the voter had already voted (primary key). */
    public boolean vote(Connection con, long noteId, long voterId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO notes_votes (material_id, voter_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setLong(1, noteId);
            ps.setLong(2, voterId);
            return ps.executeUpdate() == 1;
        }
    }

    public void unvote(Connection con, long noteId, long voterId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM notes_votes WHERE material_id = ? AND voter_id = ?")) {
            ps.setLong(1, noteId);
            ps.setLong(2, voterId);
            ps.executeUpdate();
        }
    }

    public Set<Long> votedBy(Connection con, long voterId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT material_id FROM notes_votes WHERE voter_id = ?")) {
            ps.setLong(1, voterId);
            Set<Long> ids = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        }
    }

    private List<Material> map(PreparedStatement ps) throws SQLException {
        List<Material> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(MaterialDao.mapRow(rs));
            }
        }
        return list;
    }
}
