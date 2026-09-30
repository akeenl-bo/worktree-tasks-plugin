package dev.akeen.worktreetasks.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HunksTest {

    private val base = listOf("a", "b", "c", "d", "e", "f")
    private val head = listOf("a", "B", "c", "new1", "new2", "d", "f")

    // base -> head: change b -> B, insert new1/new2 after c, delete e.
    private val diff = """
        diff --git a/x.rb b/x.rb
        index 111..222 100644
        --- a/x.rb
        +++ b/x.rb
        @@ -2 +2 @@ def x
        -b
        +B
        @@ -3,0 +4,2 @@
        +new1
        +new2
        @@ -5 +6,0 @@
        -e
    """.trimIndent()

    @Test
    fun `applies any subset of hunks to reproduce each part's version, and maps head lines into it`() {
        val file = Hunks.parse(diff).single()
        val (rename, insert, delete) = file.hunks

        assertEquals(listOf("H1", "H2", "H3"), file.hunks.map { it.id })
        assertEquals(head, Hunks.apply(base, file.hunks, setOf("H1", "H2", "H3")))
        assertEquals(base, Hunks.apply(base, file.hunks, emptySet()))
        // Part 1 = the insertion only; part 2 adds the rest.
        assertEquals(listOf("a", "b", "c", "new1", "new2", "d", "e", "f"), Hunks.apply(base, file.hunks, setOf(insert.id)))
        assertEquals(listOf("a", "B", "c", "d", "f"), Hunks.apply(base, file.hunks, setOf(rename.id, delete.id)))

        // Head line 6 ("d") sits after the insertion; without it, "d" is line 4.
        assertEquals(4, Hunks.mapHeadLine(6, file.hunks, setOf(rename.id, delete.id)))
        // Head line 4 ("new1") only exists in the insertion; it maps to where the insertion goes.
        assertEquals(4, Hunks.mapHeadLine(4, file.hunks, setOf(rename.id, delete.id)))
        assertEquals(6, Hunks.mapHeadLine(6, file.hunks, file.hunks.map { it.id }.toSet()))
    }
}
