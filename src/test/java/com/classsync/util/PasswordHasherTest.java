package com.classsync.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(10_000);

    @Test
    void correctPasswordVerifies() {
        String stored = hasher.hash("S3cure!pass".toCharArray());
        assertTrue(hasher.verify("S3cure!pass".toCharArray(), stored));
    }

    @Test
    void wrongPasswordFails() {
        String stored = hasher.hash("S3cure!pass".toCharArray());
        assertFalse(hasher.verify("s3cure!pass".toCharArray(), stored));
    }

    @Test
    void samePasswordGetsDifferentSaltAndHash() {
        String a = hasher.hash("same".toCharArray());
        String b = hasher.hash("same".toCharArray());
        assertNotEquals(a, b);
    }

    @Test
    void storedFormatRecordsAlgorithmAndIterations() {
        String stored = hasher.hash("x".toCharArray());
        String[] parts = stored.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("pbkdf2_sha256", parts[0]);
        assertEquals("10000", parts[1]);
    }

    @Test
    void verifyUsesStoredIterationsNotCurrentDefault() {
        String stored = hasher.hash("pw".toCharArray());
        assertTrue(new PasswordHasher(20_000).verify("pw".toCharArray(), stored));
    }

    @Test
    void malformedHashIsRejectedNotThrown() {
        assertFalse(hasher.verify("pw".toCharArray(), "plain-text"));
        assertFalse(hasher.verify("pw".toCharArray(), "pbkdf2_sha256$abc$%%%$###"));
        assertFalse(hasher.verify("pw".toCharArray(), null));
    }

    @Test
    void defaultWorkFactorIsStrong() {
        assertTrue(PasswordHasher.DEFAULT_ITERATIONS >= 600_000);
    }
}
