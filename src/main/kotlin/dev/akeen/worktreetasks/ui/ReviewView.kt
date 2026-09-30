package dev.akeen.worktreetasks.ui

import com.intellij.diff.requests.DiffRequest
import dev.akeen.worktreetasks.service.ReviewPart
import dev.akeen.worktreetasks.service.ReviewStep

/**
 * One page of Task Review: the whole change, the overview of a big PR (no steps; its list shows the
 * parts), or one [part] of it. [headLines] are the steps' lines in the PR's head, for matching findings.
 */
class ReviewView(
    val label: String,
    val part: ReviewPart?,
    val steps: List<ReviewStep>,
    val headLines: List<Int?>,
    val changed: List<Boolean>,
    val requests: List<DiffRequest>,
) {
    val isPartsOverview: Boolean get() = part == null && steps.isEmpty()
}
