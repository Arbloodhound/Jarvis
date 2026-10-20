package com.agent.jarvis.harness

import org.json.JSONObject
import java.io.File

/**
 * §5 Artifacts — documents, pages and small apps the user keeps and comes back to. Unlike a chat
 * widget (transient), an artifact persists: every edit is a new version (nothing is overwritten),
 * it can be listed and reopened, shared and unshared, and deleting it moves it to the [Trash].
 *
 * Sharing is the user's decision: [share] needs a [ShareConsent] the app mints from the user's own
 * request or tap; the agent can't share on its own. The actual publishing (a link, a file export)
 * is done by the app; this records the state. Files under [root]/<slug>/; JVM-tested.
 */
class Artifacts(private val root: File, private val trash: AgentTrash) {

    enum class Kind(val ext: String) { DOCUMENT("md"), PAGE("html"), APP("html"), DATA("json") }

    data class Artifact(val slug: String, val name: String, val kind: Kind, val versions: Int,
                        val createdAtMs: Long, val updatedAtMs: Long, val shared: Boolean)

    class ShareConsent internal constructor(val slug: String)

    /** The app calls this from the user's share tap (or their explicit request, read verbatim). */
    fun userAskedToShare(slug: String) = ShareConsent(slug)

    fun create(name: String, kind: Kind, content: String, nowMs: Long = System.currentTimeMillis()): Artifact {
        require(name.isNotBlank()) { "an artifact needs a name" }
        val slug = uniqueSlug(slugOf(name))
        val dir = File(root, slug).apply { mkdirs() }
        File(dir, "v1.${kind.ext}").writeText(content)
        val a = Artifact(slug, name.trim(), kind, 1, nowMs, nowMs, false)
        writeMeta(a)
        return a
    }

    /** A new version; the old ones stay. */
    fun edit(slug: String, content: String, nowMs: Long = System.currentTimeMillis()): Artifact {
        val a = get(slug) ?: throw IllegalArgumentException("no artifact $slug")
        val n = a.versions + 1
        File(File(root, slug), "v$n.${a.kind.ext}").writeText(content)
        return a.copy(versions = n, updatedAtMs = nowMs).also(::writeMeta)
    }

    fun content(slug: String, version: Int? = null): String? {
        val a = get(slug) ?: return null
        val v = version ?: a.versions
        return File(File(root, slug), "v$v.${a.kind.ext}").takeIf { it.isFile }?.readText()
    }

    fun get(slug: String): Artifact? = try {
        val o = JSONObject(File(File(root, slug), "meta.json").readText())
        Artifact(o.getString("slug"), o.getString("name"), Kind.valueOf(o.getString("kind")), o.getInt("versions"),
            o.getLong("createdAtMs"), o.getLong("updatedAtMs"), o.optBoolean("shared"))
    } catch (_: Exception) { null }

    fun list(): List<Artifact> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
        .mapNotNull { get(it.name) }.sortedByDescending { it.updatedAtMs }

    fun share(consent: ShareConsent): Artifact {
        val a = get(consent.slug) ?: throw IllegalArgumentException("no artifact ${consent.slug}")
        return a.copy(shared = true).also(::writeMeta)
    }

    fun unshare(slug: String): Artifact? = get(slug)?.copy(shared = false)?.also(::writeMeta)

    /** Recoverable: the whole artifact (all versions) goes to the trash. Returns the trash id. */
    fun delete(slug: String): String {
        val dir = File(root, slug)
        require(dir.isDirectory) { "no artifact $slug" }
        return trash.move(dir)
    }

    private fun writeMeta(a: Artifact) {
        File(File(root, a.slug), "meta.json").writeText(JSONObject().put("slug", a.slug).put("name", a.name)
            .put("kind", a.kind.name).put("versions", a.versions).put("createdAtMs", a.createdAtMs)
            .put("updatedAtMs", a.updatedAtMs).put("shared", a.shared).toString())
    }

    private fun slugOf(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifEmpty { "artifact" }

    private fun uniqueSlug(base: String): String {
        var s = base; var n = 2
        while (File(root, s).exists()) { s = "$base-$n"; n++ }
        return s
    }
}
