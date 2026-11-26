package com.classsync.service;

import com.classsync.config.Database;
import com.classsync.config.Tx;
import com.classsync.dao.AuditDao;
import com.classsync.dao.CourseDao;
import com.classsync.dao.DbTime;
import com.classsync.dao.LectureDao;
import com.classsync.dao.StudyDao;
import com.classsync.model.Lecture;
import com.classsync.model.LectureStatus;
import com.classsync.model.Topic;
import com.classsync.model.User;
import com.classsync.service.AppException.NotFoundException;
import com.classsync.service.AppException.ValidationException;
import com.classsync.util.Validation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Professor semester planner (Module 13). Each syllabus topic has a planned date and hours; its status is
 * DERIVED from the real lectures that cover the topic (lectures.topic_id), so the plan, the schedule and the
 * students' upcoming-topic previews always agree.
 *
 * Status: COMPLETED (a completed lecture covers it) › SCHEDULED (an upcoming draft/published lecture covers it)
 * › OVERDUE (planned date passed, nothing scheduled) › PLANNED (date set) › UNPLANNED.
 * Syllabus completion = completed topics ÷ all topics (no topics → no percentage).
 */
public class PlannerService {

    public enum Status {
        COMPLETED("Completed"), SCHEDULED("Scheduled"), OVERDUE("Overdue"), PLANNED("Planned"), UNPLANNED("Not planned");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record PlanRow(Topic topic, LocalDate plannedDate, int plannedHours, String notes, Lecture completedBy,
                          Lecture nextLecture, Status status) {
    }

    public record Plan(long courseId, List<PlanRow> rows) {

        public long completed() {
            return rows.stream().filter(r -> r.status() == Status.COMPLETED).count();
        }

        public OptionalDouble completion() {
            return rows.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of((double) completed() / rows.size());
        }
    }

    private final Database db;
    private final AccessPolicy access;
    private final Clock clock;
    private final StudyDao dao = new StudyDao();
    private final CourseDao courses = new CourseDao();
    private final LectureDao lectures = new LectureDao();
    private final AuditDao audit = new AuditDao();

    public PlannerService(Database db, AccessPolicy access, Clock clock) {
        this.db = db;
        this.access = access;
        this.clock = clock;
    }

    /** The plan for a course; readable by every course member (students see syllabus progress), editable by the professor. */
    public Plan plan(User u, long courseId) throws SQLException {
        try (Connection con = db.open()) {
            access.requireMember(con, u, courseId);
            Instant now = DbTime.now(con);
            LocalDate today = now.atZone(clock.getZone()).toLocalDate();
            Map<Long, StudyDao.PlanEntry> plans = dao.plans(con, courseId);
            List<Lecture> all = lectures.list(con, List.of(courseId), List.of(courseId), null, null, true, 1000);
            List<PlanRow> rows = new ArrayList<>();
            for (Topic t : courses.listTopics(con, courseId)) {
                StudyDao.PlanEntry p = plans.get(t.id());
                List<Lecture> covering = all.stream().filter(l -> Long.valueOf(t.id()).equals(l.topicId())).toList();
                Lecture done = covering.stream().filter(l -> l.status() == LectureStatus.COMPLETED)
                        .min(Comparator.comparing(Lecture::startsAt)).orElse(null);
                Lecture next = covering.stream().filter(l -> (l.status() == LectureStatus.PUBLISHED || l.status() == LectureStatus.DRAFT)
                        && l.startsAt().isAfter(now)).min(Comparator.comparing(Lecture::startsAt)).orElse(null);
                LocalDate planned = p == null ? null : p.plannedDate();
                Status s = done != null ? Status.COMPLETED
                        : next != null ? Status.SCHEDULED
                        : planned != null && planned.isBefore(today) ? Status.OVERDUE
                        : planned != null ? Status.PLANNED : Status.UNPLANNED;
                rows.add(new PlanRow(t, planned, p == null ? 1 : p.plannedHours(), p == null ? "" : p.notes(), done, next, s));
            }
            return new Plan(courseId, rows);
        }
    }

    /** Sets or reschedules a topic (planned date, hours, notes, unit). Professor only. */
    public void setPlan(User u, long topicId, LocalDate date, int hours, int unit, String notes) throws SQLException {
        int h = Validation.range(hours, "Planned hours", 1, 20);
        int un = Validation.range(unit, "Unit", 1, 10);
        String n = Validation.optional(notes, "Notes", 500);
        Tx.inTransaction(db, con -> {
            Topic t = topic(con, topicId);
            access.requireCourseProfessor(con, u, t.courseId());
            dao.upsertPlan(con, topicId, date, h, n);
            if (un != t.unitNo()) {
                dao.setTopicUnit(con, topicId, un);
            }
            audit.log(con, u.id(), "PLAN_UPDATE", "topic", topicId, date == null ? "no date" : date.toString());
            return null;
        });
    }

    /** Moves a topic one place up or down within its unit. Professor only. */
    public void move(User u, long topicId, boolean up) throws SQLException {
        Tx.inTransaction(db, con -> {
            Topic t = topic(con, topicId);
            access.requireCourseProfessor(con, u, t.courseId());
            List<Topic> ordered = new ArrayList<>(courses.listTopics(con, t.courseId()));
            int i = ordered.indexOf(t);
            int j = up ? i - 1 : i + 1;
            if (j < 0 || j >= ordered.size() || ordered.get(j).unitNo() != t.unitNo()) {
                throw new ValidationException("The topic is already " + (up ? "first" : "last") + " in its unit.");
            }
            ordered.set(i, ordered.get(j));
            ordered.set(j, t);
            dao.reorder(con, ordered.stream().map(Topic::id).toList());
            return null;
        });
    }

    private Topic topic(Connection con, long topicId) throws SQLException {
        try (var ps = con.prepareStatement("SELECT course_id FROM topics WHERE id = ?")) {
            ps.setLong(1, topicId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("Topic not found.");
                }
                long courseId = rs.getLong(1);
                return courses.listTopics(con, courseId).stream().filter(x -> x.id() == topicId).findFirst().orElseThrow();
            }
        }
    }
}
