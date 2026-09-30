You are reviewing a teammate's pull request so Akeen can review it quickly: PR #{number} "{title}" by {author} ({url}). This worktree has the PR's branch checked out; its base is {base}, and the change is `git diff {base}...HEAD`.

Rules: read only. Do not modify any code, and do not post, comment, or change anything on GitHub or Jira. The only file you may write is .claude/review-tour.json.

1. Jira: {jira} Read it with the Atlassian tools (getAccessibleAtlassianResources for the cloudId, then getJiraIssue), and its parent epic if it has one.
2. Code review: run the code-review skill at high effort on this branch against {base}. Keep only findings you verified in the code.
3. Write .claude/review-tour.json following the review-tour skill (steps follow the call chain from the user's action, one per function, with label / what / before / now), plus:
   - "sections": [
       {"title": "Jira", "bullets": [what the ticket asks for, its acceptance criteria, and anything the PR does differently or leaves out]},
       {"title": "Manual test", "bullets": [the URL or screen, the dev account / feature flags needed, then numbered steps each with the expected result]},
       {"title": "Findings", "bullets": ["[high|medium|low] path:line — what's wrong and why", ...] (or one bullet saying none were found)}
     ]
   - "findings": [{"file": "path", "line": N, "severity": "high|medium|low", "text": "..."}] for the same findings.
Finish by replying with one short paragraph: what the PR does and your overall take.
