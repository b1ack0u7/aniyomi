package tachiyomi.core.common.util.lang

import java.util.Collections
import java.util.IdentityHashMap

/** Includes this exception and stops at a cycle or [maxDepth] exceptions. */
fun Throwable.causeChain(maxDepth: Int = 16): List<Throwable> {
    require(maxDepth > 0)
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = ArrayList<Throwable>()
    var current: Throwable? = this
    while (current != null && causes.size < maxDepth && seen.add(current)) {
        causes.add(current)
        current = current.cause
    }
    return causes
}
