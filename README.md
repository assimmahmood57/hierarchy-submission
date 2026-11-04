# Hierarchy filtering and cache review

## Run locally

Install JDK 17 and make it available through `JAVA_HOME` or `PATH`.
The checked-in wrapper downloads Gradle 8.14.3; the build uses Kotlin 2.2.21
and JUnit 5. Internet access is needed on the first run.

```sh
./gradlew test
```

On Windows, use `gradlew.bat test`. The HTML test report is written to
`build/reports/tests/test/index.html`.

## Contents

- `src/main/kotlin/Hierarchy.kt`: supplied interface and array implementation,
  plus the filter implementation.
- `src/test/kotlin/FilterTest.kt`: 17 tests, including the original example,
  boundary cases, deep trees, exhaustive predicates, and randomized checks.
- `SimpleCache.md`: production code review with impacts and proposed remedies.

## Filtering approach

DFS preorder makes every subtree a contiguous interval. When a node fails the
predicate, remember its depth and skip deeper nodes until a node at the same
or a smaller depth appears. That node is outside the rejected subtree and can
be evaluated normally. Kept nodes retain their original IDs, depths, and order;
no depth adjustment is needed because every ancestor of a kept node survives.

The scan invariant is that the output contains precisely the eligible nodes
already visited. While skipping, all deeper nodes have a rejected ancestor.
At a same-or-shallower node, that rejected subtree is complete. Evaluating the
new node therefore either appends an eligible node or starts another skipped
subtree. This proves the required ancestor condition and order preservation.

Time is **O(n)** assuming constant-time hierarchy access and predicate calls.
Two primitive arrays of input size avoid boxing and repeated growth; the
returned arrays are trimmed to the result size. Peak auxiliary/output storage
is **O(n)**, with O(1) traversal state. No recursion or ancestor stack is needed.
The tradeoff is allocating for the full input even when few nodes survive.

## Assumptions and behaviour

- The input satisfies the supplied forest invariants: parallel arrays, unique
  IDs, non-negative depths, initial depth zero for non-empty input, and depth
  increases of at most one. Empty forests are supported.
- Input and predicate do not mutate the hierarchy during filtering. The
  supplied `ArrayBasedHierarchy` remains unchanged and keeps its original
  constructor/ownership behaviour; validating arbitrary input is out of scope.
- Filtering works through the `Hierarchy` interface. It does not require the
  source to be array-based or IDs to be positive, dense, or ordered.
- Predicates run once per eligible node in DFS order. Descendants of rejected
  nodes are skipped without invoking the predicate. Predicate exceptions
  propagate to the caller. Predicate conjunction/idempotence tests use pure
  predicates; call counts can differ for stateful predicates.
- Results use newly allocated arrays and do not mutate or alias input arrays.

## Test coverage

The suite covers empty/single-node forests, accept/reject-all predicates,
root/internal/leaf rejection, surviving siblings and later roots, consecutive
rejected subtrees, large depth drops, extreme IDs, interface-only input,
predicate invocation order, exceptions, source preservation, result ownership,
pure-predicate composition, and a 100,000-node chain.

An independent recursive oracle filters explicit child lists, then flattens
the result. It is checked against all 64 allowed-node subsets of a six-node
forest and against 300 seeded random forests of bounded depth. The seed makes
failures reproducible; the deep-chain test separately checks stack safety.

## GitHub submission

Push this directory as the repository root, including the Gradle wrapper.
Keep the repository accessible to the interview team throughout the process:
either use an appropriate public repository or grant the team's GitHub accounts
access to a private repository. Verify access before sending its URL.

Do not commit generated `build/`, `.gradle/`, or `.kotlin/` directories.

## Validation

All 17 JUnit tests passed with no failures or skipped tests using JDK 17,
Kotlin 2.2.21, and Gradle 8.14.3. The checked-in wrapper pins the Gradle
distribution SHA-256 checksum.
