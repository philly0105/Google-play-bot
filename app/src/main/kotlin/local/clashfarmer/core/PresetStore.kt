package local.clashfarmer.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The run composer's saved plans, at most [MAX_PRESETS], and the last plan started, in
 * files/presets.json. Seeded on first read; a file that will not parse reads as the seeds and is
 * left in place until the next save replaces it. Every instance shares one lock: the activity,
 * the composer and the remote page each make their own, and all write through one tmp file.
 */
class PresetStore(root: File) {
    private val file = File(root, "presets.json")

    /** The saved presets, in order. */
    fun presets(): List<RunPlan> = synchronized(LOCK) {
        if (!file.exists()) {
            write(SEEDS, null)
            return SEEDS
        }
        return readFile()?.first ?: SEEDS
    }

    /**
     * Saves [plan] under its name, replacing a preset of the same name. False when it has no
     * name, when it is new and [MAX_PRESETS] are already saved, or when the write fails.
     */
    fun save(plan: RunPlan): Boolean = synchronized(LOCK) {
        val name = plan.name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val named = plan.copy(name = name)
        val current = presets()
        val at = current.indexOfFirst { it.name == name }
        val next = when {
            at >= 0 -> current.toMutableList().also { it[at] = named }
            current.size >= MAX_PRESETS -> return false
            else -> current + named
        }
        return write(next, last())
    }

    /** Renames preset [from] to [to]. False when [from] is missing, [to] is blank or taken, or the write fails. */
    fun rename(from: String, to: String): Boolean = synchronized(LOCK) {
        val name = to.trim().takeIf { it.isNotEmpty() } ?: return false
        val current = presets()
        val at = current.indexOfFirst { it.name == from }
        if (at < 0 || current.any { it.name == name && it.name != from }) return false
        return write(current.toMutableList().also { it[at] = it[at].copy(name = name) }, last())
    }

    /** Deletes preset [name]. False when there is none by that name, or the write fails. */
    fun delete(name: String): Boolean = synchronized(LOCK) {
        val current = presets()
        if (current.none { it.name == name }) return false
        return write(current.filter { it.name != name }, last())
    }

    /** The plan last started, for Home's Start button, or null before any. */
    fun last(): RunPlan? = synchronized(LOCK) { readFile()?.second }

    /** Records [plan] as the last started. False when the file could not be written; never throws. */
    fun setLast(plan: RunPlan): Boolean = synchronized(LOCK) { write(presets(), plan) }

    /** The presets and the last plan, or null when the file is missing or will not parse. */
    private fun readFile(): Pair<List<RunPlan>, RunPlan?>? = runCatching {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("presets")
        val presets = (0 until array.length()).map { RunPlan.fromJson(array.getJSONObject(it)) ?: return null }
        val last = root.optJSONObject("last")?.let { RunPlan.fromJson(it) }
        presets.take(MAX_PRESETS) to last
    }.getOrNull()

    /** Writes the file whole; false on any failure, so a full or read-only disk never stops a start. */
    private fun write(presets: List<RunPlan>, last: RunPlan?): Boolean = runCatching {
        val root = JSONObject().put("presets", JSONArray(presets.map { it.toJson() }))
        last?.let { root.put("last", it.toJson()) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }.isSuccess

    companion object {
        const val MAX_PRESETS = 6

        /** One lock for every instance; @Synchronized would lock each instance on its own. */
        private val LOCK = Any()

        /** The old Farm 40, Farm 2 h and combined buttons; each keeps the account's saved goal as they did. */
        val SEEDS: List<RunPlan> = listOf(
            RunPlan(
                accountIds = listOf(RunPlan.SELECTED),
                length = Length.Attacks(Policy.LONG_RUN_ATTACKS),
                walls = true,
                stopWhen = StopWhen.SAVED_GOAL,
                name = "Farm 40 + walls"
            ),
            RunPlan(
                accountIds = listOf(RunPlan.SELECTED),
                length = Length.Minutes(Policy.TIMED_RUN_MINUTES),
                walls = true,
                stopWhen = StopWhen.SAVED_GOAL,
                name = "2 h + walls"
            ),
            // Both accounts end on one 120-minute deadline, the old combined run's default.
            RunPlan(
                accountIds = listOf(RunPlan.SELECTED, RunPlan.OTHER),
                length = Length.Attacks(Policy.LONG_RUN_ATTACKS),
                secondAttacks = Policy.LONG_RUN_ATTACKS,
                walls = false,
                stopWhen = StopWhen.SAVED_GOAL,
                name = "Both accounts"
            )
        )
    }
}
