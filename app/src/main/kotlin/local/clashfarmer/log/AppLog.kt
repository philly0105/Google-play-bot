package local.clashfarmer.log

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import local.clashfarmer.core.SessionSummary
import local.clashfarmer.core.SessionSnapshot

data class RunContextSnapshot(
    val activeAccountId: String? = null,
    val activeAccountName: String? = null,
    val currentAccountName: String? = null,
    val combinedRunId: String? = null,
    val combinedCompleted: Int = 0,
    val combinedTotal: Int = 0,
    val nextAccountName: String? = null
)

object AppLog {

    private var logFiles: DailyLogFile? = null
    private val recent = ArrayDeque<String>()
    private val summary = SessionSummary()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var currentAccountId: String? = null
    @Volatile
    private var currentAccountName: String? = null
    @Volatile
    private var activeAccountId: String? = null
    @Volatile
    private var activeAccountName: String? = null
    @Volatile
    private var combinedRunId: String? = null
    @Volatile
    private var combinedCompletedBeforeAccount: Int = 0
    @Volatile
    private var combinedCompleted: Int = 0
    @Volatile
    private var combinedTotal: Int = 0
    @Volatile
    private var nextAccountName: String? = null

    @Synchronized
    fun setAccountContext(accountId: String?, accountName: String?) {
        currentAccountId = accountId
        currentAccountName = accountName
    }

    @Synchronized
    fun clearAccountContext() {
        currentAccountId = null
        currentAccountName = null
    }

    @Synchronized
    fun markActiveAccount(accountId: String, accountName: String) {
        activeAccountId = accountId
        activeAccountName = accountName
    }

    @Synchronized
    fun clearActiveAccount() {
        activeAccountId = null
        activeAccountName = null
    }

    @Synchronized
    fun beginCombinedRun(runId: String, totalAttacks: Int) {
        combinedRunId = runId
        combinedCompletedBeforeAccount = 0
        combinedCompleted = 0
        combinedTotal = totalAttacks
        nextAccountName = null
    }

    @Synchronized
    fun beginCombinedAccount(completedBefore: Int, nextAccount: String?) {
        combinedCompletedBeforeAccount = completedBefore
        combinedCompleted = completedBefore
        nextAccountName = nextAccount
    }

    @Synchronized
    fun clearCombinedRun() {
        combinedRunId = null
        combinedCompletedBeforeAccount = 0
        combinedCompleted = 0
        combinedTotal = 0
        nextAccountName = null
    }

    @Synchronized
    fun runContextSnapshot(): RunContextSnapshot = RunContextSnapshot(
        activeAccountId = activeAccountId,
        activeAccountName = activeAccountName,
        currentAccountName = currentAccountName,
        combinedRunId = combinedRunId,
        combinedCompleted = combinedCompleted,
        combinedTotal = combinedTotal,
        nextAccountName = nextAccountName
    )

    /** Desktop: takes the app's files directory, which the phone read from its Context. */
    @Synchronized
    fun init(filesDir: File) {
        val logDir = File(filesDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        if (logFiles == null) {
            // Replay once, keeping only a bounded event list and one session summary in memory.
            try {
                replayRecentHistory(logDir.listFiles()?.filter { it.extension == "jsonl" }.orEmpty(), ::remember)
            } catch (e: Exception) {
                System.err.println("ClashFarmerLog: Previous session could not be read")
                e.printStackTrace()
            }
            logFiles = DailyLogFile(logDir)
        }
    }

    @Synchronized
    fun log(event: String, vararg fields: Pair<String, Any?>) {
        val files = logFiles ?: return
        try {
            val now = Date()
            val file = files.fileFor(now.time)
            val timestamp = dateFormat.format(now)
            val obj = JSONObject()
            obj.put("time", timestamp)
            obj.put("event", event)
            currentAccountId?.let { obj.put("accountId", it) }
            currentAccountName?.let { obj.put("accountName", it) }
            combinedRunId?.let { obj.put("combinedRunId", it) }
            for ((k, v) in fields) {
                obj.put(k, v ?: JSONObject.NULL)
            }
            remember(obj)
            if (event == "attack_completed" && combinedRunId != null) {
                combinedCompleted = combinedCompletedBeforeAccount + obj.optInt("completed", 0)
            }
            file.appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            System.err.println("ClashFarmerLog: Failed to write log")
            e.printStackTrace()
        }
    }

    @Synchronized
    fun readRecentLogs(maxLines: Int = 50): List<String> {
        return recent.takeLast(maxLines.coerceAtLeast(0))
    }

    @Synchronized
    fun sessionSnapshot(): SessionSnapshot = summary.snapshot

    private fun remember(event: JSONObject) {
        summary.accept(event)
        recent.addLast(event.toString())
        while (recent.size > 150) recent.removeFirst()
    }
}
