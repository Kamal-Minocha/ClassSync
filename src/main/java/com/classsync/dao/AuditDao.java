package com.classsync.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Append-only audit trail. Details must never contain passwords, attendance codes,
 * anonymous-feedback identities or quiz answer keys.
 */
public class AuditDao {

    public void log(Connection con, Long actorId, String action, String entityType, Long entityId, String details)
            throws SQLException {
        String sql = "INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, details) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            if (actorId == null) {
                ps.setNull(1, Types.BIGINT);
            } else {
                ps.setLong(1, actorId);
            }
            ps.setString(2, action);
            ps.setString(3, entityType);
            if (entityId == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, entityId);
            }
            ps.setString(5, details == null ? "" : details);
            ps.executeUpdate();
        }
    }
}
