package tachiyomi.core.common.util.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ThrowableExtensionsTest {
    @Test
    fun `cause chain includes the original exception in order`() {
        val root = IllegalArgumentException("root")
        val wrapper = IllegalStateException("wrapper", root)

        assertEquals(listOf(wrapper, root), wrapper.causeChain())
    }

    @Test
    fun `cyclic causes terminate at the repeated exception`() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)

        assertEquals(listOf(first, second), first.causeChain())
    }

    @Test
    fun `deep causes are bounded`() {
        val exception = (1..30).fold(Exception("root")) { cause, depth -> Exception("$depth", cause) }

        assertEquals(16, exception.causeChain().size)
        assertEquals(2, exception.causeChain(maxDepth = 2).size)
    }

    @Test
    fun `invalid depth is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Exception().causeChain(0) }
    }
}
