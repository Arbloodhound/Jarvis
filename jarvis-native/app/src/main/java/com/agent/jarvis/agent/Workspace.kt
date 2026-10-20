package com.agent.jarvis.agent

import android.content.Context
import com.agent.jarvis.harness.Artifacts
import com.agent.jarvis.harness.Trash
import java.io.File

/**
 * The agent's files (HANDOFF_V5 §2 and §5): artifacts it makes live under filesDir/workspace, and
 * nothing it deletes is gone — it goes to the trash (filesDir/trash) until the owner empties it in
 * Settings. The agent can list and restore trash, never empty it.
 */
object Workspace {
    @Volatile private var trash: Trash? = null
    @Volatile private var artifacts: Artifacts? = null

    fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, "workspace").apply { mkdirs() }

    @Synchronized fun trash(ctx: Context): Trash = trash ?: Trash(File(ctx.applicationContext.filesDir, "trash"), listOf(root(ctx))).also { trash = it }

    @Synchronized fun artifacts(ctx: Context): Artifacts = artifacts ?: Artifacts(File(root(ctx), "artifacts"), trash(ctx)).also { artifacts = it }
}
