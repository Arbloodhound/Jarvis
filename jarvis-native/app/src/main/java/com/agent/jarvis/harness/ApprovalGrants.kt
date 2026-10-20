package com.agent.jarvis.harness

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.io.File
import java.security.MessageDigest

/**
 * Persistent approval grants — the four levels from the reverse-engineering:
 *
 * - ONCE: this action only (single use, then consumed)
 * - SITE: always allow for this website
 * - RECIPIENT: allow for this recipient from this account
 * - TASK: allow for this one scheduled task only
 * - ALWAYS_ASK: purchases. No lasting grant exists, anywhere.
 *
 * The existing Gate does per-episode confirmations; this is the durable
 * layer beside it. The agent cannot revoke a grant; the user does, in
 * settings (see revoke*).
 *
 * Exec approvals additionally bind the EXACT command: cwd + argv +
 * resolved executable path + content hash, revalidated at launch. Any
 * drift fails closed (kills the TOCTOU class).
 *
 * DB wiring (repo session): add GrantEntity to JarvisDatabase entities,
 * bump version, add migration. This file never touches existing files.
 */
@Entity(
    tableName = "approval_grants",
    indices = [Index("level"), Index("scopeKey")],
)
data class GrantEntity(
    @PrimaryKey val id: String,
    /** once|site|recipient|task */
    val level: String,
    /** site host | "channel|account|recipient" | task id | "tool|argsHash" */
    val scopeKey: String,
    val tool: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** once-grants are deleted on use */
    val singleUse: Boolean = false,
)

@Dao
interface GrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(g: GrantEntity)

    @Query("SELECT * FROM approval_grants WHERE level = :level AND scopeKey = :key LIMIT 1")
    suspend fun find(level: String, key: String): GrantEntity?

    @Query("DELETE FROM approval_grants WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM approval_grants WHERE level = :level AND scopeKey = :key")
    suspend fun deleteByScope(level: String, key: String)
}

/** Approval levels. */
object ApprovalLevel {
    const val NONE = "none"
    const val ONCE = "once"
    const val SITE = "site"
    const val RECIPIENT = "recipient"
    const val TASK = "task"
    const val ALWAYS_ASK = "always_ask"
}

/** Exact command identity for an exec approval binding. */
data class CommandBinding(
    val cwd: String,
    val argv: List<String>,
    val resolved: String,
    val hash: String,
)

object CommandBinder {
    fun bind(cwd: String, argv: List<String>): CommandBinding {
        val exe = argv.firstOrNull() ?: ""
        val resolved = resolveExe(exe)
        return CommandBinding(
            cwd = File(cwd).canonicalPath,
            argv = argv.toList(),
            resolved = resolved,
            hash = if (resolved.isNotEmpty()) sha256File(resolved) else "",
        )
    }

    /** Revalidate at launch. Any drift -> false (fail closed). */
    fun matches(b: CommandBinding, cwd: String, argv: List<String>): Boolean {
        val now = bind(cwd, argv)
        return now.cwd == b.cwd && now.argv == b.argv &&
            now.resolved.isNotEmpty() && now.resolved == b.resolved &&
            now.hash == b.hash
    }

    private fun resolveExe(exe: String): String {
        if (exe.isEmpty()) return ""
        if (File(exe).isAbsolute) return File(exe).canonicalPath
        val path = System.getenv("PATH") ?: return ""
        for (d in path.split(File.pathSeparator)) {
            val f = File(d, exe)
            if (f.isFile && f.canExecute()) return f.canonicalPath
        }
        return ""
    }

    private fun sha256File(path: String): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n > 0) { md.update(buf, 0, n); n = ins.read(buf) }
        }
        md.digest().joinToString("") { "%02x".format(it) }.take(16)
    } catch (_: Exception) { "" }
}

class ApprovalGrants(private val dao: GrantDao) {

    suspend fun grantSite(host: String) {
        dao.upsert(GrantEntity(id = "g:${System.nanoTime()}", level = ApprovalLevel.SITE,
            scopeKey = host.lowercase()))
    }

    suspend fun grantRecipient(channel: String, account: String, recipient: String) {
        dao.upsert(GrantEntity(id = "g:${System.nanoTime()}", level = ApprovalLevel.RECIPIENT,
            scopeKey = "$channel|$account|$recipient"))
    }

    suspend fun grantTask(taskId: String) {
        dao.upsert(GrantEntity(id = "g:${System.nanoTime()}", level = ApprovalLevel.TASK,
            scopeKey = taskId))
    }

    suspend fun grantOnce(tool: String, argsJson: String) {
        dao.upsert(GrantEntity(id = "g:${System.nanoTime()}", level = ApprovalLevel.ONCE,
            scopeKey = "$tool|${argsJson.hashCode()}", tool = tool, singleUse = true))
    }

    suspend fun revokeSite(host: String) = dao.deleteByScope(ApprovalLevel.SITE, host.lowercase())
    suspend fun revokeRecipient(channel: String, account: String, recipient: String) =
        dao.deleteByScope(ApprovalLevel.RECIPIENT, "$channel|$account|$recipient")
    suspend fun revokeTask(taskId: String) = dao.deleteByScope(ApprovalLevel.TASK, taskId)

    /**
     * Is this call covered without asking? level is the tool's declared
     * approval level; context carries website / channel+account+recipient /
     * taskId as applicable.
     */
    suspend fun isCovered(
        level: String, tool: String = "", argsJson: String = "",
        website: String? = null, channel: String? = null,
        account: String? = null, recipient: String? = null,
        taskId: String? = null,
    ): Boolean = when (level) {
        ApprovalLevel.NONE -> true
        ApprovalLevel.ALWAYS_ASK -> false // purchases: never covered
        ApprovalLevel.ONCE -> {
            val g = dao.find(ApprovalLevel.ONCE, "$tool|${argsJson.hashCode()}")
            if (g != null) { dao.delete(g.id); true } else false
        }
        ApprovalLevel.SITE ->
            website != null && dao.find(ApprovalLevel.SITE, website.lowercase()) != null
        ApprovalLevel.RECIPIENT ->
            channel != null && account != null && recipient != null &&
                dao.find(ApprovalLevel.RECIPIENT, "$channel|$account|$recipient") != null
        ApprovalLevel.TASK ->
            taskId != null && dao.find(ApprovalLevel.TASK, taskId) != null
        else -> false
    }
}
