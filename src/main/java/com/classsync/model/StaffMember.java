package com.classsync.model;

import java.util.Set;

/** A CR's delegation in one course. */
public record StaffMember(long courseId, long userId, String fullName, String enrollmentNo,
                          Set<CoursePermission> permissions) {
}
