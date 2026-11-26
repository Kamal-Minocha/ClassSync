package com.classsync.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Creates JDBC connections to PostgreSQL.
 *
 * Every caller opens its own short-lived connection with try-with-resources:
 *   try (Connection con = database.open()) { ... }
 * A Connection is never shared between background tasks.
 */
public class Database {

    private final String url;
    private final String user;
    private final String password;

    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Connection used by the desktop application (restricted role). */
    public static Database forApplication(AppConfig config) {
        return new Database(config.require(AppConfig.DB_URL),
                config.require(AppConfig.APP_USER),
                config.require(AppConfig.APP_PASSWORD));
    }

    public Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("ApplicationName", "ClassSync");
        props.setProperty("connectTimeout", "5");   // seconds
        props.setProperty("socketTimeout", "60");    // seconds
        return DriverManager.getConnection(url, props);
    }

    /** URL without credentials, safe to show in error messages. */
    public String describe() {
        return url;
    }

    /** Quick readiness probe used by the startup screen. */
    public void ping() throws SQLException {
        try (Connection con = open(); var st = con.createStatement()) {
            st.execute("SELECT 1");
        }
    }
}
