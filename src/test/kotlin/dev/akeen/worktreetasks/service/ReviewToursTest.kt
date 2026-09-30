package dev.akeen.worktreetasks.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewToursTest {

    @Test
    fun `walks the tour once per file visit, then the rest outside-in with specs last`() {
        val tour = ReviewTours.parse(
            """
            {"title": "Record system text",
             "summary": ["When an admin opens a chat, turns load their instructions text"],
             "steps": [
               {"file": "app/controllers/ai/admin/chats_controller.rb", "line": 67, "note": "Preloads the text"},
               {"file": "app/controllers/ai/admin/chats_controller.rb", "line": 116, "note": "Markers compare text ids"},
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
            "app/controllers/ai/admin/chats_controller.rb",
            "db/migrate/20260929200355_record_ai_turn_system_messages.rb",
            "app/models/ai/metrics/turn.rb",
            "app/javascript/components/ai/admin/chat.tsx",
        )

        val steps = ReviewTours.steps(tour, changed) { it == "app/models/ai/unchanged_caller.rb" }

        assertEquals(
            listOf(
                "app/controllers/ai/admin/chats_controller.rb",
                "app/models/ai/system_text.rb",
                "app/models/ai/unchanged_caller.rb",
                "app/javascript/components/ai/admin/chat.tsx",
                "app/models/ai/metrics/turn.rb",
                "db/migrate/20260929200355_record_ai_turn_system_messages.rb",
                "spec/models/ai/system_text_spec.rb",
            ),
            steps.map { it.file },
        )
        assertEquals(
            ReviewStep("app/controllers/ai/admin/chats_controller.rb", 67, "Preloads the text · Markers compare text ids"),
            steps[0],
        )
        val overview = ReviewTours.overview(tour, steps)!!
        assertTrue(overview.contains("- When an admin opens a chat, turns load their instructions text"))
        assertTrue(overview.contains("1. `app/controllers/ai/admin/chats_controller.rb` — Preloads the text"))
    }
}
