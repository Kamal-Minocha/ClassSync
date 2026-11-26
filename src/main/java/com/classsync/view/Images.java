package com.classsync.view;

import javafx.scene.image.Image;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bundled images (src/main/resources/images): the Thapar Institute logo mark and campus photographs used as
 * backgrounds. Loaded once and cached.
 */
public final class Images {

    public static final String LOGO_MARK = "thapar-mark.png";
    public static final String LOGO_FULL = "thapar-logo.jpg";
    public static final String CAMPUS_FACADE = "campus-facade.jpg";
    public static final String CAMPUS_COURTYARD = "campus-courtyard.jpg";
    public static final String CAMPUS_MAIN = "campus-main-building.jpg";

    /** Photos rotated across course banners. */
    public static final List<String> CAMPUS = List.of(CAMPUS_COURTYARD, CAMPUS_FACADE, CAMPUS_MAIN);

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private Images() {
    }

    public static Image get(String name) {
        return CACHE.computeIfAbsent(name, n -> new Image(Objects.requireNonNull(
                Images.class.getResource("/images/" + n), "missing image " + n).toExternalForm(), true));
    }

    /** A stable campus photo for a course (same course → same photo). */
    public static String forCourse(long courseId) {
        return CAMPUS.get((int) Math.floorMod(courseId, (long) CAMPUS.size()));
    }
}
