package com.classsync.tools;

import com.classsync.config.AppConfig;
import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.CourseDao;
import com.classsync.dao.LectureDao;
import com.classsync.dao.UserDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.MaterialKind;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AccessPolicy;
import com.classsync.service.AuthService;
import com.classsync.service.LectureService;
import com.classsync.service.MaterialService;
import com.classsync.util.FileStorage;
import com.classsync.util.PasswordHasher;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in, deterministic demonstration data. Schedules are generated RELATIVE TO TODAY in the
 * academic time zone, so the demo always has past, today's and upcoming lectures.
 *
 *   mvnw compile exec:java@demo-data
 *
 * Refuses to run when the database already contains user accounts, so it can never mix
 * with real data. All demo accounts use the password  Demo@1234  (demonstration only!).
 */
public final class DemoData {

    public static final String PASSWORD = "Demo@1234";

    /** Accounts created by the demo, for the README and the demo script. */
    public static final Map<String, String> ACCOUNTS = new LinkedHashMap<>();

    static {
        ACCOUNTS.put("meera.iyer@college.edu", "Professor (CS301, CS305)");
        ACCOUNTS.put("arjun.menon@college.edu", "Professor (CS310)");
        ACCOUNTS.put("ravi.kumar@college.edu", "Class Representative (CR staff in CS301 and CS305), enrollment CS2024002");
        ACCOUNTS.put("asha.rao@college.edu", "Student, enrollment CS2024001");
        ACCOUNTS.put("admin@college.edu", "Administrator (user management)");
    }

    private final Database db;
    private final AppConfig config;
    private final ZoneId zone;
    private final AuthService auth;
    private final LectureService lectures;
    private final MaterialService materials;
    private final UserDao users = new UserDao();
    private final CourseDao courses = new CourseDao();
    private final LectureDao lectureDao = new LectureDao();
    private final Path scratch;

    public DemoData(Database db, AppConfig config, int hashIterations) throws IOException {
        this.db = db;
        this.config = config;
        this.zone = config.zone();
        AccessPolicy access = new AccessPolicy();
        Clock clock = Clock.system(zone);
        this.auth = new AuthService(db, new PasswordHasher(hashIterations), config.emailPattern());
        this.lectures = new LectureService(db, access, clock);
        this.materials = new MaterialService(db, access, new FileStorage(config.uploadRoot(), config.maxUploadBytes()), lectures);
        this.scratch = Files.createTempDirectory("classsync-demo");
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        Database owner = new Database(config.require(AppConfig.DB_URL),
                config.require(AppConfig.MIGRATOR_USER), config.require(AppConfig.MIGRATOR_PASSWORD));
        new DemoData(owner, config, PasswordHasher.DEFAULT_ITERATIONS).seed();
        System.out.println("Demo data created. Sign in with any account below, password " + PASSWORD + ":");
        ACCOUNTS.forEach((email, what) -> System.out.println("  " + email + "  —  " + what));
    }

    public void seed() throws Exception {
        try (Connection con = db.open(); var st = con.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM users")) {
            rs.next();
            if (rs.getInt(1) > 0) {
                throw new IllegalStateException("The database already has user accounts. Demo data is only loaded into an empty database.");
            }
        }
        World w = accounts();
        coursesAndEnrollment(w);
        lectures(w);
        materials(w);
        DemoDataExtras.seed(this, w);
    }

    // ------------------------------------------------------------------ accounts

    /** Handles to everything the later seeding steps need. */
    public static final class World {
        public User meera;
        public User arjun;
        public User ravi;
        public final List<User> students = new ArrayList<>();
        public long dbms;
        public long java;
        public long networks;
        public final Map<String, Long> lectures = new LinkedHashMap<>();
        public final Map<String, Long> topics = new LinkedHashMap<>();
    }

    private World accounts() throws SQLException {
        char[] pw = PASSWORD.toCharArray();
        World w = new World();
        auth.provisionAdmin("Department Administrator", "admin@college.edu", pw.clone());
        w.meera = user(auth.provisionProfessor("Dr. Meera Iyer", "meera.iyer@college.edu", "EMP101", "Computer Science", "Associate Professor", pw.clone()));
        w.arjun = user(auth.provisionProfessor("Dr. Arjun Menon", "arjun.menon@college.edu", "EMP102", "Computer Science", "Assistant Professor", pw.clone()));
        w.ravi = user(auth.provisionLearner(Role.CR, "Ravi Kumar", "ravi.kumar@college.edu", "CS2024002", "Computer Science", 5, pw.clone()));
        String[][] students = {
                {"Asha Rao", "asha.rao@college.edu", "CS2024001"},
                {"Neha Sharma", "neha.sharma@college.edu", "CS2024003"},
                {"Kabir Singh", "kabir.singh@college.edu", "CS2024004"},
                {"Priya Nair", "priya.nair@college.edu", "CS2024005"},
                {"Rohan Das", "rohan.das@college.edu", "CS2024006"},
                {"Zoya Khan", "zoya.khan@college.edu", "CS2024007"}};
        for (String[] s : students) {
            w.students.add(user(auth.provisionLearner(Role.STUDENT, s[0], s[1], s[2], "Computer Science", 5, pw.clone())));
        }
        return w;
    }

    private User user(long id) throws SQLException {
        try (Connection con = db.open()) {
            return users.findById(con, id).orElseThrow();
        }
    }

    // ------------------------------------------------------------------ courses

    private void coursesAndEnrollment(World w) throws SQLException {
        Tx.inTransaction(db, con -> {
            w.dbms = courses.insert(con, "CS301", "Database Management Systems", "Computer Science", 5,
                    "Relational model, SQL, normalization, transactions and indexing with PostgreSQL.", w.meera.id());
            w.java = courses.insert(con, "CS305", "Object-Oriented Programming with Java", "Computer Science", 5,
                    "Classes, collections, JDBC and JavaFX desktop applications.", w.meera.id());
            w.networks = courses.insert(con, "CS310", "Computer Networks", "Computer Science", 5,
                    "Layered architecture, TCP/IP, routing and application protocols.", w.arjun.id());
            List<User> all = new ArrayList<>(w.students);
            all.add(w.ravi);
            for (User s : all) {
                courses.enroll(con, w.dbms, s.id());
                courses.enroll(con, w.java, s.id());
            }
            for (User s : all.subList(0, 4)) {
                courses.enroll(con, w.networks, s.id());
            }
            courses.upsertStaff(con, w.dbms, w.ravi.id(), EnumSet.of(CoursePermission.REVIEW_NOTES,
                    CoursePermission.PUBLISH_ANNOUNCEMENTS, CoursePermission.UPDATE_LECTURES), w.meera.id());
            courses.upsertStaff(con, w.java, w.ravi.id(), EnumSet.noneOf(CoursePermission.class), w.meera.id());

            String[][] dbmsTopics = {{"1", "Relational model"}, {"1", "SQL basics"}, {"2", "Joins and subqueries"},
                    {"2", "Normalization"}, {"3", "Transactions and ACID"}, {"3", "Concurrency control"}, {"4", "Indexing"}};
            int seq = 1;
            for (String[] t : dbmsTopics) {
                w.topics.put("CS301:" + t[1], courses.insertTopic(con, w.dbms, Integer.parseInt(t[0]), t[1], seq++));
            }
            String[][] javaTopics = {{"4", "JDBC architecture"}, {"4", "PreparedStatement and ResultSet"},
                    {"4", "Transactions in JDBC"}, {"5", "JavaFX scene graph"}, {"5", "JavaFX layouts and events"}, {"5", "JavaFX CSS and charts"}};
            seq = 1;
            for (String[] t : javaTopics) {
                w.topics.put("CS305:" + t[1], courses.insertTopic(con, w.java, Integer.parseInt(t[0]), t[1], seq++));
            }
            String[][] netTopics = {{"1", "OSI and TCP/IP models"}, {"2", "IP addressing"}, {"3", "Routing"}};
            seq = 1;
            for (String[] t : netTopics) {
                w.topics.put("CS310:" + t[1], courses.insertTopic(con, w.networks, Integer.parseInt(t[0]), t[1], seq++));
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ lectures

    private void lectures(World w) throws SQLException {
        LocalDate today = LocalDate.now(zone);
        // CS301: 4 completed, 1 today, 3 upcoming published, 1 draft, 1 cancelled
        lecture(w, w.dbms, w.meera, "CS301-L1", -21, 9, "Relational model", "The relational model: relations, keys and constraints",
                "Read chapter 2 of the textbook.", "Define relation, tuple and attribute\nIdentify candidate and primary keys", "COMPLETED");
        lecture(w, w.dbms, w.meera, "CS301-L2", -14, 9, "SQL basics", "SQL basics: SELECT, WHERE, ORDER BY",
                "Install PostgreSQL via Docker and run SELECT 1.", "Write single-table queries\nFilter and sort results", "COMPLETED");
        lecture(w, w.dbms, w.meera, "CS301-L3", -7, 9, "Joins and subqueries", "Joins and subqueries",
                "Revise SELECT and WHERE.", "Use INNER and LEFT joins\nWrite correlated subqueries", "COMPLETED");
        lecture(w, w.dbms, w.meera, "CS301-L4", -2, 9, "Normalization", "Normalization: 1NF, 2NF and 3NF",
                "Bring the sample invoice table from the last tutorial.", "Spot update anomalies\nNormalize a table to 3NF", "COMPLETED");
        lecture(w, w.dbms, w.meera, "CS301-L5", 0, 11, "Normalization", "BCNF and decomposition",
                "Read section 7.4 (BCNF) and attempt exercise 7.3.", "Explain BCNF\nDecompose a relation losslessly", "PUBLISHED");
        lecture(w, w.dbms, w.meera, "CS301-L6", 2, 9, "Transactions and ACID", "Transactions and ACID properties",
                "Watch the 10-minute video on bank transfers and note what can go wrong.\nRead section 14.1.",
                "State the ACID properties\nUse COMMIT and ROLLBACK", "PUBLISHED");
        lecture(w, w.dbms, w.meera, "CS301-L7", 5, 9, "Concurrency control", "Concurrency control and isolation levels",
                "Revise transactions. Read about dirty reads and lost updates.", "Compare isolation levels\nExplain row locking", "PUBLISHED");
        lecture(w, w.dbms, w.meera, "CS301-L8", 9, 9, "Indexing", "Indexing with B-trees",
                "Read section 11.2 on B+ trees.", "Explain how an index speeds up lookups", "PUBLISHED");
        lecture(w, w.dbms, w.meera, "CS301-L9", 12, 9, "Indexing", "Query optimisation basics (draft)",
                "To be announced.", "", "DRAFT");
        // CS305
        lecture(w, w.java, w.meera, "CS305-L1", -12, 14, "JDBC architecture", "JDBC architecture and drivers",
                "Read the JDBC overview handout.", "Explain DriverManager and Connection", "COMPLETED");
        lecture(w, w.java, w.meera, "CS305-L2", -5, 14, "PreparedStatement and ResultSet", "PreparedStatement and ResultSet",
                "Try the SQL injection example from the handout.", "Use PreparedStatement parameters\nIterate a ResultSet", "COMPLETED");
        lecture(w, w.java, w.meera, "CS305-L3", -1, 14, "Transactions in JDBC", "Transactions, commit and rollback in JDBC",
                "Revise ACID from CS301.", "Disable auto-commit\nRoll back on failure", "CANCELLED");
        lecture(w, w.java, w.meera, "CS305-L4", 0, 14, "JavaFX scene graph", "JavaFX application lifecycle and scene graph",
                "Install JDK 21 and run the HelloFX sample.", "Explain init, start and stop\nBuild a Stage and Scene", "PUBLISHED");
        lecture(w, w.java, w.meera, "CS305-L5", 3, 14, "JavaFX layouts and events", "Layouts, controls and event handling",
                "Sketch a login screen on paper: which layout panes would you use?", "Use BorderPane, VBox and GridPane\nHandle button events", "PUBLISHED");
        lecture(w, w.java, w.meera, "CS305-L6", 7, 14, "JavaFX CSS and charts", "Styling with CSS and charts",
                "Collect a small dataset you would like to chart.", "Style controls with CSS\nDraw a BarChart", "PUBLISHED");
        // CS310
        lecture(w, w.networks, w.arjun, "CS310-L1", -6, 10, "OSI and TCP/IP models", "OSI and TCP/IP reference models",
                "Read chapter 1.", "Compare the OSI and TCP/IP models", "COMPLETED");
        lecture(w, w.networks, w.arjun, "CS310-L2", 1, 10, "IP addressing", "IPv4 addressing and subnetting",
                "Practise binary-to-decimal conversion.", "Calculate subnet masks", "PUBLISHED");
        lecture(w, w.networks, w.arjun, "CS310-L3", 8, 10, "Routing", "Routing algorithms",
                "Read section 5.2.", "Trace distance-vector routing", "PUBLISHED");
    }

    private void lecture(World w, long courseId, User creator, String key, int dayOffset, int hour, String topicName,
                         String topic, String preparation, String objectives, String status) throws SQLException {
        String code = key.substring(0, 5);
        Long topicId = w.topics.get(code + ":" + topicName);
        LocalDate day = LocalDate.now(zone).plusDays(dayOffset);
        int unit;
        try (Connection con = db.open()) {
            unit = topicId == null ? 1 : courses.listTopics(con, courseId).stream()
                    .filter(t -> t.id() == topicId).findFirst().orElseThrow().unitNo();
        }
        long id = lectures.create(creator, new LectureService.LectureForm(courseId, Integer.parseInt(key.substring(7)), unit,
                topicId, topic, "In this lecture we cover " + topic.toLowerCase() + ", with worked examples and a short in-class activity.",
                objectives, preparation, code.equals("CS310") ? "LH-2" : "LH-4", day, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0)));
        try (Connection con = db.open(); var ps = con.prepareStatement("UPDATE lectures SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
        w.lectures.put(key, id);
    }

    // ------------------------------------------------------------------ materials

    private void materials(World w) throws Exception {
        upload(w.meera, w.dbms, w.lectures.get("CS301-L4"), "Normalization slides", "Lecture 4 slides with worked examples", 2, pdf("Normalization slides", "1NF, 2NF and 3NF with the invoice example."), true);
        upload(w.meera, w.dbms, w.lectures.get("CS301-L5"), "BCNF reading", "Preparation reading for lecture 5", 2, pdf("BCNF reading", "Boyce-Codd normal form and lossless decomposition."), true);
        upload(w.meera, w.dbms, w.lectures.get("CS301-L6"), "Transactions primer", "Read before lecture 6", 3, pdf("Transactions primer", "Atomicity, consistency, isolation and durability."), true);
        upload(w.meera, w.dbms, null, "ER diagram cheat sheet", "One-page reference", 1, png("ER cheat sheet"), true);
        uploadKind(w.meera, w.dbms, MaterialKind.PYQ, "DBMS end-semester paper (previous year)", pdf("Previous year paper", "Section A: SQL. Section B: Normalization."));
        upload(w.meera, w.java, w.lectures.get("CS305-L2"), "JDBC code samples", "PreparedStatement examples", 4, pdf("JDBC samples", "try-with-resources, PreparedStatement and ResultSet."), true);
        upload(w.meera, w.java, w.lectures.get("CS305-L4"), "JavaFX lifecycle handout", "init, start and stop explained", 5, pdf("JavaFX lifecycle", "Application.init(), start(Stage) and stop()."), true);
        upload(w.arjun, w.networks, w.lectures.get("CS310-L2"), "Subnetting worksheet", "Practice problems", 2, pdf("Subnetting worksheet", "Ten subnetting exercises."), true);
        // CR upload without 'organise resources' in CS305 -> stays unpublished until the professor publishes it
        upload(w.ravi, w.java, null, "Lab timetable (CR upload)", "Shared by the class representative", null, pdf("Lab timetable", "Lab batches A and B."), false);
    }

    private void upload(User u, long course, Long lecture, String title, String desc, Integer unit, Path file, boolean publish) throws Exception {
        materials.upload(u, new MaterialService.UploadForm(course, lecture, MaterialKind.MATERIAL, title, desc, unit, file, publish));
    }

    private void uploadKind(User u, long course, MaterialKind kind, String title, Path file) throws Exception {
        materials.upload(u, new MaterialService.UploadForm(course, null, kind, title, "", null, file, true));
    }

    /** Writes a small but valid one-page PDF containing the given text. */
    Path pdf(String title, String body) throws IOException {
        String text = "BT /F1 20 Tf 72 720 Td (" + esc(title) + ") Tj /F1 12 Tf 0 -32 Td (" + esc(body)
                + ") Tj 0 -24 Td (ClassSync demonstration document) Tj ET";
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + text.length() + " >>\nstream\n" + text + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(sb.length());
            sb.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = sb.length();
        sb.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) {
            sb.append(String.format("%010d 00000 n \n", off));
        }
        sb.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        Path p = scratch.resolve(title.replaceAll("[^A-Za-z0-9]+", "_") + ".pdf");
        Files.writeString(p, sb.toString(), StandardCharsets.ISO_8859_1);
        return p;
    }

    Path png(String title) throws IOException {
        BufferedImage img = new BufferedImage(800, 450, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xEEF2FF));
        g.fillRect(0, 0, 800, 450);
        g.setColor(new Color(0x4F46E5));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
        g.drawString(title, 60, 120);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
        g.drawString("Entity  —  Relationship  —  Attribute", 60, 200);
        g.dispose();
        Path p = scratch.resolve(title.replaceAll("[^A-Za-z0-9]+", "_") + ".png");
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    // accessors for DemoDataExtras (later modules)
    Database db() { return db; }
    AppConfig config() { return config; }
    ZoneId zone() { return zone; }
    LectureDao lectureDao() { return lectureDao; }
}
