package com.classsync.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Code generation and percentage rules (no database). */
class AttendanceRulesTest {

    @Test
    void codesUseTheReadableAlphabetAndVary() {
        AttendanceService service = new AttendanceService(null, null, 120);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = service.newCode();
            assertEquals(AttendanceService.CODE_LENGTH, code.length());
            assertTrue(code.chars().allMatch(c -> AttendanceService.ALPHABET.indexOf(c) >= 0), code);
            assertFalse(code.matches(".*[01OIL].*"), "no easily confused characters");
            seen.add(code);
        }
        assertTrue(seen.size() > 1990, "SecureRandom codes practically never repeat in 2000 draws");
    }

    @Test
    void hashIsDeterministicPerLectureAndHidesTheCode() {
        String h = AttendanceService.hash(7, "ABC234");
        assertEquals(h, AttendanceService.hash(7, "ABC234"));
        assertNotEquals(h, AttendanceService.hash(8, "ABC234"), "salted with the lecture id");
        assertEquals(64, h.length());
        assertFalse(h.contains("ABC234"));
    }

    @Test
    void percentagesNeverDivideByZero() {
        assertTrue(AttendanceService.fraction(0, 0).isEmpty());
        assertEquals(0.75, AttendanceService.fraction(3, 4).orElseThrow());
        assertFalse(AttendanceService.isLow(0, 0), "no data is not a warning");
        assertFalse(AttendanceService.isLow(3, 4), "exactly 75% is not low");
        assertTrue(AttendanceService.isLow(2, 3));
    }

    @Test
    void codeLifetimeIsBounded() {
        assertThrows(AppException.ValidationException.class, () -> new AttendanceService(null, null, 5));
        assertThrows(AppException.ValidationException.class, () -> new AttendanceService(null, null, 3600));
    }
}
