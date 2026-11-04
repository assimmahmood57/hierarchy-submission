import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FilterTest {
    private fun sample(): Hierarchy = ArrayBasedHierarchy(
        intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        intArrayOf(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2),
    )

    private fun Hierarchy.entries(): List<Pair<Int, Int>> =
        (0 until size).map { nodeId(it) to depth(it) }

    private fun assertHierarchy(
        actual: Hierarchy,
        ids: IntArray,
        depths: IntArray,
    ) {
        assertEquals(ids.size, actual.size)
        assertEquals(ids.toList().zip(depths.toList()), actual.entries())
    }

    @Test
    fun originalExample() {
        assertHierarchy(
            sample().filter { it % 3 != 0 },
            intArrayOf(1, 2, 5, 8, 10, 11),
            intArrayOf(0, 1, 1, 0, 1, 2),
        )
    }

    @Test
    fun emptyHierarchyDoesNotInvokePredicate() {
        val empty = ArrayBasedHierarchy(intArrayOf(), intArrayOf())
        assertHierarchy(empty.filter { error("No nodes to evaluate") }, intArrayOf(), intArrayOf())
    }

    @Test
    fun singleRootCanBeKeptOrRejected() {
        val source = ArrayBasedHierarchy(intArrayOf(42), intArrayOf(0))
        assertHierarchy(source.filter { true }, intArrayOf(42), intArrayOf(0))
        assertHierarchy(source.filter { false }, intArrayOf(), intArrayOf())
    }

    @Test
    fun acceptingEveryNodePreservesOrderAndDepths() {
        val source = sample()
        assertEquals(source.entries(), source.filter { true }.entries())
    }

    @Test
    fun rejectingEveryNodeProducesEmptyForest() {
        assertHierarchy(sample().filter { false }, intArrayOf(), intArrayOf())
    }

    @Test
    fun rejectingRootRemovesItsWholeTreeAndResumesAtNextRoot() {
        assertHierarchy(
            sample().filter { it != 1 },
            intArrayOf(6, 7, 8, 9, 10, 11),
            intArrayOf(0, 1, 0, 1, 1, 2),
        )
    }

    @Test
    fun rejectingInternalNodeRemovesPassingDescendantsButKeepsSibling() {
        assertHierarchy(
            sample().filter { it != 2 },
            intArrayOf(1, 5, 6, 7, 8, 9, 10, 11),
            intArrayOf(0, 1, 0, 1, 0, 1, 1, 2),
        )
    }

    @Test
    fun rejectingLeavesDoesNotRemoveAncestorsOrLaterSiblings() {
        assertHierarchy(
            sample().filter { it != 4 && it != 9 && it != 11 },
            intArrayOf(1, 2, 3, 5, 6, 7, 8, 10),
            intArrayOf(0, 1, 2, 1, 0, 1, 0, 1),
        )
    }

    @Test
    fun consecutiveRejectedSubtreesAndLargeDepthDrops() {
        val source = ArrayBasedHierarchy(
            intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            intArrayOf(0, 1, 2, 3, 2, 3, 1, 2, 0, 1),
        )
        assertHierarchy(
            source.filter { it !in setOf(3, 5, 7) },
            intArrayOf(1, 2, 9, 10),
            intArrayOf(0, 1, 0, 1),
        )
    }

    @Test
    fun arbitraryNodeIdsAreNotArrayIndicesOrDepths() {
        val source: Hierarchy = object : Hierarchy {
            private val ids = listOf(Int.MIN_VALUE, 0, Int.MAX_VALUE, -19, 700)
            private val depths = listOf(0, 1, 2, 1, 0)
            override val size = ids.size
            override fun nodeId(index: Int) = ids[index]
            override fun depth(index: Int) = depths[index]
        }
        assertHierarchy(
            source.filter { it != 0 },
            intArrayOf(Int.MIN_VALUE, -19, 700),
            intArrayOf(0, 1, 0),
        )
    }

    @Test
    fun predicateSkipsRejectedDescendantsAndVisitsEligibleNodesOnceInOrder() {
        val visited = mutableListOf<Int>()
        sample().filter {
            visited += it
            it !in setOf(2, 6, 10)
        }
        assertEquals(listOf(1, 2, 5, 6, 8, 9, 10), visited)
    }

    @Test
    fun filteringDoesNotMutateSourceAndResultOwnsItsArrays() {
        val ids = intArrayOf(10, 20, 30)
        val depths = intArrayOf(0, 1, 0)
        val source = ArrayBasedHierarchy(ids, depths)
        val before = source.entries()
        val all = source.filter { true }
        val some = source.filter { it != 20 }
        assertEquals(before, source.entries())
        ids[0] = 999
        depths[1] = 0
        assertEquals(before, all.entries())
        assertHierarchy(some, intArrayOf(10, 30), intArrayOf(0, 0))
    }

    @Test
    fun predicateExceptionsArePropagated() {
        val expected = IllegalStateException("Predicate failed")
        val actual = assertFailsWith<IllegalStateException> {
            sample().filter { if (it == 3) throw expected else true }
        }
        assertSame(expected, actual)
    }

    @Test
    fun deepChainNeedsNoRecursionAndPrunesAllDescendants() {
        val count = 100_000
        val source = ArrayBasedHierarchy(IntArray(count) { it }, IntArray(count) { it })
        val all = source.filter { true }
        assertEquals(source.entries(), all.entries())
        assertHierarchy(source.filter { it != 1 }, intArrayOf(0), intArrayOf(0))
    }

    @Test
    fun purePredicatesAreIdempotentAndComposeByConjunction() {
        val source = sample()
        val p: (Int) -> Boolean = { it % 3 != 0 }
        val q: (Int) -> Boolean = { it % 5 != 0 }
        assertEquals(source.filter(p).entries(), source.filter(p).filter(p).entries())
        assertEquals(
            source.filter { p(it) && q(it) }.entries(),
            source.filter(p).filter(q).entries(),
        )
    }

    // The oracle uses explicit trees and recursion instead of the production
    // array/depth traversal. Recursion is deliberately limited to small trees.
    private data class Node(val id: Int, val children: List<Node>)

    private fun flatten(forest: List<Node>): List<Pair<Int, Int>> = buildList {
        fun visit(node: Node, depth: Int) {
            add(node.id to depth)
            node.children.forEach { visit(it, depth + 1) }
        }
        forest.forEach { visit(it, 0) }
    }

    private fun prune(forest: List<Node>, allowed: Set<Int>): List<Node> =
        forest.filter { it.id in allowed }.map { it.copy(children = prune(it.children, allowed)) }

    @Test
    fun allPredicatesOnSmallBranchingForestMatchTreeOracle() {
        val forest = listOf(
            Node(0, listOf(Node(1, listOf(Node(2, emptyList()))), Node(3, emptyList()))),
            Node(4, listOf(Node(5, emptyList()))),
        )
        val entries = flatten(forest)
        val source = ArrayBasedHierarchy(
            entries.map { it.first }.toIntArray(), entries.map { it.second }.toIntArray(),
        )
        for (mask in 0 until (1 shl 6)) {
            val allowed = (0 until 6).filter { mask and (1 shl it) != 0 }.toSet()
            assertEquals(
                flatten(prune(forest, allowed)), source.filter { it in allowed }.entries(),
                "mask=$mask",
            )
        }
    }

    @Test
    fun seededRandomForestsMatchTreeOracle() {
        val random = Random(20261104)
        repeat(300) { case ->
            var nextId = -10_000
            fun tree(depth: Int): Node {
                val id = nextId
                nextId += 37
                val childCount = if (depth == 5) 0 else random.nextInt(4)
                return Node(id, List(childCount) { tree(depth + 1) })
            }
            val forest = List(random.nextInt(6)) { tree(0) }
            val entries = flatten(forest)
            val source = ArrayBasedHierarchy(
                entries.map { it.first }.toIntArray(), entries.map { it.second }.toIntArray(),
            )
            val allowed = entries.map { it.first }.filter { random.nextBoolean() }.toSet()
            val actual = source.filter { it in allowed }
            assertEquals(flatten(prune(forest, allowed)), actual.entries(), "case=$case")
            assertEquals(entries, source.entries(), "Source changed in case=$case")
            for (index in 0 until actual.size) {
                val previousDepth = if (index == 0) -1 else actual.depth(index - 1)
                assertEquals(true, actual.depth(index) in 0..(previousDepth + 1), "case=$case")
            }
        }
    }
}
