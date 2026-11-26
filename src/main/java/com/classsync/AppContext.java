package com.classsync;

import com.classsync.config.AppConfig;
import com.classsync.config.Database;
import com.classsync.service.AccessPolicy;
import com.classsync.service.AdminService;
import com.classsync.service.AnnouncementService;
import com.classsync.service.AttendanceService;
import com.classsync.service.DiscussionService;
import com.classsync.service.FeedbackService;
import com.classsync.service.NoteService;
import com.classsync.service.NotificationService;
import com.classsync.service.PlannerService;
import com.classsync.service.ProgressService;
import com.classsync.service.StudyService;
import com.classsync.service.AuthService;
import com.classsync.service.CourseService;
import com.classsync.service.LectureService;
import com.classsync.service.MaterialService;
import com.classsync.service.QuizAnalyticsService;
import com.classsync.service.QuizService;
import com.classsync.util.FileStorage;
import com.classsync.util.PasswordHasher;

import java.time.Clock;

/**
 * Creates and holds the single instance of each service. This is plain manual wiring:
 * no dependency-injection framework is needed for an application of this size.
 */
public class AppContext {

    private final AppConfig config;
    private final Database database;
    private final Clock clock;
    private final Session session = new Session();
    private final Background background = new Background(session);
    private final AuthService auth;
    private final CourseService courses;
    private final LectureService lectures;
    private final MaterialService materials;
    private final QuizService quizzes;
    private final QuizAnalyticsService quizAnalytics;
    private final AttendanceService attendance;
    private final FeedbackService feedback;
    private final NoteService notes;
    private final DiscussionService discussions;
    private final AnnouncementService announcements;
    private final NotificationService notifications;
    private final ProgressService progress;
    private final PlannerService planner;
    private final StudyService study;
    private final AdminService admin;

    public AppContext(AppConfig config) {
        this(config, Database.forApplication(config), Clock.system(config.zone()));
    }

    /** Also used by the screenshot tool to point the UI at a disposable database. */
    public AppContext(AppConfig config, Database database, Clock clock) {
        this.config = config;
        this.database = database;
        this.clock = clock;
        AccessPolicy access = new AccessPolicy();
        FileStorage storage = new FileStorage(config.uploadRoot(), config.maxUploadBytes());
        this.auth = new AuthService(database, new PasswordHasher(), config.emailPattern());
        this.courses = new CourseService(database, access);
        this.lectures = new LectureService(database, access, clock);
        this.materials = new MaterialService(database, access, storage, lectures);
        this.quizzes = new QuizService(database, access, clock);
        this.quizAnalytics = new QuizAnalyticsService(database, access, quizzes);
        this.attendance = new AttendanceService(database, access, config.getInt(AppConfig.ATTENDANCE_CODE_SECONDS));
        this.feedback = new FeedbackService(database, access);
        this.notes = new NoteService(database, access, storage, lectures, materials);
        this.discussions = new DiscussionService(database, access, lectures);
        this.announcements = new AnnouncementService(database, access, lectures);
        this.notifications = new NotificationService(database);
        this.progress = new ProgressService(database, access, clock, attendance, quizAnalytics);
        this.planner = new PlannerService(database, access, clock);
        this.study = new StudyService(database, access, materials, lectures, discussions);
        this.admin = new AdminService(database, new PasswordHasher());
    }

    public AppConfig config() { return config; }
    public Database database() { return database; }
    public Clock clock() { return clock; }
    public Session session() { return session; }
    public Background background() { return background; }
    public AuthService auth() { return auth; }
    public CourseService courses() { return courses; }
    public LectureService lectures() { return lectures; }
    public MaterialService materials() { return materials; }
    public QuizService quizzes() { return quizzes; }
    public QuizAnalyticsService quizAnalytics() { return quizAnalytics; }
    public AttendanceService attendance() { return attendance; }
    public FeedbackService feedback() { return feedback; }
    public NoteService notes() { return notes; }
    public DiscussionService discussions() { return discussions; }
    public AnnouncementService announcements() { return announcements; }
    public NotificationService notifications() { return notifications; }
    public ProgressService progress() { return progress; }
    public PlannerService planner() { return planner; }
    public StudyService study() { return study; }
    public AdminService admin() { return admin; }
}
