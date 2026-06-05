package dev.akeen.worktreetasks.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persists friendly task display names keyed by absolute worktree path. Git itself doesn't store a
 * human name for a worktree, so we keep our own mapping (application-level so every window agrees).
 */
@State(
    name = "WorktreeTaskNames",
    storages = [Storage("worktreeTasks.xml")],
)
class TaskNameStore : PersistentStateComponent<TaskNameStore.State> {

    class State {
        // path -> friendly name
        var names: MutableMap<String, String> = mutableMapOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun nameFor(path: String): String? = state.names[path]

    fun put(path: String, name: String) {
        state.names[path] = name
    }

    fun remove(path: String) {
        state.names.remove(path)
    }

    companion object {
        fun getInstance(): TaskNameStore =
            ApplicationManager.getApplication().getService(TaskNameStore::class.java)
    }
}
