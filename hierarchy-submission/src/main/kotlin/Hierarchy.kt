/**
 * An ordered forest in DFS preorder, with parallel node IDs and depths.
 * Roots have depth 0. Depths are non-negative and can increase by at most one
 * between consecutive nodes. Node IDs are unique.
 */
interface Hierarchy {
    val size: Int

    /** Returns the node ID at [index], where 0 <= index < [size]. */
    fun nodeId(index: Int): Int

    /** Returns the node depth at [index], where 0 <= index < [size]. */
    fun depth(index: Int): Int

    fun formatString(): String = (0 until size).joinToString(
        separator = ", ", prefix = "[", postfix = "]"
    ) { i -> "${nodeId(i)}:${depth(i)}" }
}

/**
 * Keeps a node iff its ID and every ancestor's ID pass [nodeIdPredicate].
 * Preserves order and depths. Descendants of rejected nodes are not evaluated.
 * Assumes a valid hierarchy that remains unchanged during this call.
 * Takes O(size) time and O(size) space, assuming constant-time access/predicate.
 */
fun Hierarchy.filter(nodeIdPredicate: (Int) -> Boolean): Hierarchy {
    val nodeCount = size
    val nodeIds = IntArray(nodeCount)
    val depths = IntArray(nodeCount)
    var keptCount = 0
    var rejectedDepth = -1

    for (index in 0 until nodeCount) {
        val currentDepth = depth(index)
        if (rejectedDepth >= 0) {
            // A subtree ends at the first node no deeper than its root.
            if (currentDepth > rejectedDepth) continue
            rejectedDepth = -1
        }

        val id = nodeId(index)
        if (nodeIdPredicate(id)) {
            nodeIds[keptCount] = id
            depths[keptCount] = currentDepth
            keptCount++
        } else {
            rejectedDepth = currentDepth
        }
    }

    return ArrayBasedHierarchy(nodeIds.copyOf(keptCount), depths.copyOf(keptCount))
}

class ArrayBasedHierarchy(
    private val myNodeIds: IntArray,
    private val myDepths: IntArray,
) : Hierarchy {
    override val size: Int = myDepths.size
    override fun nodeId(index: Int): Int = myNodeIds[index]
    override fun depth(index: Int): Int = myDepths[index]
}
