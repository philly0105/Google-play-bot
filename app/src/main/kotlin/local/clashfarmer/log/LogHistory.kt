package local.clashfarmer.log

import org.json.JSONObject
import java.io.File

/** Include idle days after the latest session without replaying every past run. */
fun replayRecentHistory(files: List<File>, consume: (JSONObject) -> Unit) {
    val selected = mutableListOf<File>()
    for (file in files.sortedByDescending { it.name }) {
        selected.add(file)
        val containsSession = file.useLines { lines ->
            lines.any { line ->
                runCatching { JSONObject(line).optString("event") == "session_started" }.getOrDefault(false)
            }
        }
        if (containsSession) break
    }
    for (file in selected.asReversed()) {
        file.useLines { lines ->
            lines.forEach { line ->
                val event = runCatching { JSONObject(line) }.getOrNull()
                if (event != null) consume(event)
            }
        }
    }
}
