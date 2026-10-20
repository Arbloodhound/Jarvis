package com.agent.jarvis.harness.connectors

/**
 * Where connector secrets live (§1: "no credentials in code, logs, or memory; the agent only sees
 * opaque handles"). A secret is stored once and from then on referred to by a handle
 * (`cred:<connector>:<uuid>`). The handle is bound to the hosts it may be sent to; [AuthedTransport]
 * swaps it for the real value only at the network boundary and only for those hosts — the same
 * idea as Muse's surrogate credentials and OpenClaw's host-bound sealed secrets.
 *
 * The vault never returns a secret to agent-facing code: [reveal] is internal to this package and
 * is called by the transport alone.
 */
interface CredentialVault {
    /** Store [secret] for [connectorId], usable only against [hosts]. Returns the opaque handle. */
    fun store(connectorId: String, secret: String, hosts: Set<String>): String

    /** The hosts the handle may be sent to; empty when the handle is unknown. */
    fun hostsFor(handle: String): Set<String>

    /** The handle stored for [connectorId], or null. */
    fun handleFor(connectorId: String): String?

    /** Remove the secret (disconnect). */
    fun remove(handle: String)

    /** The secret itself. Only [AuthedTransport] calls this. */
    fun reveal(handle: String): String?
}

/** In-memory vault for tests and for a session that must not persist anything. */
class InMemoryVault : CredentialVault {
    private data class Entry(val connectorId: String, val secret: String, val hosts: Set<String>)
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    override fun store(connectorId: String, secret: String, hosts: Set<String>): String {
        require(hosts.isNotEmpty()) { "a credential must be bound to at least one host" }
        entries.entries.removeIf { it.value.connectorId == connectorId }
        val h = "cred:$connectorId:" + java.util.UUID.randomUUID().toString().take(12)
        entries[h] = Entry(connectorId, secret, hosts.map { it.lowercase() }.toSet())
        return h
    }
    override fun hostsFor(handle: String) = entries[handle]?.hosts ?: emptySet()
    override fun handleFor(connectorId: String) = entries.entries.firstOrNull { it.value.connectorId == connectorId }?.key
    override fun remove(handle: String) { entries.remove(handle) }
    override fun reveal(handle: String) = entries[handle]?.secret
}

/**
 * Android Keystore-backed vault: each secret is encrypted with an AES-GCM key that never leaves
 * the Keystore and stored in app-private preferences (excluded from backup by
 * data_extraction_rules). RUNTIME-UNPROVEN on the phone.
 */
class KeystoreVault(context: android.content.Context) : CredentialVault {
    private val prefs = context.getSharedPreferences("harness_vault", android.content.Context.MODE_PRIVATE)
    private val alias = "jarvis_harness_vault"

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    override fun store(connectorId: String, secret: String, hosts: Set<String>): String {
        require(hosts.isNotEmpty()) { "a credential must be bound to at least one host" }
        handleFor(connectorId)?.let(::remove)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val blob = android.util.Base64.encodeToString(c.iv + c.doFinal(secret.toByteArray()), android.util.Base64.NO_WRAP)
        val h = "cred:$connectorId:" + java.util.UUID.randomUUID().toString().take(12)
        prefs.edit().putString("$h|blob", blob).putString("$h|hosts", hosts.joinToString(",") { it.lowercase() })
            .putString("conn|$connectorId", h).apply()
        return h
    }
    override fun hostsFor(handle: String) =
        prefs.getString("$handle|hosts", null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    override fun handleFor(connectorId: String) = prefs.getString("conn|$connectorId", null)
    override fun remove(handle: String) {
        val conn = handle.split(':').getOrNull(1)
        prefs.edit().remove("$handle|blob").remove("$handle|hosts").apply {
            if (conn != null && prefs.getString("conn|$conn", null) == handle) remove("conn|$conn")
        }.apply()
    }
    override fun reveal(handle: String): String? = try {
        val raw = android.util.Base64.decode(prefs.getString("$handle|blob", null) ?: return null, android.util.Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)))
    } catch (_: Exception) { null }
}
