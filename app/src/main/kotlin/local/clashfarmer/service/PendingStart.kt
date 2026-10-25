package local.clashfarmer.service

import local.clashfarmer.core.RunPlan
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One run the service starts by itself at [atMs]. [plan] has its selected and other accounts
 * already resolved, so it runs on the accounts shown when it was set; [label] names them.
 * [asSet] is the plan as the composer had it, saved as the last plan once the run starts, so
 * Home's Start still follows the selected account afterwards.
 *
 * There is no alarm behind it, only a coroutine: if the service dies first the start is dropped,
 * and the next connect finds this file and says so.
 */
data class PendingStart(
    val plan: RunPlan,
    val atMs: Long,
    val setAtMs: Long,
    val label: String,
    val asSet: RunPlan = plan
) {

    fun toJson(): JSONObject = JSONObject()
        .put("plan", plan.toJson())
        .put("atMs", atMs)
        .put("setAtMs", setAtMs)
        .put("label", label)
        .put("asSet", asSet.toJson())

    companion object {
        const val MAX_AHEAD_MS = 12 * 60 * 60 * 1000L
        /**
         * How late a start may still run. The wait does not count while the phone sleeps, so a
         * start that wakes later than this was missed rather than run at some other hour.
         */
        const val LATE_MS = 2 * 60 * 1000L

        /** Why [atMs] cannot be a start time at [nowMs], or null when it can. */
        fun problem(atMs: Long, nowMs: Long): String? = when {
            atMs <= nowMs -> "That time has already passed"
            atMs - nowMs > MAX_AHEAD_MS -> "A start can be at most 12 hours ahead"
            else -> null
        }

        /** Why a start due at [atMs] must not run at [nowMs], or null when it is on time. */
        fun lateProblem(atMs: Long, nowMs: Long): String? =
            if (nowMs - atMs > LATE_MS) "The phone was asleep at the start time" else null

        /** The next [hour]:[minute] on the clock after [nowMs]: today while it is still ahead, else tomorrow. */
        fun nextAt(hour: Int, minute: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (!target.isAfter(now)) target = target.plusDays(1)
            return target.toInstant().toEpochMilli()
        }

        /** The start in [json], or null when it is not a whole one. */
        fun fromJson(json: JSONObject): PendingStart? = runCatching {
            val plan = RunPlan.fromJson(json.getJSONObject("plan")) ?: return null
            PendingStart(
                plan = plan,
                atMs = json.getLong("atMs"),
                setAtMs = json.getLong("setAtMs"),
                label = json.getString("label"),
                asSet = json.optJSONObject("asSet")?.let { RunPlan.fromJson(it) } ?: plan
            )
        }.getOrNull()
    }
}

/** files/pending-start.json: at most one start, written whole. Never throws. */
class PendingStartStore(root: File) {
    private val file = File(root, "pending-start.json")

    fun read(): PendingStart? = runCatching {
        if (!file.exists()) return null
        PendingStart.fromJson(JSONObject(file.readText()))
    }.getOrNull()

    /** False when the file could not be written; the start still holds in memory. */
    fun write(start: PendingStart): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(start.toJson().toString())
        local.clashfarmer.core.AtomicFile.replace(tmp, file) // Desktop: renameTo never replaces on Windows.
    }.isSuccess

    fun clear() {
        runCatching { file.delete() }
    }

    /** Whether a file is there at all, readable or not. */
    fun exists(): Boolean = runCatching { file.exists() }.getOrDefault(false)
}
