package com.classsync.service;

import com.classsync.dao.CourseDao;
import com.classsync.model.CoursePermission;
import com.classsync.model.Role;
import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Course-scoped authorisation rules, checked with fresh database lookups on every call.
 * Because nothing is cached, revoking a CR permission takes effect on the very next operation.
 */
public class AccessPolicy {

    private final CourseDao courses = new CourseDao();

    /** Professor of the course, or an enrolled student/CR. */
    public boolean isMember(Connection con, User u, long courseId) throws SQLException {
        return u.role() == Role.PROFESSOR
                ? courses.isProfessorOf(con, u.id(), courseId)
                : courses.isEnrolled(con, u.id(), courseId);
    }

    public void requireMember(Connection con, User u, long courseId) throws SQLException {
        if (!isMember(con, u, courseId)) {
            throw new AccessDeniedException("You are not a member of this course.");
        }
    }

    public boolean isCourseProfessor(Connection con, User u, long courseId) throws SQLException {
        return u.role() == Role.PROFESSOR && courses.isProfessorOf(con, u.id(), courseId);
    }

    public void requireCourseProfessor(Connection con, User u, long courseId) throws SQLException {
        if (!isCourseProfessor(con, u, courseId)) {
            throw new AccessDeniedException("Only the course's professor can do this.");
        }
    }

    /** True for the course professor, or a CR whose course_staff row grants the permission. */
    public boolean has(Connection con, User u, long courseId, CoursePermission permission) throws SQLException {
        if (isCourseProfessor(con, u, courseId)) {
            return true;
        }
        if (u.role() != Role.CR) {
            return false;
        }
        Optional<Set<CoursePermission>> perms = courses.staffPermissions(con, u.id(), courseId);
        return perms.isPresent() && perms.get().contains(permission);
    }

    public void require(Connection con, User u, long courseId, CoursePermission permission) throws SQLException {
        if (!has(con, u, courseId, permission)) {
            throw new AccessDeniedException("You do not have permission to " + permission.label().toLowerCase() + " in this course.");
        }
    }

    /** Courses the user can see (taught or enrolled, not archived). */
    public List<Long> visibleCourseIds(Connection con, User u) throws SQLException {
        return courses.accessibleCourseIds(con, u.id(), u.role());
    }

    /** Subset of courses in which the user may see drafts / unpublished items (professor or CR staff). */
    public List<Long> staffCourseIds(Connection con, User u) throws SQLException {
        List<Long> visible = visibleCourseIds(con, u);
        if (u.role() == Role.PROFESSOR) {
            return visible;
        }
        if (u.role() != Role.CR) {
            return List.of();
        }
        List<Long> staff = new ArrayList<>();
        for (Long id : visible) {
            if (courses.staffPermissions(con, u.id(), id).isPresent()) {
                staff.add(id);
            }
        }
        return staff;
    }
}
