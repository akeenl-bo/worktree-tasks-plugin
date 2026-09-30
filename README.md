# Worktree Tasks

An IntelliJ IDEA plugin for **agentic-first coding**: each *task* is a git worktree opened as its
own IDE window, with a persistent [Claude Code](https://code.claude.com) agent running in that
window's terminal. A left sidebar (**Worktree Tasks**) lists the repository's worktrees and lets you
create, open, provision, run, and remove them — and shows each agent's live status.

## Model

- **One window per worktree.** Open/switch a task opens (or focuses) that worktree as its own project
  window. With macOS native tabs on (**System Settings → Desktop & Dock → Prefer tabs when opening
  documents → Always**), the windows group as tabs in one frame. Each window keeps its own Claude
  agent alive, so switching between worktrees never kills another agent.
- **Per-worktree dev server, one live at a time.** Each worktree controls its own dev server, but
  activating one stops any other first so they don't fight over the shared port (e.g. `:3000`).
- **No integration with the Claude plugin needed.** Running `claude` in the IDE terminal makes the
  official Claude Code plugin auto-activate; Claude isolates session state per directory.

## What the sidebar does

- **New Task** — prompt for name + base branch → `git worktree add` → open the worktree window.
- **Open & Run** (green ▶, or double-click) — open/focus the worktree window, run its dev server
  (stopping any other), and open Claude.
- **Stop Server** — stop the selected worktree's dev server.
- **Run Setup** — run setup commands (e.g. `yarn install`) in the worktree.
- **Remove** — `git worktree remove` (offers force + branch delete).
- Status per task: `▶ serving`, `⋯ working`, `● needs input`, `✓ done`, plus dirty/branch/main markers.

## Worktree provisioning

A fresh worktree shares git-tracked files but not its environment, so on first open the plugin:

1. **Links secrets** — symlinks gitignored files (`config/master.key`, `.env`) from the main worktree.
2. **Installs deps** — runs setup commands (`yarn install`) when `node_modules` is missing.
3. **Activates the toolchain** — runs a command prefix (`nvm use`) so the worktree's `.nvmrc`/
   `.ruby-version` are honored. Commands run through an interactive login shell that `cd`s into the
   worktree, so version managers (nvm/fnm/direnv) and inline env vars (`ASSETS_COMPILE=true`) work.

## Claude status indicator

The plugin merges Claude Code hooks into each worktree's gitignored `.claude/settings.local.json`
(`UserPromptSubmit`/`Notification`/`Stop`) that write a status file the sidebar polls — so you can see
which agents are working, waiting on you, or done across all windows.

## Task Browser

A right-side tool window with an embedded Chromium (JCEF) on the dev server (`http://localhost:3000`
by default), so the app can be used without leaving the IDE: back / forward / reload, an address bar,
DevTools, and open-in-external-browser. Only one task's server runs at a time on the shared port, so
the header says which task is serving; when a server starts, the page reloads once the URL answers.
Cookies persist in the IDE's JCEF cache, so a login sticks across windows and restarts.

**Claude can drive it.** Set **Remote debugging port** (e.g. `9333`; it sets the IDE registry key
`ide.browser.jcef.debug.port` and needs a restart). Each open Task Browser then writes
`.claude/.worktree-browser` (`{"cdp": ..., "worktree": ...}`) and tags its page with
`window.__worktreeTask = "<worktree path>"`, so Playwright's `chromium.connectOverCDP` can pick this
task's page out of the IDE's embedded browsers and screenshot or click through exactly what you see.

## Jira Board

A bottom tool window showing Jira tickets in their board's columns, one saved view at a time.

- **Start Task** (or double-click) — opens the ticket's task if a worktree branch already carries its
  key; otherwise New Task pre-filled from the ticket, with **Assign to me** and **Move to In Progress**
  checkboxes, and Claude's first prompt set to the ticket's key, summary, and link.
- **Create Ticket** — summary, description, epic, plus every field the issue type's create screen
  requires (custom fields included), pre-filled from `fieldDefaults`.
- Reloads every few minutes while visible, and when its config file changes.

The site, project, views, and field defaults live in a JSON file rather than the plugin, by default
`~/.config/worktree-tasks/jira.json` (open it from the board's gear menu; point Settings at a file in
any repo to track it). Views with a `boardId` use that board's filter and columns, narrowed by `jql`;
views without one run `jql` alone, grouped by status. `repoPath` is where Start Task makes worktrees.

```json
{
  "site": "https://your-site.atlassian.net",
  "project": "PROJ",
  "startStatus": "In Progress",
  "fieldDefaults": { "Story Points": "2", "Components": "Platform" },
  "views": [
    { "name": "My tickets", "boardId": 42, "jql": "assignee = currentUser() AND statusCategory != Done", "repoPath": "~/dev/app", "epicKey": "" },
    { "name": "Epic PROJ-100", "boardId": 0, "jql": "parent = PROJ-100 ORDER BY Rank", "repoPath": "~/dev/service", "epicKey": "PROJ-100" }
  ]
}
```

Your email and API token go in Settings; the token is kept in the macOS Keychain. Points are read
from every field named "Story Points" or "Story point estimate"; if your site has several and the
wrong one wins, pin it with `"pointsField": "customfield_10016"` (the id from the field's admin URL).

**Dashboard tab.** This week (Monday to Friday) against a weekly point goal set at the top of the tab
(blank or 0 = your last four weeks' average). A ticket is credited to the week it *first* entered a
delivered status (from its changelog, so moving on through QA doesn't count it twice); tickets in
flight count as committed, so what's left to pick up is goal − delivered − in flight. **Today** lists
in-flight work (review first), then enough ranked To Do tickets to cover today's even share of what's
left; **Rest of the week** covers the remainder. Your own To Do tickets come before unassigned ones.
**Ask Claude** has a headless Claude reorder the picks with reasons; that session gets no tools or MCP
servers, only the ticket data in its prompt. The dashboard never assigns, moves, or edits a ticket.

```json
"dashboard": {
  "boardId": 42,
  "deliveredStatuses": ["Merged", "Ready for QA", "Done"],
  "reviewStatuses": ["Code Review"],
  "pickStatuses": ["To Do"]
}
```

`boardId` defaults to the first view with a board; empty `deliveredStatuses` means the "done" category
and empty `pickStatuses` the "To Do" category.

## Settings — Settings | Tools | Worktree Tasks

- **Claude executable** (default `claude` on PATH)
- **Worktree base directory** (default `<repo>-worktrees` next to the repo)
- **Default base branch** (default current HEAD)
- **Run claude automatically** on task open; **initial prompt template** (`{task}` placeholder)
- **Dev Server**: commands (one per line), shell, shell args (`-i -l -c`), command prefix (`nvm use`)
- **Worktree Setup**: setup commands, run-on-create toggle, linked files
- **Task Browser**: URL, remote debugging port (for Claude over CDP)
- **Jira Board**: config file, email, API token, reload interval, task prompt (`{key}`, `{summary}`, `{url}`)

## Building / running

Requires JDK 21 for the build (auto-provisioned via the foojay toolchain resolver; only a newer JDK
needs to be installed locally). Targets IntelliJ Platform **2025.3** (`sinceBuild = 253`).

```bash
./gradlew runIde        # launch a sandbox IDE with the plugin
./gradlew buildPlugin   # produce build/distributions/worktree-tasks-*.zip
./gradlew test          # run unit tests
```

Install the built zip via **Settings | Plugins | ⚙ | Install Plugin from Disk…**. The official Claude
Code plugin should also be installed for the terminal integration. The sandbox IDE's heap is raised
in `build.gradle.kts` (`runIde { maxHeapSize = "4g" }`) so indexing a real project doesn't OOM.
