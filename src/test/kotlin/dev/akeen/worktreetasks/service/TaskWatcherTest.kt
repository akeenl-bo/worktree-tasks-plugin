package dev.akeen.worktreetasks.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWatcherTest {

    @Test
    fun `notifies when a task finishes or starts waiting, not when it starts working or goes quiet`() {
        assertTrue(shouldNotify(ClaudeStatus.WORKING, ClaudeStatus.DONE))
        assertTrue(shouldNotify(ClaudeStatus.WORKING, ClaudeStatus.NEEDS_INPUT))
        assertTrue(shouldNotify(ClaudeStatus.NEEDS_INPUT, ClaudeStatus.DONE))
        assertFalse(shouldNotify(ClaudeStatus.DONE, ClaudeStatus.WORKING))
        assertFalse(shouldNotify(ClaudeStatus.DONE, ClaudeStatus.DONE))
        assertFalse(shouldNotify(ClaudeStatus.DONE, null))
    }
}
