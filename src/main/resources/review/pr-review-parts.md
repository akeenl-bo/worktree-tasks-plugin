4. This PR is big: about {lines} changed lines not counting specs, schema.rb and lockfiles, in {count} commits:
{commits}
   Split the review into 3 to 7 parts Akeen can review one at a time, ordered so each builds on the ones before it (usually data and models first, then the flow the user triggers). Add "parts": [{"title", "why" (one sentence: why this is one unit), "summary" (this part's own flow bullets), "steps" (call-chain steps for just this part), and either "commits" or "hunks"}].
   - Use "commits" (short shas, in order, each commit in exactly one part, a part being a run of consecutive commits; fold fixup commits into the commit they fix) when the commits already tell a coherent story. Lines in a commit part's steps refer to the file as of that part's last commit (git show <sha>:<path>).
   - Otherwise (one big commit, or commits that are just work in progress) use "hunks": ids from .claude/review-hunks.md (read it), or "F:<path>" for a whole file. Every hunk goes in exactly one part; hunks of one file may go to different parts. Lines in a hunk part's steps refer to the current files.
   - The top-level summary, sections, findings and take still cover the whole PR; top-level "steps" can be empty.

