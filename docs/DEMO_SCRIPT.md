# ClassSync — Demo Script

A 15–20 minute walkthrough that shows every role and the "complete learning unit" idea. All steps use the
opt-in demo data; nothing here touches real user data.

## 0. Before the demo (5 minutes, once)

```powershell
docker compose up -d postgres            # wait for (healthy): docker compose ps
.\mvnw compile exec:java@migrate         # schema up to date
.\mvnw compile exec:java@demo-data       # ONLY into an empty database — refuses otherwise
.\mvnw javafx:run
```

Demo schedules are generated **relative to the day you load them**: there are always completed, today's and
upcoming lectures, open and closed quizzes. Load the demo data on the day of the presentation (or the day before).

All demo passwords: **`Demo@1234`** (demonstration only; the database stores PBKDF2 hashes).

| Account | Role | Good for showing |
|---|---|---|
| `meera.iyer@college.edu` | Professor (CS301, CS305) | quizzes, attendance, feedback, note review, planner |
| `ravi.kumar@college.edu` | CR in CS301 (review notes, announcements, update lectures) | delegated permissions, review queue |
| `asha.rao@college.edu` | Student | everything a student sees; weak topic, low attendance in CS305, 5-day streak |
| `arjun.menon@college.edu` | Professor (CS310) | course isolation |

Tip: keep two ClassSync windows ready (`.\mvnw javafx:run` twice) — one professor, one student — for the live
attendance step.

## 1. Sign-in and the student dashboard (Asha) — 3 min

1. Login screen: show *Show password*, then sign in with the enrollment number **CS2024001** (email works too).
2. Dashboard, top to bottom:
   * greeting, enrolled courses, today's lectures, new materials;
   * **learning streak (5 days)**, badges, open study tasks;
   * announcements (one *Urgent*, one *Room change* posted by the CR) and the to-do card;
   * **attendance** per course — CS305 shows the red *Below 75%* warning;
   * **pending quiz** (Normalization) and **weak topics** (Joins: needs revision);
   * upcoming lectures with **preparation instructions** — the "before the lecture" part.
3. The bell shows unread notifications — open it, click one, it opens the related screen.

## 2. A lecture as a complete learning unit — 3 min

Open *Lecture Schedule* → a completed CS301 lecture (L3 Joins). Walk the tabs:
**Overview** (objectives, preparation) → **Materials** → **Quizzes** → **Attendance** (Asha's record) →
**Feedback** → **Notes** (approved notes with upvotes) → **Doubts**. Say: *before, during and after the lecture
in one place.*

## 3. Timed quiz (Asha) — 3 min

1. *Quizzes* → *Normalization quiz* → **Start**: read the rules dialog (time limit, autosave, one official attempt).
2. Answer two questions — point out the navigator turning blue and "All answers saved".
3. **Close the window** (or sign out), reopen, *Resume*: answers and remaining time are restored.
   Explain: the deadline lives in PostgreSQL, not in the countdown.
4. **Submit** → confirm → review page with correct answers and explanations (this quiz releases answers
   after submit). Toggle *only questions I got wrong*. Point at **Practice again** (never official).
5. *Revision* → *Questions to revise* lists the closed quiz's mistakes.

## 4. Live attendance (Professor + Asha) — 3 min

1. Meera: open today's lecture → *Attendance* → **Start attendance**. A large code with a countdown appears.
2. Asha (second window): *Attendance* page → type the code (lower case is fine) → **Mark me present**.
   Try a wrong code first: "That code is not correct" (5 wrong codes lock that session for the student).
3. Meera: the count rises within seconds. **Finalize attendance** → confirmation → everyone else becomes ABSENT.
4. **Correct** one record: a reason is required; the correction history shows who/when/why/before/after.
5. *Attendance* page → course report: awaiting finalization, per-lecture and per-student rates.

Say clearly: codes discourage casual misuse; they do not prove physical presence — that is why the
professor reviews and can correct.

## 5. Professor workflows (Meera) — 3 min

* *Quizzes* → *SQL and joins check* → **Results**: participation 6/7, average, highest, lowest,
  question-accuracy chart, most chosen wrong answers, class weak topics.
* *Feedback*: trend chart; open L3 → anonymous comments show "Anonymous student".
* *Notes* → review queue → open a note → **Request changes** (reason required) or **Approve**.
* *Semester Planner*: completion %, Completed/Scheduled/**Overdue** statuses derived from real lectures;
  reschedule a topic with **Plan**.
* *Announcements* → **New announcement** → every enrolled student gets one notification.

## 6. Delegation and privacy (Ravi, CR) — 2 min

* Ravi's *Notes* → **Review queue** contains Rohan's note but **not his own** (no self-approval).
* Ravi can publish announcements in CS301 but sees no quiz editor, no attendance controls, no feedback
  reports — those are professor-only even with every delegated permission.
* Optional: as Meera, remove Ravi's *Review notes* permission (course → Class Representatives); Ravi's next
  approve attempt is refused immediately (permissions are read fresh on every action).

## 7. Revision, performance, library, bookmarks (Asha) — 2 min

* *Performance*: quiz average, attendance, streak, weekly activity chart, badges (First Quiz, Five-Day Streak).
* *Resource Library*: everything published by course and unit; bookmark an item.
* *Bookmarks & Tasks*: the bookmark appears; tick a study task; recent activity.

## 7b. Optional: administrator (admin@college.edu) — 2 min

* *Dashboard*: account counts per type, **Add a new …** buttons, newest accounts, recent sign-ins, account changes.
* **Add user** → Professor → fill name/email/employee number/department → **Generate** → **Create account**; sign in
  as the new professor in a second window with that password.
* *User Management*: search "ravi" → **Make student** → confirm: Ravi's CR rights in CS301/CS305 are removed.
  Then **Make CR** again (rights must be re-granted by the professor — explain why).
* **Disable** a student → confirm → their sign-in fails with the usual message; **Enable** restores it.

## 8. Engineering points to mention (1 min)

* Java 21 + JavaFX 21, direct JDBC with `PreparedStatement`, PostgreSQL 17 in Docker, Maven, JUnit 5.
* 166 automated tests against a real PostgreSQL test database (never the real one); 26 checks that drive the real window and verify the database.
* Versioned migrations with checksums; the app uses a restricted database role.
* Optional: `docker compose --profile gui up` runs the same desktop app inside Docker (WSLg).

## If something goes wrong

| Symptom | Fix |
|---|---|
| "Cannot connect to the database" | Start Docker Desktop → `docker compose up -d postgres` → *Retry* |
| "Database schema needs an update" | `.\mvnw compile exec:java@migrate` |
| Demo data refuses to load | It only loads into an empty database. To start over, use a fresh volume only if it holds nothing you need (`docker compose down -v` destroys ALL data). |
| Quiz says "not open right now" | The demo was loaded more than two days ago; reload demo data on a fresh database |
