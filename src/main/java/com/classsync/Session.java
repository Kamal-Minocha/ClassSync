package com.classsync;

import com.classsync.model.User;
import com.classsync.service.AppException.AccessDeniedException;

/**
 * The in-memory authenticated session for this desktop client.
 *
 * The generation number increases on every login and logout. Background tasks remember
 * the generation they started in and discard their result if it changed, so a slow
 * query started by user A can never update the screen of user B.
 */
public class Session {

    private volatile User user;
    private volatile long generation;

    public synchronized void login(User signedIn) {
        user = signedIn;
        generation++;
    }

    public synchronized void logout() {
        user = null;
        generation++;
    }

    public synchronized void replaceUser(User updated) {
        if (user != null && user.id() == updated.id()) {
            user = updated;
        }
    }

    public User user() {
        return user;
    }

    public User requireUser() {
        User u = user;
        if (u == null) {
            throw new AccessDeniedException("Please sign in first.");
        }
        return u;
    }

    public boolean isSignedIn() {
        return user != null;
    }

    public long generation() {
        return generation;
    }
}
