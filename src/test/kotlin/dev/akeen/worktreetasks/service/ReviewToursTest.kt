package dev.akeen.worktreetasks.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewToursTest {

    @Test
    fun `follows the tour's hops, merging only same-screen steps, then the rest outside-in with specs last`() {
        val tour = ReviewTours.parse(
            """
            {"title": "Record system text",
             "summary": ["When an admin opens a chat, turns load their instructions text"],
             "steps": [
               {"file": "app/controllers/ai/admin/chats_controller.rb", "line": 67, "label": "ChatsController#turns", "what": "Loads the turns."},
               {"file": "app/controllers/ai/admin/chats_controller.rb", "line": 70, "what": "Orders them."},
               {"file": "app/models/ai/system_text.rb", "line": 21, "label": "SystemText.for", "note": "Stored once per text",
                "before": "Only the instructions.", "now": "Every system block."},
               {"file": "app/controllers/ai/admin/chats_controller.rb", "line": 116, "label": "ChatsController#prompts_by_message_id"},
               {"file": "app/models/ai/unchanged_caller.rb", "line": 7, "label": "Caller#call"},
               {"file": "app/models/ai/gone.rb", "label": "Stale step for a deleted file"},
               {"label": "No file, ignored"}
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
                "ChatsController#turns",
                "SystemText.for",
                "ChatsController#prompts_by_message_id",
                "Caller#call",
                "app/javascript/components/ai/admin/chat.tsx",
                "app/models/ai/metrics/turn.rb",
                "db/migrate/20260929200355_record_ai_turn_system_messages.rb",
                "spec/models/ai/system_text_spec.rb",
            ),
            steps.map { it.label ?: it.file },
        )
        assertEquals("Loads the turns. Orders them.", steps[0].what)
        assertEquals(
            ReviewStep("app/models/ai/system_text.rb", 21, "SystemText.for", "Stored once per text", "Only the instructions.", "Every system block."),
            steps[1],
        )
    }

    @Test
    fun `reads a PR review tour out of the reviewer's reply, fenced or not`() {
        val reply = "```json\n" + """
            {"title": "PR #31021", "take": "Fills in the deal from documents.",
             "summary": ["When a broker creates a deal..."],
             "sections": [{"title": "Jira", "bullets": ["No acceptance criteria."]}],
             "findings": [{"file": "app/models/ai/actions/create_deal.rb", "line": 137, "severity": "medium", "text": "Otto isn't wired."}],
             "steps": [{"file": "app/models/ai/actions/create_deal.rb", "line": 120, "label": "CreateDeal#call"}]}
        """.trimIndent() + "\n```"

        val tour = ReviewTours.parse(ReviewTours.extractJsonObject(reply)!!)!!

        assertEquals("Fills in the deal from documents.", tour.take)
        assertEquals(listOf("Jira"), tour.sections.map { it.title })
        assertEquals(mapOf(0 to tour.findings), ReviewTours.findingsByStep(tour.findings, tour.steps))
    }
}
