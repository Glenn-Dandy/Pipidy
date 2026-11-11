package de.kewl.pipidy.pipi

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.zip.Deflater

// Text formats of the Pipi lamp (dreame.light.r2229), as the Mi Home plugin 1009694 v33 writes them.
// Lists are entries joined by ';', fields joined by '-', names in Base64 so they never contain either.

/** Light mode, an entry of siid 4 piid 1: id-sort-active-builtIn-name-brightness-colourTemp-blinkStyle-blinkFreq. */
data class LightMode(
    val id: Int,
    val sort: Int,
    val active: Boolean,
    val builtIn: Boolean,
    val name: String,
    val brightness: Int,
    val colorTemp: Int,
    /** 0 steady, 1 rapid, 2 normal, 3 slow, 4 twice, 5 breathe. */
    val blinkStyle: Int,
    /** Index into [BLINK_COUNTS]. */
    val blinkFreq: Int,
) {
    /** The first seven modes carry fixed names that the plugin translates. */
    val hasFixedName get() = id in 1..BUILT_IN_MODES

    fun encode(): String =
        listOf(id, sort, flag(active), flag(builtIn), base64(name), brightness, colorTemp, blinkStyle, blinkFreq)
            .joinToString("-")

    companion object {
        const val BUILT_IN_MODES = 7
        const val MAX_ID = 127
        const val BLINK_STYLES = 5
        /** Blink counts behind the frequency index, -1 means without end. */
        val BLINK_COUNTS = listOf(1, 2, 5, 10, 20, 50, -1)

        fun parseList(raw: String?): List<LightMode> = entries(raw).mapNotNull { fields ->
            if (fields.size != 9) return@mapNotNull null
            LightMode(
                id = fields[0].toIntOrNull() ?: return@mapNotNull null,
                sort = fields[1].toIntOrNull() ?: 0,
                active = fields[2] == "1",
                builtIn = fields[3] == "1",
                name = unbase64(fields[4]),
                brightness = (fields[5].toIntOrNull() ?: 50).coerceIn(1, 100),
                colorTemp = (fields[6].toIntOrNull() ?: 4000).coerceIn(2700, 5700),
                blinkStyle = (fields[7].toIntOrNull() ?: 0).coerceIn(0, BLINK_STYLES),
                blinkFreq = (fields[8].toIntOrNull() ?: 0).coerceIn(0, BLINK_COUNTS.size - 1),
            )
        }.sortedBy { it.sort }

        /** Lowest free id, as the plugin picks it. */
        fun freeId(modes: List<LightMode>): Int? = (1..MAX_ID).firstOrNull { id -> modes.none { it.id == id } }
    }
}

/** Scheduled switching, an entry of siid 6 piid 2: id-enabled-HH:MM-days-repeat-action. */
data class Schedule(
    val id: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    /** Seven characters '0' or '1', Sunday first. All '0' means once. */
    val days: String,
    val turnOn: Boolean,
) {
    val once get() = '1' !in days

    fun encode(): String {
        val time = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        return "$id-${flag(enabled)}-$time-$days-${if (once) 0 else 1}-${if (turnOn) 0 else 1}"
    }

    companion object {
        const val ONCE = "0000000"
        const val DAILY = "1111111"
        const val WORKDAYS = "0111110"
        const val WEEKEND = "1000001"

        fun parseList(raw: String?): List<Schedule> = entries(raw).mapNotNull { fields ->
            if (fields.size != 6) return@mapNotNull null
            val time = fields[2].split(':')
            Schedule(
                id = fields[0].toIntOrNull() ?: return@mapNotNull null,
                enabled = fields[1] == "1",
                hour = time.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0,
                minute = time.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                days = fields[3].padStart(7, '0').takeLast(7),
                turnOn = fields[5] != "1",
            )
        }.sortedBy { it.hour * 60 + it.minute }

        fun nextId(schedules: List<Schedule>) = (schedules.maxOfOrNull { it.id } ?: 0) + 1
    }
}

/** Motion, an entry of siid 5 piid 3: id-builtIn-name. */
data class LampMotion(val id: Int, val builtIn: Boolean, val name: String) {
    companion object {
        fun parseList(raw: String?): List<LampMotion> = entries(raw).mapNotNull { fields ->
            if (fields.size != 3) return@mapNotNull null
            LampMotion(fields[0].toIntOrNull() ?: return@mapNotNull null, fields[1] == "1", unbase64(fields[2]))
        }

        fun nextId(motions: List<LampMotion>) = (motions.maxOfOrNull { it.id } ?: 0) + 1

        /** Entry that adds a recorded motion, or renames one when [path] is null. */
        fun encode(id: Int, name: String, path: List<PathPoint>?): String =
            "$id-0-${base64(name)}-" + (path?.let(::encodePath) ?: "")
    }
}

/**
 * One 50 ms step of a recorded motion. [x] right and [y] down from -100 to 100,
 * [light] the light mode to switch to in this step, -1 for light off, 0 for no change.
 */
data class PathPoint(val x: Int, val y: Int, val light: Int = 0)

const val PATH_STEP_MS = 50L
const val PATH_MAX_STEPS = 600

/** Positions are stored in steps of 2, which roughly halves the size of a busy path. */
const val PATH_GRID = 2

/**
 * The lamp drops a request of more than about 1350 bytes without answering, so a written
 * entry must stay well below that. Id and name of a motion take at most 40 characters.
 */
const val MAX_ENTRY_CHARS = 1150
const val MAX_PATH_CHARS = MAX_ENTRY_CHARS - 40

/** Snaps a position from -1 to 1 onto the grid of the stored path, -100 to 100. */
fun pathValue(position: Float): Int = Math.round(position.coerceIn(-1f, 1f) * 100 / PATH_GRID) * PATH_GRID

/** Three signed bytes per step, zlib-deflated, URL-safe Base64 with padding. */
fun encodePath(points: List<PathPoint>): String {
    val raw = ByteArray(points.size * 3)
    points.forEachIndexed { i, p ->
        raw[i * 3] = p.x.coerceIn(-100, 100).toByte()
        raw[i * 3 + 1] = p.y.coerceIn(-100, 100).toByte()
        raw[i * 3 + 2] = p.light.coerceIn(-128, 127).toByte()
    }
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(raw)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    return Base64.getEncoder().encodeToString(out.toByteArray()).replace('+', '-').replace('/', '_')
}

/** Error codes of siid 9 piid 1, given as "code:detail" pairs joined by ','. */
fun parseFaults(raw: String?): List<Int> =
    raw.orEmpty().split(',').mapNotNull { it.substringBefore(':').trim().toIntOrNull() }.filter { it != 0 }.distinct()

/** Days since the lamp's birthday (siid 3 piid 2, "yyyy-MM-dd"), null when it has none. */
fun daysSince(birthday: String, today: LocalDate = LocalDate.now()): Long? = try {
    ChronoUnit.DAYS.between(LocalDate.parse(birthday.trim()), today).coerceAtLeast(0)
} catch (_: DateTimeParseException) {
    null
}

/** The plugin's name rule: at most 8 units, where a character outside Latin-1 counts twice. */
fun nameUnits(name: String): Int = name.fold(0) { units, c -> units + if (c.code > 0xFF) 2 else 1 }

const val MAX_NAME_UNITS = 8

private fun entries(raw: String?): List<List<String>> =
    raw.orEmpty().split(';').filter { '-' in it }.map { it.split('-') }

private fun flag(value: Boolean) = if (value) 1 else 0

private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

private fun unbase64(text: String) = try {
    String(Base64.getDecoder().decode(text), Charsets.UTF_8)
} catch (_: IllegalArgumentException) {
    text
}
