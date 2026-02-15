package coen448.computablefuture.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.*;

/**
 * Tests for the Fail-Partial policy.
 * No Mockito -- uses real Microservice instances and a FailingMicroservice subclass.
 */
public class FailPartialTest {

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

    private final AsyncProcessor processor = new AsyncProcessor();

    // ---------------------------------------------------------------
    // Happy path: all services succeed
    // ---------------------------------------------------------------

    @Test
    void failPartial_allSucceed_returnsFullList()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice s1 = new Microservice("Alpha");
        Microservice s2 = new Microservice("Beta");
        Microservice s3 = new Microservice("Gamma");

        CompletableFuture<List<String>> result = processor.processAsyncFailPartial(
                List.of(s1, s2, s3),
                List.of("a", "b", "c"));

        List<String> output = result.get(1, TimeUnit.SECONDS);

        assertEquals(3, output.size());
        assertTrue(output.contains("Alpha:A"));
        assertTrue(output.contains("Beta:B"));
        assertTrue(output.contains("Gamma:C"));
    }

    // ---------------------------------------------------------------
    // Partial failure: one fails, others succeed
    // ---------------------------------------------------------------

    @Test
    void failPartial_oneServiceFails_returnsOnlySuccessfulResults()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice good1 = new Microservice("Good1");
        Microservice bad = new FailingMicroservice("Bad",
                new RuntimeException("connection refused"));
        Microservice good2 = new Microservice("Good2");

        CompletableFuture<List<String>> result = processor.processAsyncFailPartial(
                List.of(good1, bad, good2),
                List.of("x", "y", "z"));

        List<String> output = result.get(1, TimeUnit.SECONDS);

        // Only the two successful services should be in the result
        assertEquals(2, output.size());
        assertTrue(output.contains("Good1:X"));
        assertTrue(output.contains("Good2:Z"));
        // The failing service's result must NOT appear
        assertFalse(output.stream().anyMatch(s -> s.startsWith("Bad:")));
    }

    @Test
    void failPartial_oneServiceFails_noExceptionEscapes()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice good = new Microservice("OK");
        Microservice bad = new FailingMicroservice("FAIL",
                new RuntimeException("timeout"));

        CompletableFuture<List<String>> result = processor.processAsyncFailPartial(
                List.of(good, bad),
                List.of("msg", "msg"));

        // Must complete normally -- no exception thrown
        assertDoesNotThrow(() -> result.get(1, TimeUnit.SECONDS));
        assertFalse(result.isCompletedExceptionally());
    }

    // ---------------------------------------------------------------
    // All services fail -> empty list
    // ---------------------------------------------------------------

    @Test
    void failPartial_allServicesFail_returnsEmptyList()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice bad1 = new FailingMicroservice("F1",
                new RuntimeException("err1"));
        Microservice bad2 = new FailingMicroservice("F2",
                new RuntimeException("err2"));

        CompletableFuture<List<String>> result = processor.processAsyncFailPartial(
                List.of(bad1, bad2),
                List.of("a", "b"));

        List<String> output = result.get(1, TimeUnit.SECONDS);

        assertTrue(output.isEmpty(), "All services failed so the result list must be empty");
        assertFalse(result.isCompletedExceptionally());
    }

    // ---------------------------------------------------------------
    // Liveness: completes within timeout
    // ---------------------------------------------------------------

    @Test
    void failPartial_liveness_completesWithinTimeout() {

        Microservice s1 = new Microservice("S1");
        Microservice bad = new FailingMicroservice("BAD",
                new RuntimeException("down"));
        Microservice s2 = new Microservice("S2");

        CompletableFuture<List<String>> result = processor.processAsyncFailPartial(
                List.of(s1, bad, s2),
                List.of("a", "b", "c"));

        assertDoesNotThrow(() -> result.get(2, TimeUnit.SECONDS),
                "Fail-Partial should complete without deadlock");
    }
}
