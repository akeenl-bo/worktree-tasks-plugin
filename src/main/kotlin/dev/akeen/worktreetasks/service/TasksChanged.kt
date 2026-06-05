package dev.akeen.worktreetasks.service

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic

/**
 * Project message-bus topic fired when the set of tasks (worktrees) changes, so open tool windows
 * can refresh themselves.
 */
fun interface TasksChangedListener {
    fun tasksChanged()
}

val TASKS_CHANGED: Topic<TasksChangedListener> =
    Topic.create("Worktree tasks changed", TasksChangedListener::class.java)

fun Project.fireTasksChanged() {
    if (isDisposed) return
    messageBus.syncPublisher(TASKS_CHANGED).tasksChanged()
}

/** Refresh the task sidebar in every open window (used for app-wide state like the active server). */
fun fireTasksChangedEverywhere() {
    ProjectManager.getInstance().openProjects.forEach { it.fireTasksChanged() }
}
