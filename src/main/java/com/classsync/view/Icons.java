package com.classsync.view;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Outline icons drawn for ClassSync on a 24x24 grid as SVG path data.
 * They are original simple shapes (no third-party icon font), coloured by the .icon CSS class.
 */
public final class Icons {

    public static final String DASHBOARD = "M3 3h7v8H3z M14 3h7v5h-7z M14 12h7v9h-7z M3 15h7v6H3z";
    public static final String COURSES = "M4 5a2 2 0 0 1 2-2h13v15H6a2 2 0 0 0-2 2z M4 20a2 2 0 0 0 2 2h13v-4 M8 7h7";
    public static final String CALENDAR = "M4 5h16v16H4z M4 10h16 M8 3v4 M16 3v4 M8 14h3";
    public static final String FILE = "M6 2h9l5 5v15H6z M14 2v6h6 M9 13h6 M9 17h4";
    public static final String QUIZ = "M4 4h16v16H4z M8 12l3 3 5-6";
    public static final String ATTENDANCE = "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M2 21v-1a6 6 0 0 1 11-3 M15 17l2.5 2.5L22 15";
    public static final String STAR = "M12 2.5l2.9 6 6.6.9-4.8 4.6 1.2 6.5L12 17.4l-5.9 3.1 1.2-6.5L2.5 9.4l6.6-.9z";
    public static final String NOTE = "M4 20h4L19 9l-4-4L4 16z M13.5 6.5l4 4";
    public static final String CHAT = "M21 14a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z M7 8h10 M7 12h6";
    public static final String LIBRARY = "M12 2L2 7l10 5 10-5z M2 17l10 5 10-5 M2 12l10 5 10-5";
    public static final String MEGAPHONE = "M3 10v4h3l7 5V5L6 10z M17 9a4 4 0 0 1 0 6 M19.5 6.5a8 8 0 0 1 0 11";
    public static final String BELL = "M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9 M13.7 21a2 2 0 0 1-3.4 0";
    public static final String SETTINGS = "M4 21v-7 M4 10V3 M12 21v-9 M12 8V3 M20 21v-5 M20 12V3 M1 14h6 M9 8h6 M17 16h6";
    public static final String SEARCH = "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z M21 21l-5-5";
    public static final String MOON = "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z";
    public static final String SUN = "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4";
    public static final String LOGOUT = "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4 M16 17l5-5-5-5 M21 12H9";
    public static final String MENU = "M3 6h18 M3 12h18 M3 18h18";
    public static final String CHART = "M3 3v18h18 M7 15l4-4 3 3 6-6";
    public static final String REVISION = "M3 4v6h6 M3.5 15a9 9 0 1 0 2.1-9.4L3 10";
    public static final String PLANNER = "M9 2h6v4H9z M8 4H5v18h14V4h-3 M8 12h8 M8 16h5";
    public static final String BOOKMARK = "M6 3h12v18l-6-4-6 4z";
    public static final String DOWNLOAD = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5 M12 15V3";
    public static final String UPLOAD = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M17 8l-5-5-5 5 M12 3v12";
    public static final String PLUS = "M12 5v14 M5 12h14";
    public static final String EYE = "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z";
    public static final String EYE_OFF = "M3 3l18 18 M10.6 10.6a2 2 0 0 0 2.8 2.8 M9.9 5.1A10 10 0 0 1 12 5c7 0 11 7 11 7a18 18 0 0 1-3.2 4 M6.6 6.6A18 18 0 0 0 1 12s4 7 11 7a10 10 0 0 0 5.4-1.6";
    public static final String CLOCK = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 6v6l4 2";
    public static final String USERS = "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M1 21v-1a6 6 0 0 1 12 0v1 M16 3.5a4 4 0 0 1 0 7 M19 14.5a6 6 0 0 1 4 5.5v1";
    public static final String TARGET = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 18a6 6 0 1 0 0-12 6 6 0 0 0 0 12z M12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z";
    public static final String BOOK_OPEN = "M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2z M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z";
    public static final String CHECK = "M20 6L9 17l-5-5";
    public static final String X = "M18 6L6 18 M6 6l12 12";
    public static final String ALERT = "M12 3l10 18H2z M12 10v5 M12 18h.01";
    public static final String THUMB_UP = "M7 22V11 M2 13v7a2 2 0 0 0 2 2h13.4a2 2 0 0 0 2-1.7l1.4-9A2 2 0 0 0 18.8 9H14V5a3 3 0 0 0-3-3l-4 9";
    public static final String CHEVRON_LEFT = "M15 18l-6-6 6-6";
    public static final String CHEVRON_RIGHT = "M9 18l6-6-6-6";
    public static final String FLAME = "M12 22c4.4 0 7-2.9 7-7 0-4.5-3.5-7-4-11-2.5 2-4 4.5-4 7-1.5-1-2-2.5-2-4-2 2-4 5-4 8 0 4.1 2.6 7 7 7z";
    public static final String TROPHY = "M8 21h8 M12 17v4 M7 4h10v5a5 5 0 0 1-10 0z M17 5h3v2a3 3 0 0 1-3 3 M7 5H4v2a3 3 0 0 0 3 3";
    public static final String REFRESH = "M21 4v6h-6 M3 20v-6h6 M5.6 9a7.5 7.5 0 0 1 12.5-2.6L21 10 M3 14l2.9 3.6A7.5 7.5 0 0 0 18.4 15";
    public static final String KEY = "M15 7a4 4 0 1 1-3.9 5H3v3h3v3h3v-3h2.1A4 4 0 0 1 15 7z";
    public static final String DATABASE = "M12 8c4.4 0 8-1.3 8-3s-3.6-3-8-3-8 1.3-8 3 3.6 3 8 3z M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5 M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3";

    private Icons() {
    }

    /** Creates an icon node at the given pixel size (the drawing grid is 24x24). */
    public static Node of(String pathData, double size) {
        SVGPath path = new SVGPath();
        path.setContent(pathData);
        path.getStyleClass().add("icon");
        double scale = size / 24.0;
        path.setScaleX(scale);
        path.setScaleY(scale);
        // a Group's size includes the scale transform; the StackPane centres it in a fixed box
        StackPane box = new StackPane(new Group(path));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.getStyleClass().add("icon-box");
        return box;
    }

    public static Node of(String pathData) {
        return of(pathData, 18);
    }
}
