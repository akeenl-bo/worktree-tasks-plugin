package dev.akeen.worktreetasks.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewToursTest {

    @Test
    fun `walks the tour first, keeps context steps, then the rest by layer with specs last`() {
        val tour = ReviewTours.parse(
            """
            {"title": "Record system text",
             "steps": [
               {"file": "app/controllers/ai/chats_controller.rb", "line": 40, "note": "Controller hands the prompt to SystemText"},
               {"file": "app/models/ai/system_text.rb", "line": 21, "note": "Stored once per distinct text"},
               {"file": "app/models/ai/unchanged_caller.rb", "line": 7, "note": "Existing caller, unchanged"},
               {"file": "app/models/ai/gone.rb", "note": "Stale step for a file that no longer exists"},
               {"note": "No file, ignored"}
             ]}
            """.trimIndent(),
        )
        val changed = listOf(
            "spec/models/ai/system_text_spec.rb",
            "app/models/ai/system_text.rb",
            "app/controllers/ai/chats_controller.rb",
            "db/migrate/20260929200355_record_ai_turn_system_messages.rb",
            "app/models/ai/metrics/turn.rb",
        )

        val steps = ReviewTours.steps(tour, changed) { it == "app/models/ai/unchanged_caller.rb" }

        assertEquals(
            listOf(
                "app/controllers/ai/chats_controller.rb",
                "app/models/ai/system_text.rb",
                "app/models/ai/unchanged_caller.rb",
                "db/migrate/20260929200355_record_ai_turn_system_messages.rb",
                "app/models/ai/metrics/turn.rb",
                "spec/models/ai/system_text_spec.rb",
            ),
            steps.map { it.file },
        )
        assertEquals(ReviewStep("app/models/ai/system_text.rb", 21, "Stored once per distinct text"), steps[1])
    }
}
