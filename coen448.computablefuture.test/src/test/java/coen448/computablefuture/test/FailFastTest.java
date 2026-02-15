package coen448.computablefuture.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.*;

/**
 * Tests for the Fail-Fast (Atomic) policy.
 * No Mockito -- uses real Microservice instances and a FailingMicroservice subclass.
 */
public class FailFastTest {

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
    void failFast_allSucceed_returnsJoinedResult()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice s1 = new Microservice("Alpha");
        Microservice s2 = new Microservice("Beta");

        CompletableFuture<String> result = processor.processAsyncFailFast(
                List.of(s1, s2),
                List.of("hello", "world"));

        String output = result.get(1, TimeUnit.SECONDS);

        // Each service returns serviceId:INPUT_UPPERCASED
        assertEquals("Alpha:HELLO Beta:WORLD", output);
    }

    @Test
    void failFast_singleService_succeeds()
            throws ExecutionException, InterruptedException, TimeoutException {

        Microservice s1 = new Microservice("Solo");

        CompletableFuture<String> result = processor.processAsyncFailFast(
                List.of(s1),
                List.of("ping"));

        String output = result.get(1, TimeUnit.SECONDS);
        assertEquals("Solo:PING", output);
    }

    // ---------------------------------------------------------------
    // Failure path: one service fails -> entire operation fails
    // ---------------------------------------------------------------

    @Test
    void failFast_oneServiceFails_exceptionPropagates() {

        Microservice good = new Microservice("Good");
        Microservice bad = new FailingMicroservice("Bad",
                new RuntimeException("service down"));

        CompletableFuture<String> result = processor.processAsyncFailFast(
                List.of(good, bad),
                List.of("msg1", "msg2"));

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> result.get(1, TimeUnit.SECONDS));

        // The cause must be the original RuntimeException
        assertInstanceOf(RuntimeException.class, ex.getCause());
        assertEquals("service down", ex.getCause().getMessage());
    }

    @Test
    void failFast_oneServiceFails_noPartialResult() {

        Microservice good = new Microservice("Good");
        Microservice bad = new FailingMicroservice("Bad",
                new RuntimeException("boom"));

        CompletableFuture<String> result = processor.processAsyncFailFast(
                List.of(good, bad),
                List.of("a", "b"));

        // Wait for completion
        assertThrows(ExecutionException.class,
                () -> result.get(1, TimeUnit.SECONDS));

        // The future completed exceptionally -- no normal result available
        assertTrue(result.isCompletedExceptionally());
    }

    // ---------------------------------------------------------------
    // Failure path: all services fail
    // ---------------------------------------------------------------

    @Test
    void failFast_allServicesFail_exceptionPropagates() {

        Microservice bad1 = new FailingMicroservice("Fail1",
                new RuntimeException("err1"));
        Microservice bad2 = new FailingMicroservice("Fail2",
                new RuntimeException("err2"));

        CompletableFuture<String> result = processor.processAsyncFailFast(
                List.of(bad1, bad2),
                List.of("x", "y"));

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> result.get(1, TimeUnit.SECONDS));

        assertInstanceOf(RuntimeException.class, ex.getCause());
    }

    // ---------------------------------------------------------------
    // Liveness: must complete within timeout (no deadlock)
    // ---------------------------------------------------------------

    @Test
    void failFast_liveness_completesWithinTimeout() {

        Microservice s1 = new Microservice("S1");
        Microservice s2 = new Microservice("S2");
        Microservice s3 = new Microservice("S3");

        CompletableFuture<String> result = processor.processAsyncFailFast(
                List.of(s1, s2, s3),
                List.of("a", "b", "c"));

        assertDoesNotThrow(() -> result.get(2, TimeUnit.SECONDS),
                "Fail-Fast should complete without deadlock");
    }

    @Test
    void failFast_liveness_failingServiceDoesNotBlock() {

        Microservice good = new Microservice("OK");
        Microservice bad = new FailingMicroservice("BAD",
                new RuntimeException("instant fail"));

        CompletableFuture<String> result = processor.processAsyncFailFast(
                List.of(good, bad),
                List.of("m1", "m2"));

        // Must finish (exceptionally) within timeout -- proves no deadlock
        assertThrows(ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
    }
}
