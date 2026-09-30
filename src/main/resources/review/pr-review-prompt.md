You are reviewing a teammate's pull request so Akeen can review it quickly: PR #{number} "{title}" by {author} ({url}). This worktree has the PR's branch checked out; its base is {base}, and the change is `git diff {base}...HEAD`.

Rules: read only. Do not write or modify any files, and do not post, comment, or change anything on GitHub or Jira. Use the Read, Grep and Glob tools to look at files; Bash only allows plain read-only git and gh commands (no pipes, variables, or scripts), and code can't be run.

1. Jira: {jira} Read it with the Atlassian tools (getAccessibleAtlassianResources for the cloudId, then getJiraIssue), and its parent epic if it has one.
2. Code review: run the code-review skill at high effort on this branch against {base}. Keep only findings you verified in the code.
3. Build the review tour following the review-tour skill (steps follow the call chain from the user's action, one per function, with label / what / before / now), plus:
   - "sections": [
       {"title": "Jira", "bullets": [what the ticket asks for, its acceptance criteria, and anything the PR does differently or leaves out]},
       {"title": "Manual test", "bullets": [the URL or screen, the dev account / feature flags needed, then numbered steps each with the expected result]},
       {"title": "Findings", "bullets": ["[high|medium|low] path:line — what's wrong and why", ...] (or one bullet saying none were found)}
     ]
   - "findings": [{"file": "path", "line": N, "severity": "high|medium|low", "text": "..."}] for the same findings.
Your final reply must be only the review tour: one JSON object with keys title, summary, sections, findings, steps, and "take" (one short paragraph: what the PR does and your overall take). No prose or code fences around it; the plugin saves it as the tour.
