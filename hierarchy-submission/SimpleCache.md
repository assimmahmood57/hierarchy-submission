# SimpleCache code review

The map operations are thread-safe, but the implementation is not ready for
production as a general-purpose TTL cache. The main blockers are unbounded
retention and use of wall-clock time for expiration. The workload alone does
not justify adding a global lock: that would serialize the read-heavy path
without addressing either blocker.

## 1. Expired entries are retained indefinitely — high severity

`get()` returns `null` for an expired entry but never removes it. No other
method removes entries either. Consequently, a key and its value remain
strongly referenced until that same key is overwritten or the entire cache
becomes unreachable. Reading an expired entry repeatedly does not help.

With a continuing stream of distinct keys, memory grows for the lifetime of
the process, even though most entries have expired. The consequences include
increased GC work, latency spikes, and eventually an out-of-memory failure.
At 100 distinct new keys per second, the map retains 8.64 million entries per
day. Actual bytes depend on keys, values, and JVM object overhead.

**Required change:** implement reclamation that also covers keys never read
again. Lazy eviction on reads can supplement scheduled or amortized cleanup,
but cannot replace it. If adding removal after an expired read, use
`cache.remove(key, entry)` rather than `cache.remove(key)`: a writer may have
replaced the expired entry between the read and removal. Unconditional removal
could then delete a fresh value. Apply the same conditional-removal rule to
background cleanup. The current code has no removal race; this is a constraint
on its fix. Conditional removal compares by equality, so the observed entry
must identify the entry generation adequately for the chosen design. [1]

## 2. There is no capacity or weight bound — high severity for unbounded keys

Reclaiming expired entries does not limit the number or total weight of live
entries. A burst of unique keys, large values, or delayed cleanup can still
exhaust the heap before the one-minute TTL elapses. A bounded key universe and
small values could make this acceptable, but neither is specified here.

**Required change:** set a capacity or weight budget based on heap headroom and
expected value sizes, and define an eviction policy. A separate `size()` check
followed by `put()` is not an atomic capacity limit; concurrent writers can
all pass the check. Prefer a mature cache implementation that coordinates
eviction with writes. Configure both expiration and capacity. [3]

## 3. Wall-clock changes break elapsed-time expiration — high severity

`System.currentTimeMillis()` measures wall time. If the clock moves backward,
an entry can appear younger, including having a negative age, and remain
readable longer than a minute. A forward jump can expire entries prematurely.
This can serve stale data or cause a burst of misses and backend load.

**Required change:** measure elapsed time with `System.nanoTime()` and compare
`nowNanos - writtenAtNanos` with a TTL expressed in nanoseconds. Compare the
difference rather than adding a deadline, to handle counter wraparound for
ordinary TTL intervals. `nanoTime()` is for elapsed durations within one JVM;
its origin must not be persisted or compared between processes. Inject a
ticker so boundary and clock scenarios can be tested without sleeping. [2]

## 4. `size()` does not describe usable cache entries — API correctness

Even with no concurrent traffic, the result includes every expired mapping.
For example, put one item, wait beyond the TTL, and `get()` returns `null`
while `size()` still returns `1`. Monitoring may substantially overstate useful
occupancy. During concurrent updates, the map's aggregate count also describes
a transient state; callers must not treat it as a stable admission check. [1]

**Required change:** document the metric as an approximate retained-entry
count, or explicitly design a live-entry metric. Cleanup reduces stale counts
but does not make counting and a later operation atomic. Exact live counts
need a stronger consistency contract and coordination, which has a cost.

## 5. Null and object-ownership contracts are unclear

The unconstrained Kotlin type parameters allow nullable key and value types.
The underlying map rejects a null key at runtime. A null value, however, is
wrapped in a non-null `CacheEntry`, so it can be stored; `get()` then returns
the same `null` for a cached null, an absent key, and an expired entry. [1]

**Required change:** if nulls are not supported, use `K : Any, V : Any`. If
cached nulls are intentional, provide a result type that distinguishes a hit
from a miss. Document that keys need stable equality/hash codes while stored.

The entry's `val` fields do not make an arbitrary `V` immutable. Callers can
mutate the object passed to `put()` or obtained from `get()`. Concurrent mutation
then needs its own synchronization; the map does not make the value object's
internals safe. Require immutable/thread-safe values, defensive copies, or an
explicit caller-ownership policy. This is a contract requirement, not evidence
that every possible use of this class has a data race.

## Concurrency behaviour to specify, rather than incorrectly call a defect

- A read overlapping a write can return the previously observed value or a
  miss for that observed entry. The map safely publishes entries; the code
  does not promise a transaction spanning lookup, time check, and return. [1]
- A thread can pass the TTL check and be paused before returning. Thus the
  implementation checks freshness at validation time; it cannot promise that
  the value remains fresh when the caller later uses it.
- The timestamp is sampled before publication. If a writer stalls, the entry
  has already consumed some TTL when published, possibly all of it. Specify
  whether TTL begins at timestamp sampling or at the write's publication.
- Overlapping puts are resolved by their map update order. Invocation order
  and timestamp order are not a business-version conflict policy. If callers
  need newer source data to win, introduce explicit versions and an atomic
  per-key update policy.

Global synchronization is not a general solution to these contract questions.
Agree on the required behaviour before introducing stronger coordination.

## Recommended delivery and validation

Use a maintained concurrent cache, such as Caffeine, with a measured
`maximumSize` or `maximumWeight`, expire-after-write duration, and injectable
time source. Ensure maintenance reclaims expired entries during idle periods
if that is an operational requirement; timed eviction does not automatically
imply removal at the exact deadline. [3] If retaining a custom implementation,
design and test cleanup, bounds, and lifecycle management together.

Before release, cover TTL just before/at/after expiration, overwrite resetting
TTL, deterministic expired-read/fresh-write cleanup interleavings, untouched
expired entries, capacity pressure, and the chosen null policy. Use latches or
barriers for concurrency tests rather than timing sleeps. Load-test realistic
key cardinalities and value sizes with tens of threads; observe heap use, GC,
hit/miss rates, evictions, and tail latency. The stated operations per second
alone establish neither a performance problem nor adequate capacity.

The fixed `60000` constant is a configuration/testability limitation, not a
concurrency bug. Make TTL and capacity configurable and reject invalid values.
Adding backend loading would also require a policy for concurrent misses;
request coalescing is not a missing feature of the submitted put/get-only API.

## References

1. [JDK 17 ConcurrentHashMap](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html)
   — publication, aggregate methods, conditional removal, and null handling.
2. [JDK 17 System](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#nanoTime())
   — elapsed-time measurement and subtraction-based timeout comparison.
3. [Caffeine eviction documentation](https://github.com/ben-manes/caffeine/wiki/Eviction)
   — size/weight bounds, timed eviction, maintenance, scheduling, and testing.
