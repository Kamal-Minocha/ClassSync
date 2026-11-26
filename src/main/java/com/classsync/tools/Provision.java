package com.classsync.tools;

import com.classsync.config.AppConfig;
import com.classsync.config.Database;
import com.classsync.model.Role;
import com.classsync.service.AuthService;
import com.classsync.util.PasswordHasher;

import java.io.Console;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Local account provisioning for department staff. This is the ONLY way to create
 * Professor and CR accounts or to change a role; the desktop UI can only register Students.
 * It connects with the migration-owner credentials, which ordinary users do not have.
 *
 *   mvnw compile exec:java@provision -Dexec.args="professor 'Dr. Meera Iyer' meera@college.edu EMP101 'Computer Science'"
 *   mvnw compile exec:java@provision -Dexec.args="cr 'Ravi Kumar' ravi@college.edu CS2024002 'Computer Science' 3"
 *   mvnw compile exec:java@provision -Dexec.args="admin 'Department Admin' admin@college.edu"
 *   mvnw compile exec:java@provision -Dexec.args="set-role ravi@college.edu CR"      (STUDENT <-> CR only)
 *   mvnw compile exec:java@provision -Dexec.args="reset-password ravi@college.edu"
 *   mvnw compile exec:java@provision -Dexec.args="set-active ravi@college.edu false"
 *
 * The password is read from the console without echo, or from CLASSSYNC_PROVISION_PASSWORD
 * when no console is available (e.g. under Maven). It is never passed on the command line.
 */
public final class Provision {

    private Provision() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        AppConfig config = AppConfig.load();
        Database owner = new Database(config.require(AppConfig.DB_URL),
                config.require(AppConfig.MIGRATOR_USER), config.require(AppConfig.MIGRATOR_PASSWORD));
        AuthService auth = new AuthService(owner, new PasswordHasher(), config.emailPattern());

        switch (args[0]) {
            case "professor" -> {
                require(args, 5);
                long id = auth.provisionProfessor(args[1], args[2], args[3], args[4],
                        args.length > 5 ? args[5] : "Professor", password());
                System.out.println("Created professor account id " + id);
            }
            case "admin" -> {
                require(args, 3);
                long id = auth.provisionAdmin(args[1], args[2], password());
                System.out.println("Created administrator account id " + id);
            }
            case "student", "cr" -> {
                require(args, 6);
                Role role = args[0].equals("cr") ? Role.CR : Role.STUDENT;
                long id = auth.provisionLearner(role, args[1], args[2], args[3], args[4],
                        Integer.parseInt(args[5]), password());
                System.out.println("Created " + role + " account id " + id);
            }
            case "set-role" -> {
                require(args, 3);
                Role role = Role.valueOf(args[2]);
                if (!role.isLearner()) {
                    throw new IllegalArgumentException("set-role only switches between STUDENT and CR");
                }
                try (Connection con = owner.open(); PreparedStatement ps = con.prepareStatement(
                        "UPDATE users SET role = ? WHERE lower(email) = lower(?) AND role IN ('STUDENT','CR')")) {
                    ps.setString(1, role.name());
                    ps.setString(2, args[1]);
                    System.out.println(ps.executeUpdate() == 1 ? "Role updated." : "No student/CR with that email.");
                }
            }
            case "reset-password" -> {
                require(args, 2);
                auth.resetPassword(args[1], password());
                System.out.println("Password reset. The user can sign in with the new password now.");
            }
            case "set-active" -> {
                require(args, 3);
                boolean active = Boolean.parseBoolean(args[2]);
                auth.setActive(args[1], active);
                System.out.println(active ? "Account activated." : "Account deactivated (records kept; sign-in blocked).");
            }
            default -> usage();
        }
    }

    /**
     * The password comes from CLASSSYNC_PROVISION_PASSWORD when it is set (recommended under Maven), otherwise
     * from a hidden console prompt. The variable is checked FIRST: since JDK 22, System.console() returns a
     * console even when nobody can type into it, so trying the console first would read an empty password.
     */
    private static char[] password() {
        String env = System.getenv("CLASSSYNC_PROVISION_PASSWORD");
        if (env != null && !env.isBlank()) {
            return env.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("No console available: set CLASSSYNC_PROVISION_PASSWORD for this command.");
        }
        char[] p = console.readPassword("Password for the new account: ");
        char[] again = console.readPassword("Repeat password: ");
        if (p == null || p.length == 0) {
            throw new IllegalStateException("No password entered. Set CLASSSYNC_PROVISION_PASSWORD and run the command again.");
        }
        if (!java.util.Arrays.equals(p, again)) {
            throw new IllegalArgumentException("Passwords do not match");
        }
        return p;
    }

    private static void require(String[] args, int n) {
        if (args.length < n) {
            usage();
            System.exit(2);
        }
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  admin     <full name> <email>
                  professor <full name> <email> <employee no> <department> [designation]
                  student   <full name> <email> <enrollment no> <department> <semester>
                  cr        <full name> <email> <enrollment no> <department> <semester>
                  set-role  <email> STUDENT|CR
                  reset-password <email>
                  set-active <email> true|false""");
    }
}
