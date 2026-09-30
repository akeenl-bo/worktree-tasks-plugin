package dev.akeen.worktreetasks.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PrWatcherTest {

    @Test
    fun `sorts a poll into new PRs, PRs with new commits, and PRs no longer listed`() {
        val found = parseGhPrs(
            """
            [{"number": 31021, "title": "BSD-38660 ai deal ingestion", "url": "https://github.com/buildoutinc/buildout/pull/31021",
              "headRefName": "tim/ai-deal-ingestion", "baseRefName": "master", "headRefOid": "77e9b554de", "author": {"login": "timothyjosefik"}},
             {"number": 31030, "title": "BSD-38700 new", "url": "u", "headRefName": "x", "baseRefName": "master", "headRefOid": "aaa", "author": {"login": "someone"}}]
            """.trimIndent(),
        )
        val reviewed = record(31021, latestSha = "0ld5ha")
        val gone = record(30990, latestSha = "fff")

        val plan = planPoll(listOf(reviewed, gone), found)

        assertEquals(listOf(31030), plan.newPrs.map { it.number })
        assertEquals(listOf(31021 to "77e9b554de"), plan.moved.map { (record, pr) -> record.number to pr.headSha })
        assertEquals(listOf(30990), plan.missing.map { it.number })
        assertEquals("timothyjosefik", found.first().author)
        assertEquals("buildoutinc/buildout", githubRepo("git@github.com:buildoutinc/buildout.git"))
        assertEquals("buildoutinc/buildout", githubRepo("https://github.com/buildoutinc/buildout"))
    }

    private fun record(number: Int, latestSha: String) = PrReviewStore.Record().apply {
        this.number = number
        this.latestSha = latestSha
    }
}
