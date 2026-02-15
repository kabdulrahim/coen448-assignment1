package coen448.computablefuture.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.*;

/**
 * Tests for the Fail-Soft policy.
 * No Mockito -- uses real Microservice instances and a FailingMicroservice subclass.
 */
public class FailSoftTest {

    /**
     * A microservice that always completes exceptionally.
     */
    static class FailingMicroservice extends Microservice {
        private final RuntimeException exception;

        FailingMicroservice(String serviceId, RuntimeException exception) {
            super(serviceId);
            this.exception = exception;
        }

        @Override
        public CompletableFuture<String> retrieveAsync(String input) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static final String FALLBACK = "N/A";
    private final AsyncProcessor processor = new AsyncProcessor();

    // ---------------------------------------------------------------
    // Happy path: all services succeed -- fallback not used
    // ---------------------------------------------------------------

    @Test
    void failSoft_allSucceed_returnsNormalResult()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice s1 = new Microservice("Alpha");
        Microservice s2 = new Microservice("Beta");

        CompletableFuture<String> result = processor.processAsyncFailSoft(
                List.of(s1, s2),
                List.of("hello", "world"),
                FALLBACK);

        String output = result.get(1, TimeUnit.SECONDS);

        assertEquals("Alpha:HELLO Beta:WORLD", output);
        assertFalse(output.contains(FALLBACK));
    }

    // ---------------------------------------------------------------
    // One service fails -- fallback substituted
    // ---------------------------------------------------------------

    @Test
    void failSoft_oneServiceFails_fallbackUsed()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice good = new Microservice("Good");
        Microservice bad = new FailingMicroservice("Bad",
                new RuntimeException("service unavailable"));

        CompletableFuture<String> result = processor.processAsyncFailSoft(
                List.of(good, bad),
                List.of("msg", "msg"),
                FALLBACK);

        String output = result.get(1, TimeUnit.SECONDS);

        // Result should contain the successful service's output AND the fallback
        assertTrue(output.contains("Good:MSG"));
        assertTrue(output.contains(FALLBACK));
        assertEquals("Good:MSG N/A", output);
    }

    @Test
    void failSoft_oneServiceFails_noExceptionEscapes()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice good = new Microservice("OK");
        Microservice bad = new FailingMicroservice("FAIL",
                new RuntimeException("boom"));

        CompletableFuture<String> result = processor.processAsyncFailSoft(
                List.of(good, bad),
                List.of("a", "b"),
                FALLBACK);

        // Must complete normally
        assertDoesNotThrow(() -> result.get(1, TimeUnit.SECONDS));
        assertFalse(result.isCompletedExceptionally());
    }

    // ---------------------------------------------------------------
    // All services fail -- all fallback values
    // ---------------------------------------------------------------

    @Test
    void failSoft_allServicesFail_allFallbacks()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice bad1 = new FailingMicroservice("F1",
                new RuntimeException("err1"));
        Microservice bad2 = new FailingMicroservice("F2",
                new RuntimeException("err2"));
        Microservice bad3 = new FailingMicroservice("F3",
                new RuntimeException("err3"));

        CompletableFuture<String> result = processor.processAsyncFailSoft(
                List.of(bad1, bad2, bad3),
                List.of("x", "y", "z"),
                FALLBACK);

        String output = result.get(1, TimeUnit.SECONDS);

        // All three results should be the fallback
        assertEquals("N/A N/A N/A", output);
        assertFalse(result.isCompletedExceptionally());
    }

    // ---------------------------------------------------------------
    // Liveness: completes within timeout
    // ---------------------------------------------------------------

    @Test
    void failSoft_liveness_completesWithinTimeout() {

        Microservice s1 = new Microservice("S1");
        Microservice bad = new FailingMicroservice("BAD",
                new RuntimeException("down"));

        CompletableFuture<String> result = processor.processAsyncFailSoft(
                List.of(s1, bad),
                List.of("a", "b"),
                FALLBACK);

        assertDoesNotThrow(() -> result.get(2, TimeUnit.SECONDS),
                "Fail-Soft should complete without deadlock");
    }

    // ---------------------------------------------------------------
    // Nondeterminism: completion order varies (observed, not asserted)
    // ---------------------------------------------------------------

    @RepeatedTest(10)
    void failSoft_nondeterminism_completionOrderObserved()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice s1 = new Microservice("A");
        Microservice s2 = new Microservice("B");
        Microservice s3 = new Microservice("C");

        CompletableFuture<String> result = processor.processAsyncFailSoft(
                List.of(s1, s2, s3),
                List.of("msg", "msg", "msg"),
                FALLBACK);

        String output = result.get(1, TimeUnit.SECONDS);

        // Print to observe nondeterministic scheduling (not asserting order)
        System.out.println("FailSoft order: " + output);

        // Sanity: all three services present
        assertTrue(output.contains("A:MSG"));
        assertTrue(output.contains("B:MSG"));
        assertTrue(output.contains("C:MSG"));
    }
}
