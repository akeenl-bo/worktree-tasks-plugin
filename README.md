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

## Settings — Settings | Tools | Worktree Tasks

- **Claude executable** (default `claude` on PATH)
- **Worktree base directory** (default `<repo>-worktrees` next to the repo)
- **Default base branch** (default current HEAD)
- **Run claude automatically** on task open; **initial prompt template** (`{task}` placeholder)
- **Dev Server**: commands (one per line), shell, shell args (`-i -l -c`), command prefix (`nvm use`)
- **Worktree Setup**: setup commands, run-on-create toggle, linked files

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
