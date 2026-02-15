package coen448.computablefuture.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class AsyncProcessor {
	
    public CompletableFuture<String> processAsync(List<Microservice> microservices, String message) {
    	
        List<CompletableFuture<String>> futures = microservices.stream()
            .map(client -> client.retrieveAsync(message))
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.joining(" ")));
        
    }
    
    /**
     * Fail-Fast (Atomic Policy): If any concurrent microservice invocation fails,
     * the entire operation fails and no result is produced.
     *
     * All services are invoked concurrently. If one service completes exceptionally,
     * the aggregate Future completes exceptionally. No partial result is returned.
     * The exception propagates to the caller.
     *
     * @param services  the list of microservices to invoke concurrently
     * @param messages  the list of messages, one per service (paired by index)
     * @return a CompletableFuture that completes with the joined results, or
     *         completes exceptionally if any service fails
     */
    public CompletableFuture<String> processAsyncFailFast(
            List<Microservice> services, List<String> messages) {

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < services.size(); i++) {
            futures.add(services.get(i).retrieveAsync(messages.get(i)));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.joining(" ")));
    }

    /**
     * Fail-Partial Policy: Handles failures per service.
     * Returns only the successful results; failed services are silently excluded.
     * No exception escapes to the caller -- the aggregate Future always completes normally.
     *
     * @param services  the list of microservices to invoke concurrently
     * @param messages  the list of messages, one per service (paired by index)
     * @return a CompletableFuture that always completes normally with a list of
     *         successful results (may be empty if all services fail)
     */
    public CompletableFuture<List<String>> processAsyncFailPartial(
            List<Microservice> services, List<String> messages) {

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < services.size(); i++) {
            futures.add(services.get(i).retrieveAsync(messages.get(i)));
        }

        // Handle each future individually: successful results kept, failures become null
        List<CompletableFuture<String>> handled = futures.stream()
            .map(f -> f.handle((result, ex) -> {
                if (ex != null) {
                    return null;  // mark failure as null
                }
                return result;
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(handled.toArray(new CompletableFuture[0]))
            .thenApply(v -> handled.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)   // exclude failed services
                .collect(Collectors.toList()));
    }

    /**
     * Fail-Soft Policy: Uses fallback values for failures.
     * The aggregate Future always completes normally. If a service fails, its result
     * is replaced with the provided fallback value.
     *
     * <p><b>Warning:</b> This policy masks failures. The caller cannot distinguish
     * between a genuine result and a fallback. In production systems this can hide
     * outages, cause silent data corruption, or delay incident detection. Use only
     * when availability is more important than accuracy (e.g. UI defaults,
     * non-critical metrics).</p>
     *
     * @param services      the list of microservices to invoke concurrently
     * @param messages      the list of messages, one per service (paired by index)
     * @param fallbackValue the value to substitute when a service fails
     * @return a CompletableFuture that always completes normally with all results
     *         joined by spaces (fallbacks in place of failures)
     */
    public CompletableFuture<String> processAsyncFailSoft(
            List<Microservice> services, List<String> messages,
            String fallbackValue) {

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < services.size(); i++) {
            futures.add(
                services.get(i).retrieveAsync(messages.get(i))
                    .exceptionally(ex -> fallbackValue)
            );
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.joining(" ")));
    }

    public CompletableFuture<List<String>> processAsyncCompletionOrder(
            List<Microservice> microservices, String message) {

        List<String> completionOrder =
            Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> futures = microservices.stream()
            .map(ms -> ms.retrieveAsync(message)
                .thenAccept(completionOrder::add))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> completionOrder);
        
    }
    
}