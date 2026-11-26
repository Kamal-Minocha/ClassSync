package com.classsync.dao;

import com.classsync.model.Material;
import com.classsync.model.MaterialKind;
import com.classsync.model.ReviewStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Materials and notes metadata (file bytes are on disk, see FileStorage). */
public class MaterialDao {

    public record NewMaterial(long courseId, Long lectureId, MaterialKind kind, String title, String description,
                              Integer unitNo, long uploaderId, String originalFilename, String storedPath,
                              String fileType, long sizeBytes, boolean published, ReviewStatus reviewStatus) {
    }

    /**
     * Listing filter. Visibility rule applied in SQL:
     *   course in courseIds AND not archived AND
     *   (published OR (not a note AND course in staffCourseIds) OR uploaded by viewer)
     * Unapproved notes of other students never appear here; reviewers use the review queue.
     */
    public record Filter(List<Long> courseIds, List<Long> staffCourseIds, long viewerId, List<MaterialKind> kinds,
                         Long lectureId, String query, String sort, int limit) {
    }

    /** Whitelisted ORDER BY clauses; user input only picks a key, never SQL text. */
    public static final Map<String, String> SORTS = Map.of(
            "newest", "m.created_at DESC",
            "oldest", "m.created_at ASC",
            "title", "lower(m.title) ASC",
            "downloads", "m.download_count DESC, m.created_at DESC",
            "votes", "votes DESC, m.created_at DESC");

    private static final String SELECT = """
            SELECT m.*, c.code AS course_code, l.lecture_no, u.full_name AS uploader_name,
                   (SELECT count(*) FROM notes_votes v WHERE v.material_id = m.id) AS votes
            FROM materials m
            JOIN courses c ON c.id = m.course_id
            JOIN users u ON u.id = m.uploader_id
            LEFT JOIN lectures l ON l.id = m.lecture_id
            """;

    public long insert(Connection con, NewMaterial m) throws SQLException {
        String sql = """
                INSERT INTO materials (course_id, lecture_id, kind, title, description, unit_no, uploader_id,
                    original_filename, stored_path, file_type, size_bytes, published, review_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, m.courseId());
            setNullableLong(ps, 2, m.lectureId());
            ps.setString(3, m.kind().name());
            ps.setString(4, m.title());
            ps.setString(5, m.description());
            if (m.unitNo() == null) {
                ps.setNull(6, Types.SMALLINT);
            } else {
                ps.setInt(6, m.unitNo());
            }
            ps.setLong(7, m.uploaderId());
            ps.setString(8, m.originalFilename());
            ps.setString(9, m.storedPath());
            ps.setString(10, m.fileType());
            ps.setLong(11, m.sizeBytes());
            ps.setBoolean(12, m.published());
            ps.setString(13, m.reviewStatus() == null ? null : m.reviewStatus().name());
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    public Optional<Material> findById(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT + " WHERE m.id = ?")) {
            ps.setLong(1, id);
            List<Material> list = map(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    public Optional<Material> lockById(Connection con, long id) throws SQLException {
        // lock only the materials row; the joined tables are read normally
        try (PreparedStatement ps = con.prepareStatement(SELECT + " WHERE m.id = ? FOR UPDATE OF m")) {
            ps.setLong(1, id);
            List<Material> list = map(ps);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    public List<Material> list(Connection con, Filter f) throws SQLException {
        if (f.courseIds().isEmpty()) {
            return List.of();
        }
        String order = SORTS.getOrDefault(f.sort(), SORTS.get("newest"));
        String sql = SELECT + """
                 WHERE m.course_id = ANY(?) AND m.archived_at IS NULL
                   AND (m.published OR (m.kind <> 'NOTE' AND m.course_id = ANY(?)) OR m.uploader_id = ?)
                   AND m.kind = ANY(?)
                   AND (?::bigint IS NULL OR m.lecture_id = ?::bigint)
                   AND (?::text IS NULL OR m.title ILIKE ? OR m.description ILIKE ? OR m.original_filename ILIKE ?)
                 ORDER BY""" + " " + order + " LIMIT ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setArray(1, con.createArrayOf("bigint", f.courseIds().toArray()));
            ps.setArray(2, con.createArrayOf("bigint", f.staffCourseIds().toArray()));
            ps.setLong(3, f.viewerId());
            ps.setArray(4, con.createArrayOf("varchar", f.kinds().stream().map(Enum::name).toArray()));
            setNullableLong(ps, 5, f.lectureId());
            setNullableLong(ps, 6, f.lectureId());
            String q = f.query() == null || f.query().isBlank() ? null : "%" + LectureDao.escapeLike(f.query().trim()) + "%";
            ps.setString(7, q);
            ps.setString(8, q);
            ps.setString(9, q);
            ps.setString(10, q);
            ps.setInt(11, f.limit());
            return map(ps);
        }
    }

    public void setPublished(Connection con, long id, boolean published) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE materials SET published = ?, updated_at = now() WHERE id = ? AND kind <> 'NOTE'")) {
            ps.setBoolean(1, published);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void setRecommended(Connection con, long id, boolean recommended) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE materials SET recommended = ?, updated_at = now() WHERE id = ?")) {
            ps.setBoolean(1, recommended);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void archive(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE materials SET archived_at = now(), updated_at = now() WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void updateDetails(Connection con, long id, String title, String description, Long lectureId, Integer unitNo)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE materials SET title = ?, description = ?, lecture_id = ?, unit_no = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, title);
            ps.setString(2, description);
            setNullableLong(ps, 3, lectureId);
            if (unitNo == null) {
                ps.setNull(4, Types.SMALLINT);
            } else {
                ps.setInt(4, unitNo);
            }
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    public void incrementDownloads(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE materials SET download_count = download_count + 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    List<Material> map(PreparedStatement ps) throws SQLException {
        List<Material> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    static Material mapRow(ResultSet rs) throws SQLException {
        long lectureId = rs.getLong("lecture_id");
        Long lecture = rs.wasNull() ? null : lectureId;
        int lectureNo = rs.getInt("lecture_no");
        Integer lectureNumber = rs.wasNull() ? null : lectureNo;
        int unit = rs.getInt("unit_no");
        Integer unitNo = rs.wasNull() ? null : unit;
        String review = rs.getString("review_status");
        OffsetDateTime archived = rs.getObject("archived_at", OffsetDateTime.class);
        return new Material(rs.getLong("id"), rs.getLong("course_id"), rs.getString("course_code"), lecture,
                lectureNumber, MaterialKind.valueOf(rs.getString("kind")), rs.getString("title"),
                rs.getString("description"), unitNo, rs.getLong("uploader_id"), rs.getString("uploader_name"),
                rs.getString("original_filename"), rs.getString("stored_path"), rs.getString("file_type"),
                rs.getLong("size_bytes"), rs.getBoolean("published"),
                review == null ? null : ReviewStatus.valueOf(review), rs.getBoolean("recommended"),
                rs.getInt("download_count"), archived == null ? null : archived.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("votes"));
    }
}
