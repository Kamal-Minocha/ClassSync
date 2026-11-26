# ClassSync — Accounts, Passwords and Credentials

This document lists every kind of login and password in ClassSync, and the procedure to create, change,
reset and disable each kind of user account.

> **Real secrets are deliberately not written here.** The real database passwords live only in the `.env` file
> in the project folder (ignored by Git). This document tells you where each secret is and how to change it,
> so it can be shared or committed safely.

## 1. Three kinds of credentials

| Kind | Who uses it | Where it is stored | Example |
|---|---|---|---|
| **App accounts** (Student, CR, Professor, Administrator) | people signing in to the ClassSync window | `users` table — only a PBKDF2 hash of the password | `asha.rao@college.edu` |
| **Demo accounts** | presentations and testing | created by `demo-data`, same table | password `Demo@1234` |
| **Database roles** | the app, the tools, Docker | `.env` (git-ignored) | `classsync_app` |

## 2. Demo accounts (demonstration only)

Created only when you run `.\mvnw compile exec:java@demo-data` on an **empty** database.
All use the password **`Demo@1234`**. Students and CRs can also sign in with their enrollment number.

| Email | Enrollment | Role | Notes |
|---|---|---|---|
| `admin@college.edu` | — | Administrator | User Management dashboard (added in V007 — only in demo loads made after it) |
| `meera.iyer@college.edu` | — | Professor | teaches CS301, CS305 |
| `arjun.menon@college.edu` | — | Professor | teaches CS310 |
| `ravi.kumar@college.edu` | `CS2024002` | Class Representative | CR staff in CS301 (review notes, announcements, update lectures) and CS305 |
| `asha.rao@college.edu` | `CS2024001` | Student | best account for a student demo |
| `neha.sharma@college.edu` | `CS2024003` | Student | |
| `kabir.singh@college.edu` | `CS2024004` | Student | |
| `priya.nair@college.edu` | `CS2024005` | Student | |
| `rohan.das@college.edu` | `CS2024006` | Student | |
| `zoya.khan@college.edu` | `CS2024007` | Student | |

Do not use the demo accounts for real data: everyone who has read this document knows the password.

## 3. Rules every app account follows

| Item | Rule |
|---|---|
| Password | 8–128 characters, at least one letter and one digit |
| Email (self-registration) | must match `CLASSSYNC_EMAIL_PATTERN` (default `^[a-z0-9._-]+@college\.edu$`); this is a format check only, not proof of ownership |
| Enrollment number | 6–15 capital letters or digits (`CS2024001`); stored upper-case; signing in is case-insensitive |
| Uniqueness | one account per email (case-insensitive) and per enrollment number |
| Storage | `pbkdf2_sha256$600000$<salt>$<hash>` — the plain password is never stored, logged or recoverable |
| Roles | `STUDENT`, `CR`, `PROFESSOR`, `ADMIN`. Registration in the app can only create a Student; only an administrator (or the provisioning tool) can create the others |

## 4. Two ways to manage accounts

| | **Admin dashboard** (in the app) — recommended | **Provisioning tool** (command line) |
|---|---|---|
| Who | someone signed in with an **Administrator** account | someone with the project folder and its `.env` |
| Create Student / CR / Professor / Administrator | **Add user** | `student`, `cr`, `professor`, `admin` commands |
| Edit name, department, semester, designation | ✎ Edit | — |
| Student ↔ CR | **Make CR** / **Make student** | `set-role` |
| Reset a password | 🔑 Reset password | `reset-password` |
| Disable / enable | **Disable** / **Enable** | `set-active` |
| Needed for | everyday work | **creating the very first administrator**, or when no admin can sign in |

Both write the same audit log, and neither ever stores or logs a plain password.

### 4.1 Using the admin dashboard

1. Sign in with an Administrator account. The sidebar shows **Dashboard** and **User Management**.
2. **Dashboard**: counts of active Students, CRs, Professors, Administrators and disabled accounts (click a
   card to open that list), **Add a new …** buttons for each account type, the newest accounts, recent sign-ins
   and the latest account changes.
3. **User Management**: every account in one table. Search by name, email, enrollment or employee number,
   filter by account type, tick *Include disabled accounts* to see disabled ones.
4. **Add user**: choose the account type; the form shows only the fields that type needs (enrollment number,
   semester and department for Students/CRs; employee number, designation and department for Professors;
   just name and email for Administrators). Type a password or click **Generate** for a random 12-character one,
   then give it to the person privately.
5. Row actions: ✎ edit details · 🔑 set a new password · **Make CR / Make student** (asks for confirmation;
   making a CR a student also removes all their CR rights in every course) · **Disable / Enable** (asks for
   confirmation).

Safety rules enforced by the server (not just the screen): every action re-checks that the caller is still an
active administrator; an administrator cannot disable themselves; the last active administrator can never be
disabled, even if two administrators try to disable each other at the same moment; email, enrollment number
and employee number of an existing account cannot be changed; accounts are disabled, never deleted.

## 4.2 Before running any provisioning-tool command

Account commands use the provisioning tool, which connects with the **migration-owner** credentials from
`.env`. Ordinary users do not have these.

1. Start Docker Desktop and the database: `docker compose up -d postgres`
2. Open PowerShell in the project folder (`D:\ClassSync`).
3. Put the **new account's** password in an environment variable for this window (it is not saved anywhere
   and never appears on the command line or in logs):

   ```powershell
   $env:CLASSSYNC_PROVISION_PASSWORD = "Welcome#2026"
   ```

4. Run the command. Always put the **whole** `-Dexec.args=...` argument in double quotes and each value that
   contains spaces in single quotes (this exact form is the one tested in PowerShell).
5. When finished, remove the password from the window: `Remove-Item Env:CLASSSYNC_PROVISION_PASSWORD`

Tell the new user their password privately and ask them to keep it secret (there is no in-app
"change password" screen yet; an administrator can set a new one from the dashboard or with §5.6).

## 5. Procedures

Every procedure below that uses the provisioning tool can also be done from the admin dashboard (§4.1).

### 5.0 The first Administrator (once per database)

The admin dashboard needs an Administrator to sign in, so the first one is created with the tool:

```powershell
.\mvnw compile exec:java@migrate        # brings the database to the latest version (adds the ADMIN role)
$env:CLASSSYNC_PROVISION_PASSWORD = "<a strong password>"
.\mvnw compile exec:java@provision "-Dexec.args=admin 'Department Administrator' admin@college.edu"
Remove-Item Env:CLASSSYNC_PROVISION_PASSWORD
```

Arguments: `admin <full name> <email>`. Expected output: `Created ADMIN account id …`. Sign in with that email
and password; further administrators can then be added from **Add user → Administrator**. Keep at least two
administrators so one can reset the other's password.

### 5.1 New Student — option A: the student registers themselves

1. Start ClassSync (`.\mvnw javafx:run`).
2. On the login screen click **Create an account**.
3. Fill in full name, college email, enrollment number, semester, department, password (twice).
4. Click **Create account**. The account is always a **Student**; the student can sign in immediately.
5. A professor then enrols the student in courses (§5.5).

### 5.2 New Student — option B: created by the department

```powershell
$env:CLASSSYNC_PROVISION_PASSWORD = "Welcome#2026"
.\mvnw compile exec:java@provision "-Dexec.args=student 'Arjun Verma' arjun.verma@college.edu CS2024050 'Computer Science' 5"
```

Arguments: `student <full name> <email> <enrollment no> <department> <semester 1-12>`.
Expected output: `Created STUDENT account id …`.

### 5.3 New Professor (administrator only — never by self-registration)

```powershell
$env:CLASSSYNC_PROVISION_PASSWORD = "Welcome#2026"
.\mvnw compile exec:java@provision "-Dexec.args=professor 'Dr. Kavita Sharma' kavita.sharma@college.edu EMP205 'Computer Science' 'Assistant Professor'"
```

Arguments: `professor <full name> <email> <employee no> <department> [designation]` (designation defaults to
"Professor"). Expected output: `Created professor account id …`.

After signing in, the professor creates their courses (*My Courses* → **New course**), adds syllabus topics,
lectures and enrols students (§5.5).

### 5.4 New Class Representative (CR)

A CR is a student with extra, course-specific rights. Two steps: create/promote the account, then the course
professor delegates rights in that course.

**Step 1 — the account (department):** either create a new CR account …

```powershell
$env:CLASSSYNC_PROVISION_PASSWORD = "Welcome#2026"
.\mvnw compile exec:java@provision "-Dexec.args=cr 'Simran Kaur' simran.kaur@college.edu CS2024051 'Computer Science' 5"
```

… or promote an existing student (keeps their password, enrolments and history):

```powershell
.\mvnw compile exec:java@provision "-Dexec.args=set-role arjun.verma@college.edu CR"
```

To turn a CR back into a plain student: `"-Dexec.args=set-role arjun.verma@college.edu STUDENT"`.
`set-role` only switches between STUDENT and CR — it can never create a professor or administrator.
In the app: User Management → **Make CR** / **Make student** on the account's row.

**Step 2 — the course rights (the course's professor, in the app):**

1. The CR must be **enrolled** in the course (§5.5).
2. Open the course → **Class Representatives** tab.
3. Pick the CR in *Enrolled CR account* → **Add as CR staff**.
4. Tick the rights to delegate: *Update lecture information*, *Review student notes*, *Publish announcements*,
   *Publish and organise resources*, *Moderate discussions*. (Viewing the schedule incl. drafts and uploading
   materials are given automatically.)
5. Removing or un-ticking a right takes effect on the CR's very next action.

A CR can never: manage quizzes or marks, finalise or correct attendance, approve their own notes, change roles,
or give themselves rights.

### 5.5 Enrolling students and CRs in a course (professor, in the app)

1. Sign in as the course's professor → *My Courses* → open the course → **Students** tab.
2. Type the enrollment number (e.g. `CS2024050`) → **Enroll**.

### 5.6 Forgotten password — set a new one (department)

The old password cannot be recovered (only its hash exists); set a new one:

```powershell
$env:CLASSSYNC_PROVISION_PASSWORD = "NewPass#2026"
.\mvnw compile exec:java@provision "-Dexec.args=reset-password simran.kaur@college.edu"
```

Works for every role. Expected output: `Password reset. …`. The audit log records that a reset happened
(never the password).

### 5.7 Disable or re-enable an account (department)

```powershell
.\mvnw compile exec:java@provision "-Dexec.args=set-active arjun.verma@college.edu false"
.\mvnw compile exec:java@provision "-Dexec.args=set-active arjun.verma@college.edu true"
```

A disabled account cannot sign in, but all its academic records (attendance, quiz results, notes) are kept.
Prefer this over deleting accounts. In the app: User Management → **Disable** / **Enable** (tick *Include
disabled accounts* to find a disabled one).

### 5.8 Demo accounts in one step

```powershell
.\mvnw compile exec:java@migrate        # tables must exist
.\mvnw compile exec:java@demo-data      # only into an EMPTY database
```

## 6. Database credentials (`.env`)

| Variable | Role | Used by | Rights |
|---|---|---|---|
| `POSTGRES_PASSWORD` | `postgres` | Docker container administration only | superuser |
| `CLASSSYNC_MIGRATOR_USER` / `_PASSWORD` | `classsync_migrator` | `migrate`, `provision`, `demo-data`, tests | owns the schema |
| `CLASSSYNC_APP_DB_USER` / `_PASSWORD` | `classsync_app` | the ClassSync desktop app | read/write rows only; cannot change tables or rewrite audit/correction history |

**See them:** open `.env` in the project folder (`notepad .env`). Never paste them into chats, screenshots,
documents or Git.

**Important:** the values in `.env` are used to *create* the roles only when the Docker data volume is created
for the first time. Editing `.env` later does not change an existing database. To change a password:

```powershell
# 1) change it in PostgreSQL (example: the app role)
docker compose exec postgres psql -U postgres -c "ALTER ROLE classsync_app PASSWORD 'a-new-long-random-password'"
# 2) put the same value in .env (CLASSSYNC_APP_DB_PASSWORD=...) so the app and tools use it
```

Same for `classsync_migrator` (`CLASSSYNC_MIGRATOR_PASSWORD`) and `postgres` (`POSTGRES_PASSWORD`).

If the Docker volume is ever deleted and recreated (for example by `docker compose down -v`), the roles are
recreated from the **current** `.env`, the database is empty, and you must run `migrate` (and optionally
`demo-data`) again.

## 7. Troubleshooting

| Message | Cause and fix |
|---|---|
| `Password must be at least 8 characters` / `…one letter and one digit` | the password in `CLASSSYNC_PROVISION_PASSWORD` breaks the rules in §3 |
| `No console available: set CLASSSYNC_PROVISION_PASSWORD` | set the variable (§4.2 step 3) |
| `An account with this email or enrollment number already exists` | choose another email/enrollment, or use `set-role` / `reset-password` on the existing one |
| `No student/CR with that email.` (set-role) | wrong email, or the account is a professor |
| `Use your college email address` (registration) | email does not match `CLASSSYNC_EMAIL_PATTERN` |
| `Invalid email/enrollment number or password.` at sign-in | wrong password, or the account is disabled (§5.7) — the message is the same on purpose |
| `password authentication failed for user "classsync_…"` | `.env` and the database disagree — follow §6 |
| `relation "users" does not exist` | the database is empty — run `.\mvnw compile exec:java@migrate` |
| `Only an active administrator can manage user accounts.` | the signed-in account is not (or is no longer) an active Administrator |
| `This is the last active administrator and cannot be disabled.` | create or enable another Administrator first |
| No **User Management** in the sidebar / `admin` command unknown | database below version 7 or old build — run `migrate` and rebuild |

_All commands in §5 were run on 2026-11-26 against the disposable test database (with `CLASSSYNC_DB_URL`
pointed at `classsync_test`) and produced the expected accounts, role change, password reset and
deactivation. The admin dashboard was exercised in the real app the same day (screenshots 120–124 in
`docs/screenshots`): adding a professor with a generated password, resetting a password and disabling an
account were each confirmed by signing in._
