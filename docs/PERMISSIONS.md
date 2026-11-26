# ClassSync — Permission Matrix

Roles: **Student**, **Professor**, **CR** (Class Representative) and **Administrator** (added in V007 for
account management). The academic matrix below covers the first three; administrators have no course, material,
quiz or attendance access at all and appear only in the *Account administration* table at the end.

Every rule below is enforced in a **service method** (`com.classsync.service.*`) using the signed-in user plus database lookups (`enrollments`, `courses.professor_id`, `course_staff`). Hiding a button is only a convenience. CR delegation is re-read from `course_staff` on every operation, so revocation takes effect on the next call.

Legend: ✅ allowed · ❌ denied · 🔸 requires the named delegated permission · *own* = only own records · *enrolled* = only in enrolled courses · *assigned* = only courses where they are the assigned professor.

| Action | Student | CR | Professor |
|---|---|---|---|
| Register via the login screen | ✅ (creates STUDENT only) | ❌ provisioned | ❌ provisioned |
| View enrolled courses, timetable, published lectures | ✅ *enrolled* | ✅ *enrolled* | ✅ *assigned* (incl. drafts) |
| Create / edit / archive course, manage enrollment | ❌ | ❌ | ✅ *assigned* (creates courses assigned to self) |
| Grant / revoke CR permissions | ❌ | ❌ (never to self) | ✅ *assigned* |
| Create / edit lectures, publish previews | ❌ | 🔸 `can_update_lectures` | ✅ *assigned* |
| View course schedule (incl. draft lectures) | ❌ | ✅ default staff right | ✅ |
| Upload course materials | ❌ | ✅ default staff right (unpublished until published by an authorised user if not 🔸 organize) | ✅ |
| Publish / organise / archive materials | ❌ | 🔸 `can_organize_resources` | ✅ |
| Download published material | ✅ *enrolled* | ✅ *enrolled* | ✅ *assigned* |
| Create / publish quizzes, release results | ❌ | ❌ | ✅ |
| Modify official quiz marks | ❌ | ❌ | ❌ (scores are computed; professors cannot hand-edit) |
| Attempt quizzes | ✅ *enrolled* | ✅ *enrolled* | ❌ |
| See own score, correct answers, explanations | ✅ *after release (policy)* | ✅ *after release (policy)* | — |
| Practice re-attempts (never official) | ✅ *after release, if allowed* | ✅ *after release, if allowed* | — |
| See answer keys, quiz analytics, class weak topics | ❌ | ❌ (even with every delegable permission) | ✅ *assigned* |
| Start attendance session, finalize | ❌ | ❌ | ✅ |
| Correct finalized attendance (with reason) | ❌ | ❌ | ✅ |
| Submit attendance code | ✅ *enrolled* | ✅ *enrolled* | ❌ |
| View own attendance, percentages, warnings | ✅ | ✅ | — |
| View lecture roster and course attendance report | ❌ | ❌ (even with every delegable permission) | ✅ *assigned* |
| Submit / edit lecture feedback | ✅ *enrolled*, own | ✅ *enrolled*, own | ❌ |
| View feedback report | ❌ | ❌ | ✅ (anonymous rows have no identity) |
| Upload notes | ✅ *enrolled* | ✅ *enrolled* | ❌ (professors upload `MATERIAL`, which needs no review) |
| See pending/rejected notes | *own* | *own* + 🔸 `can_review_notes` | ✅ |
| Approve / reject notes | ❌ | 🔸 `can_review_notes`, **never own** | ✅, never own |
| Upvote note | ✅ *enrolled*, once, not own | same | ❌ |
| Ask / reply to doubts | ✅ *enrolled* | ✅ *enrolled* | ✅ (reply marked official answer) |
| Resolve doubt | *own* doubt | *own* or 🔸 `can_moderate_discussions` | ✅ |
| Hide doubt / reply (moderate) | ❌ | 🔸 `can_moderate_discussions` | ✅ |
| Publish announcements | ❌ | 🔸 `can_publish_announcements` | ✅ |
| Change roles of any user | ❌ | ❌ | ❌ (local provisioning tool only) |
| Bookmarks, study tasks, revision history | *own* only (others get "not found") | *own* only | — |
| Performance, badges, streak, revision mode | *own* | *own* | — |
| Resource library (published resources + approved notes) | ✅ *enrolled* | ✅ *enrolled* | ✅ *assigned* |
| Semester planner — edit (dates, hours, order, topics) | ❌ | ❌ (not delegable) | ✅ *assigned* |
| Semester plan / syllabus progress — read | ✅ *enrolled* (service) | ✅ *enrolled* (service) | ✅ |
| Course analytics | ❌ | ❌ | ✅ |

## CR defaults vs. delegated permissions

* **Default** (granted by being listed in `course_staff`): view the course schedule including drafts; upload course materials (saved unpublished unless they also hold `can_organize_resources`).
* **Delegable** (boolean columns on `course_staff`): `can_update_lectures`, `can_review_notes`, `can_publish_announcements`, `can_organize_resources`, `can_moderate_discussions`.
* **Never delegable**: quiz marks, attendance finalization/correction, role changes, approving own notes, granting permissions.

A `course_staff` row has a composite FK to `enrollments(course_id, student_id)`, so the database itself refuses delegation to someone not enrolled, and unenrolling removes the delegation automatically.

## Account administration (`AdminService`)

Every call re-reads the caller from `users` and requires `role = 'ADMIN' AND active`, so a disabled or removed
administrator is refused on their very next action, even with the window still open.

| Action | Student | CR | Professor | Administrator |
|---|---|---|---|---|
| Open the admin dashboard / User Management, list and search accounts | ❌ | ❌ | ❌ | ✅ |
| Create a Student, CR, Professor or Administrator account | ❌ (registration: own STUDENT only) | ❌ | ❌ | ✅ |
| Edit name, department, semester, designation | ❌ | ❌ | ❌ | ✅ (email, enrollment and employee numbers are fixed) |
| Switch Student ↔ CR (CR → Student also deletes the CR's `course_staff` rows) | ❌ | ❌ | ❌ | ✅ learners only; never to/from Professor or Administrator |
| Set a new password for any account | ❌ | ❌ | ❌ | ✅ (audited, password never logged) |
| Disable / re-enable an account | ❌ | ❌ | ❌ | ✅ never themselves; never the last active administrator |
| Delegate CR rights inside a course | ❌ | ❌ | ✅ *assigned* | ❌ (stays with the course professor) |
| Courses, lectures, materials, quizzes, attendance, feedback, discussions | see above | see above | see above | ❌ |

Disabling administrators is serialised: the service locks every `ADMIN` row in id order before the last-admin
check, so two administrators disabling each other at the same moment run one after the other and one always
remains (test `mutualDisablingRaceAlwaysLeavesAnActiveAdmin`).
