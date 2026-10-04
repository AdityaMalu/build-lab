package testkit;

import java.util.Objects;

/** Minimal assertion helpers. Every failure throws {@link AssertionError}. */
public final class Assert {
    private Assert() {}

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) fail(message);
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, "expected true but was false");
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) fail(message);
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, "expected false but was true");
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            fail(message + " ==> expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, "values differ");
    }

    public static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            fail(message + " ==> expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        assertEquals(expected, actual, "values differ");
    }

    public static void assertNear(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            fail(message + " ==> expected <" + expected + "> +/- " + tolerance + " but was <" + actual + ">");
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            fail(message + " ==> did not expect <" + actual + ">");
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual) {
        assertNotEquals(unexpected, actual, "values should differ");
    }

    public static void assertNotNull(Object value) {
        assertNotNull(value, "unexpected null");
    }

    public static void assertNull(Object value, String message) {
        if (value != null) fail(message + " ==> expected null but was <" + value + ">");
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) fail(message + " ==> expected a value but was null");
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError(message + " ==> expected " + type.getSimpleName()
                    + " but got " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
        throw new AssertionError(message + " ==> expected " + type.getSimpleName() + " but nothing was thrown");
    }

    public static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable action) {
        return assertThrows(type, action, "wrong exception");
    }
}
