# Failure Semantics for Concurrent Microservice Invocations

## 1. Overview

When multiple microservices execute concurrently, the system must define an explicit
exception-handling policy that determines what happens if one or more of them fail.
This document describes three policies implemented in `AsyncProcessor`:

| Policy | Return type | On failure | Exception escapes? |
|---|---|---|---|
| **Fail-Fast** | `CompletableFuture<String>` | Entire operation fails | Yes |
| **Fail-Partial** | `CompletableFuture<List<String>>` | Only successful results returned | No |
| **Fail-Soft** | `CompletableFuture<String>` | Fallback value substituted | No |

---

## 2. Policy Descriptions

### 2.1 Fail-Fast (Atomic Policy)

**Behavior:** All services are invoked concurrently via `CompletableFuture.allOf`. If any
single service completes exceptionally, the aggregate future completes exceptionally. No
partial result is produced -- the caller receives the exception.

**Key API:** `CompletableFuture.allOf(...)` naturally propagates failures. When any child
future fails, `allOf` completes exceptionally, and the downstream `thenApply` stage is
skipped.

**Analogy:** A database transaction -- either all operations succeed and commit, or the
entire transaction rolls back.

### 2.2 Fail-Partial

**Behavior:** Each service is invoked concurrently. Per-service exceptions are caught using
`.handle((result, ex) -> ...)`. Successful results are collected into a list; failed
services are excluded. The aggregate future always completes normally, even if all services
fail (returning an empty list).

**Key API:** `CompletableFuture.handle()` converts exceptions into `null` markers, which are
then filtered out during result aggregation.

**Analogy:** A search engine that queries multiple indices -- if one index is down, results
from the remaining indices are still displayed to the user.

### 2.3 Fail-Soft

**Behavior:** Each service is invoked concurrently. If a service fails, its result is
replaced with a caller-supplied fallback value using `.exceptionally(ex -> fallbackValue)`.
The aggregate future always completes normally and always produces a result string of the
same length (same number of tokens) as the input.

**Key API:** `CompletableFuture.exceptionally()` provides a recovery path that substitutes
the fallback value whenever the upstream stage completes exceptionally.

**Analogy:** A dashboard displaying metrics from multiple sources -- if one source is
unavailable, the dashboard shows "N/A" rather than crashing or showing a blank panel.

---

## 3. When to Use Each Policy

### Fail-Fast

Use when **correctness is critical** and partial results would be misleading or dangerous.

**Examples:**
- **Financial transactions**: A payment involving multiple banks must either fully succeed or
  fully fail. Transferring money from one account without crediting another would be
  catastrophic.
- **Distributed writes**: Writing data to multiple replicas where consistency requires all
  replicas to acknowledge. A partial write would leave the system in an inconsistent state.
- **CI/CD pipelines**: If any build step fails, the entire pipeline should abort rather than
  deploy partially-built artifacts.

### Fail-Partial

Use when **some results are better than none** and the caller can tolerate incomplete data.

**Examples:**
- **Search aggregation**: Querying multiple search providers and merging results. If one
  provider is slow or down, returning results from the others is still valuable.
- **Health checks**: Monitoring multiple services where knowing the status of available
  services is useful even if some are unreachable.
- **Recommendation engines**: Aggregating recommendations from multiple models. Fewer
  recommendations are acceptable; no recommendations at all would be a poor user experience.

### Fail-Soft

Use when **availability is paramount** and the system must always produce output, even at the
cost of accuracy.

**Examples:**
- **UI defaults**: A settings page that loads preferences from multiple services. If one
  service is down, showing a default value is better than an error page.
- **Caching layers**: When a cache-aside read fails, returning a stale or default value keeps
  the application responsive.
- **Monitoring dashboards**: Displaying "N/A" for an unavailable metric is preferable to
  crashing the entire dashboard.

---

## 4. Risks of Hiding Failures in Concurrent Systems

Both Fail-Partial and Fail-Soft intentionally suppress exceptions. While this improves
availability, it introduces risks that must be carefully managed:

### 4.1 Silent Data Corruption

When failures are masked with fallback values, downstream consumers cannot distinguish
between genuine results and substituted defaults. For example, if a pricing service returns a
fallback of "$0.00", an order-processing system might charge customers nothing -- silently
losing revenue without any alert.

### 4.2 Delayed Incident Detection

If exceptions never propagate to the caller, monitoring systems may not detect that a service
is down. The system appears healthy (no errors in logs, no alerts triggered) while silently
serving degraded or incorrect data. By the time the issue is discovered, significant damage
may have accumulated.

### 4.3 Cascading Misconfigurations

Fallback values can mask configuration errors. A misconfigured service URL might cause every
call to fail, but Fail-Soft would substitute defaults indefinitely. The root cause -- a
single typo in a configuration file -- could go unnoticed for days or weeks.

### 4.4 Loss of Observability

Exception stack traces carry diagnostic information (which service failed, why, and from
which thread). When exceptions are swallowed by `handle()` or `exceptionally()`, this
diagnostic information is lost unless explicitly logged. Without structured logging inside the
exception handler, debugging production issues becomes extremely difficult.

### 4.5 Mitigation Strategies

To mitigate these risks while still benefiting from fault-tolerant policies:

1. **Log every suppressed exception** -- Even if the exception does not propagate, log it at
   WARNING level with full context (service ID, input, timestamp).
2. **Emit metrics** -- Count fallback activations per service. Alert when the rate exceeds a
   threshold.
3. **Use circuit breakers** -- Combine Fail-Soft with a circuit breaker pattern so that
   persistent failures trigger alerts and eventually reject requests rather than silently
   serving stale data.
4. **Mark fallback values explicitly** -- Use sentinel values or wrapper types that
   downstream consumers can check, rather than indistinguishable defaults.
5. **Time-bound fallback policies** -- Allow fallback behavior only for a limited window
   (e.g., 5 minutes), after which the system escalates to Fail-Fast.
