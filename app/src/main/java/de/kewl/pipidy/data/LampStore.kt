package de.kewl.pipidy.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

enum class LampKind(val label: String) {
    PIPI("Pipi-Lampe"),
    YEELIGHT("Yeelight"),
}

data class LampConfig(
    val id: String,
    val name: String,
    val kind: LampKind,
    val host: String,
    val token: String = "",
)

/** Keeps the lamp list in the private app preferences. */
class LampStore(private val prefs: SharedPreferences) {

    fun load(): List<LampConfig> {
        val raw = prefs.getString(KEY_LAMPS, null) ?: return migrate()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val kind = LampKind.entries.firstOrNull { it.name == item.optString("kind") } ?: return@mapNotNull null
                LampConfig(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    kind = kind,
                    host = item.getString("host"),
                    token = item.optString("token"),
                )
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    fun save(lamps: List<LampConfig>) {
        val array = JSONArray()
        lamps.forEach { lamp ->
            array.put(
                JSONObject()
                    .put("id", lamp.id)
                    .put("name", lamp.name)
                    .put("kind", lamp.kind.name)
                    .put("host", lamp.host)
                    .put("token", lamp.token)
            )
        }
        prefs.edit().putString(KEY_LAMPS, array.toString()).apply()
    }

    /** Version 0.1 knew exactly one Pipi lamp, stored as host and token. */
    private fun migrate(): List<LampConfig> {
        val token = prefs.getString(LEGACY_TOKEN, null) ?: return emptyList()
        val host = prefs.getString(LEGACY_HOST, null) ?: return emptyList()
        val lamps = listOf(LampConfig(newId(), "Pipi", LampKind.PIPI, host, token))
        save(lamps)
        prefs.edit().remove(LEGACY_TOKEN).remove(LEGACY_HOST).apply()
        return lamps
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        private const val KEY_LAMPS = "lamps"
        private const val LEGACY_HOST = "host"
        private const val LEGACY_TOKEN = "token"
    }
}
