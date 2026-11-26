package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.dao.NotificationDao;
import com.classsync.model.Notification;
import com.classsync.model.User;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Reading and clearing the signed-in user's notifications. Notifications are CREATED by the services whose
 * actions cause them (announcements, lecture changes, quiz/material publishing, note reviews, doubt replies,
 * attendance warnings), inside those actions' transactions.
 *
 * Refresh strategy: the top-bar bell re-reads the unread count every 30 seconds and after navigation.
 */
public class NotificationService {

    private final Database db;
    private final NotificationDao dao = new NotificationDao();

    public NotificationService(Database db) {
        this.db = db;
    }

    public List<Notification> list(User u, boolean unreadOnly, int limit) throws SQLException {
        try (Connection con = db.open()) {
            return dao.list(con, u.id(), unreadOnly, Math.min(Math.max(limit, 1), 200));
        }
    }

    public int unreadCount(User u) throws SQLException {
        try (Connection con = db.open()) {
            return dao.unreadCount(con, u.id());
        }
    }

    public void markRead(User u, long notificationId) throws SQLException {
        try (Connection con = db.open()) {
            dao.markRead(con, u.id(), notificationId);
        }
    }

    public int markAllRead(User u) throws SQLException {
        try (Connection con = db.open()) {
            return dao.markAllRead(con, u.id());
        }
    }
}
