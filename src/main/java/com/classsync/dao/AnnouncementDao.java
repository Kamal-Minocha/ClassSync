package com.classsync.dao;

import com.classsync.model.Announcement;
import com.classsync.model.AnnouncementCategory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class AnnouncementDao {

    public long insert(Connection con, long courseId, Long lectureId, long authorId, AnnouncementCategory category,
                       String title, String body) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO announcements (course_id, lecture_id, author_id, category, title, body)
                VALUES (?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, courseId);
            MaterialDao.setNullableLong(ps, 2, lectureId);
            ps.setLong(3, authorId);
            ps.setString(4, category.name());
            ps.setString(5, title);
            ps.setString(6, body);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        }
    }

    /** Newest first, in the given courses. */
    public List<Announcement> list(Connection con, List<Long> courseIds, int limit) throws SQLException {
        if (courseIds.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT a.*, c.code AS course_code, l.lecture_no, u.full_name AS author_name
                FROM announcements a
                JOIN courses c ON c.id = a.course_id
                JOIN users u ON u.id = a.author_id
                LEFT JOIN lectures l ON l.id = a.lecture_id
                WHERE a.course_id = ANY(?)
                ORDER BY a.created_at DESC, a.id DESC LIMIT ?""")) {
            ps.setArray(1, con.createArrayOf("bigint", courseIds.toArray()));
            ps.setInt(2, limit);
            List<Announcement> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long lecture = rs.getLong("lecture_id");
                    Long lectureId = rs.wasNull() ? null : lecture;
                    int no = rs.getInt("lecture_no");
                    list.add(new Announcement(rs.getLong("id"), rs.getLong("course_id"), rs.getString("course_code"),
                            lectureId, rs.wasNull() ? null : no, rs.getLong("author_id"), rs.getString("author_name"),
                            AnnouncementCategory.valueOf(rs.getString("category")), rs.getString("title"),
                            rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class).toInstant()));
                }
            }
            return list;
        }
    }
}
