package com.billtt.riddle

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings.
 *
 * Several profiles, each pairing an endpoint with a persona, so the diary can be a different
 * character depending on which one is active. One OpenAI-compatible endpoint shape covers
 * every backend worth using here -- Zhipu, Aliyun DashScope compatible-mode, Moonshot,
 * SiliconFlow, Volcengine, OpenAI, and Anthropic's own compatibility endpoint.
 */
class Prefs(context: Context) {

    data class Profile(
        val name: String,
        val baseUrl: String,
        val model: String,
        val key: String,
        /** Blank means use OraclePrompts.PERSONA. */
        val persona: String,
    ) {
        val configured: Boolean get() = key.isNotBlank()
    }

    private val sp = context.getSharedPreferences("riddle", Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
        encryptAtRestIfNeeded()
        purgeLegacyPlaintext()
    }

    // ------------------------------------------------------------------ profiles

    var profiles: List<Profile>
        get() {
            val raw = sp.getString(KEY_PROFILES, null) ?: return listOf(blankProfile(1))
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Profile(
                        name = o.optString("name", "档案 ${i + 1}"),
                        baseUrl = o.optString("baseUrl", DEFAULT_BASE_URL),
                        model = o.optString("model", ""),
                        // Ciphertext on disk; decrypt() passes legacy plaintext through.
                        key = Secret.decrypt(o.optString("key", "")),
                        persona = o.optString("persona", ""),
                    )
                }
            }.getOrElse { listOf(blankProfile(1)) }.ifEmpty { listOf(blankProfile(1)) }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach {
                arr.put(
                    JSONObject()
                        .put("name", it.name)
                        .put("baseUrl", it.baseUrl)
                        .put("model", it.model)
                        .put("key", Secret.encrypt(it.key))
                        .put("persona", it.persona)
                )
            }
            sp.edit().putString(KEY_PROFILES, arr.toString()).apply()
        }

    var activeIndex: Int
        get() = sp.getInt(KEY_ACTIVE, 0).coerceIn(0, (profiles.size - 1).coerceAtLeast(0))
        set(value) = sp.edit().putInt(KEY_ACTIVE, value).apply()

    val active: Profile get() = profiles.getOrElse(activeIndex) { blankProfile(1) }

    fun updateProfile(index: Int, p: Profile) {
        val list = profiles.toMutableList()
        while (list.size <= index) list.add(blankProfile(list.size + 1))
        list[index] = p
        profiles = list
    }

    fun addProfile(): Int {
        val list = profiles.toMutableList()
        list.add(blankProfile(list.size + 1))
        profiles = list
        return list.size - 1
    }

    // ------------------------------------------------------------------ other settings

    /**
     * How long the pen must rest before a page is sent. Upstream used a flat 2800 ms; that
     * reads as a long dead pause in practice, so it is shorter by default and adjustable.
     * A double-tap sends immediately regardless.
     */
    var idleMs: Long
        get() = sp.getLong(KEY_IDLE_MS, DEFAULT_IDLE_MS)
        set(value) = sp.edit().putLong(KEY_IDLE_MS, value.coerceIn(400L, 20_000L)).apply()

    /** Whether the diary remembers: storage plus the catalog sent with each turn. */
    var memoryEnabled: Boolean
        get() = sp.getBoolean("memory_enabled", true)
        set(value) = sp.edit().putBoolean("memory_enabled", value).apply()

    val configured: Boolean get() = active.configured

    // ------------------------------------------------------------------ migration

    /**
     * Carry a working single-endpoint configuration into profile 1 rather than making the
     * user re-enter a key that already works.
     */
    private fun migrateIfNeeded() {
        if (sp.contains(KEY_PROFILES)) return
        val oldKey = sp.getString("openai_key", "").orEmpty()
        val oldModel = sp.getString("openai_model", "").orEmpty()
        val oldBase = sp.getString("openai_base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val migrated = Profile(
            name = "档案 1",
            baseUrl = oldBase,
            model = oldModel,
            key = oldKey.trim(),
            persona = "",
        )
        profiles = listOf(migrated)
        activeIndex = 0
        // Delete the legacy plaintext fields. Without this the encrypted copy and a
        // cleartext copy of the same key sit side by side, and encrypting was pointless.
        sp.edit()
            .remove("openai_key").remove("openai_model").remove("openai_base_url")
            .remove("api_key").remove("model").remove("provider")
            .apply()
    }

    /**
     * Remove legacy plaintext credential fields left behind by any earlier layout.
     *
     * Encrypting the key in the new profile is worthless while a cleartext copy of the same
     * key is still sitting in the same file under its old name. Runs on every start, not
     * just on migration, so a file written by an older build is cleaned up too.
     */
    private fun purgeLegacyPlaintext() {
        val stale = listOf("openai_key", "api_key").filter { sp.contains(it) }
        if (stale.isEmpty()) return
        val e = sp.edit()
        listOf("openai_key", "openai_model", "openai_base_url", "api_key", "model", "provider")
            .forEach { e.remove(it) }
        e.apply()
    }

    /**
     * Rewrite the stored profiles once if any key is still sitting there in plaintext.
     *
     * Without this, encryption would only take effect the next time the user happened to
     * open settings and press save -- a key that already works would stay readable on disk
     * indefinitely. The getter passes legacy plaintext through, so reading and writing back
     * is all it takes.
     */
    private fun encryptAtRestIfNeeded() {
        val raw = sp.getString(KEY_PROFILES, null) ?: return
        val needsRewrite = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).any { i ->
                val k = arr.getJSONObject(i).optString("key", "")
                k.isNotEmpty() && !Secret.isEncrypted(k)
            }
        }.getOrDefault(false)
        if (needsRewrite) profiles = profiles
    }

    private fun blankProfile(n: Int) = Profile("档案 $n", DEFAULT_BASE_URL, "", "", "")

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_IDLE_MS = "idle_ms"

        const val DEFAULT_BASE_URL = OpenAiOracle.DEFAULT_BASE_URL
        const val DEFAULT_IDLE_MS = 1500L
    }
}
