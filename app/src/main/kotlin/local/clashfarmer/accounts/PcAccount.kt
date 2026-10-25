package local.clashfarmer.accounts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import local.clashfarmer.core.PresetStore
import local.clashfarmer.core.Region
import local.clashfarmer.core.RunPlan
import local.clashfarmer.core.Viewport
import local.clashfarmer.recognition.RecognizedBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop: the one account the PC runs, and what the bot knows of it. It sits in Blood's id slot,
 * so the owner's profile, calibration and history stay where they are. A fresh install starts it
 * as a neutral record, and the first supervised run reads the village name and tag off Clash's
 * Settings and keeps a crop of that strip, the identity template, in the account's own folder.
 * Every later account check compares Settings with that crop. Nothing about the owner's village
 * ships with the app.
 */
object PcAccount {
    /** Blood's slot, so the owner's files keep their place. */
    val ID: String = AccountStore.BLOOD_ID

    /** What a fresh install calls the account until the first supervised run reads it. */
    const val FRESH_VILLAGE_NAME = "Your village"
    const val FRESH_SUPERCELL_NAME = "Not read yet"
    const val FRESH_PLAYER_TAG = "#UNREAD"

    /**
     * The frame a template with no identity.json was cut at. The owner's template is the one the
     * app used to ship, cut at 1740x978 and copied into the data folder by hand.
     */
    val DEFAULT_TEMPLATE_FRAME = Viewport(0, 0, 1740, 978)

    /** An unattended start can't open Settings to learn the account, so it waits for a watched one. */
    const val ENROL_FIRST = "Run one supervised attack first so the bot can read your account."

    /**
     * The Settings tab opened, but no name and tag could be read off it. The strip shows them only
     * for an account linked to a Supercell ID; the language is checked on its own ([NOT_ENGLISH]).
     */
    const val IDENTITY_UNREADABLE =
        "Could not read your village name and tag in Clash's Settings. They show only when the account is " +
            "linked to a Supercell ID: in Clash, open Settings and connect Supercell ID, then run one supervised " +
            "attack again."

    /** Settings shows a language other than English, and the bot reads Clash's English words. */
    const val NOT_ENGLISH = "Set Clash's language to English (Settings > Language). The bot reads English text."

    /** Settings is open, but neither the strip nor the name is the village read on the first run. */
    const val NOT_THE_READ_VILLAGE =
        "Clash's Settings does not show the village the bot read. Switch Clash to that account and try again, " +
            "or press Read account again in Setup and run one supervised attack."

    /** Setup's button that drops the template ([forget]). */
    const val READ_AGAIN = "Read account again"

    /** A start for any account but this one. The account check would switch accounts in Clash. */
    fun otherAccountRefused(villageName: String) =
        "Clash Farmer runs one account on this PC, and $villageName is not it."

    /**
     * Writes an index holding only the neutral account, when there is no index yet, and says
     * whether it did. Run before [AccountStore.ensureMigrated], so the phone's seeding of its two
     * accounts never runs on a fresh install. An existing index, the owner's, is left alone.
     */
    fun seedIfFresh(filesDir: File): Boolean {
        val index = File(File(filesDir, "accounts"), "index.json")
        if (index.exists()) return false
        val record = AccountRecord(ID, FRESH_VILLAGE_NAME, FRESH_SUPERCELL_NAME, FRESH_PLAYER_TAG,
            AccountSettings(wallsEnabled = false))
        record.validate()
        val json = JSONObject().put("selectedId", ID).put("accounts", JSONArray().put(record.toJson()))
        atomicWrite(index, json.toString(2).toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * The presets a fresh install starts with. The phone's seeds build walls, which a new account
     * has not set up, and one runs "Both accounts", which a PC with one account can't start.
     */
    val FRESH_PRESETS: List<RunPlan> = listOf(
        PresetStore.SEEDS[0].copy(walls = false, name = "Farm 40"),
        PresetStore.SEEDS[1].copy(walls = false, name = "2 h")
    )

    /**
     * Swaps the phone's seeded presets for [FRESH_PRESETS] when there is no presets.json yet, and
     * says whether it did. It goes through PresetStore's own calls, so that copied file stays as
     * it was. The new presets are saved before the seeds go, so a stop half way leaves too many
     * presets rather than none. An existing file, the owner's, is left alone.
     */
    fun seedPresetsIfFresh(filesDir: File): Boolean {
        if (File(filesDir, "presets.json").exists()) return false
        val store = PresetStore(filesDir)
        val seeds = store.presets()
        FRESH_PRESETS.forEach { store.save(it) }
        seeds.mapNotNull { it.name }.filter { name -> FRESH_PRESETS.none { it.name == name } }.forEach { store.delete(it) }
        return true
    }

    /** The account's identity template, a crop of the Settings strip. */
    fun templateFile(filesDir: File, id: String): File = File(accountDir(filesDir, id), "identity.png")

    /** The frame size [templateFile] was cut at. */
    fun templateFrameFile(filesDir: File, id: String): File = File(accountDir(filesDir, id), "identity.json")

    /**
     * Whether the account has a template that decodes, so its check can run unwatched. A damaged
     * one counts as none: the next supervised run reads the account again and writes over it.
     */
    fun enrolled(filesDir: File, id: String): Boolean =
        loadTemplate(filesDir, id)?.also { it.bitmap.recycle() } != null

    /**
     * Drops the account's template, so the next supervised run reads the account again. The
     * template goes first, since it is what marks the account read.
     */
    fun forget(filesDir: File, id: String) {
        templateFile(filesDir, id).delete()
        templateFrameFile(filesDir, id).delete()
    }

    /**
     * Undoes a read the account check did not confirm: drops the template and puts [before], the
     * record as it was before the read, back. Says whether there was anything to undo.
     */
    fun undoRead(filesDir: File, before: AccountRecord): Boolean {
        val store = AccountStore(filesDir)
        val now = runCatching { store.load().firstOrNull { it.id == before.id } }.getOrNull()
        if (!templateFile(filesDir, before.id).exists() && now == before) return false
        forget(filesDir, before.id)
        runCatching { store.update(before) }
        return true
    }

    /** A template and the size of the frame it was cut from. */
    class Template(val bitmap: Bitmap, val frame: Viewport)

    /**
     * The account's template, or null before its first read or when the file does not decode.
     * A missing or unreadable identity.json means [DEFAULT_TEMPLATE_FRAME]: only the owner's
     * copied template lacks one.
     */
    fun loadTemplate(filesDir: File, id: String): Template? {
        val file = templateFile(filesDir, id)
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        val frame = runCatching {
            val json = JSONObject(templateFrameFile(filesDir, id).readText(Charsets.UTF_8))
            Viewport(0, 0, json.getInt("frameWidth"), json.getInt("frameHeight"))
        }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 } ?: DEFAULT_TEMPLATE_FRAME
        return Template(bitmap, frame)
    }

    /**
     * Cuts [region] out of [screen] at its own size and keeps it as the account's template, with
     * the frame size beside it. The frame file goes first: the template's arrival is what marks
     * the account read, so a stop between the two reads it again next time.
     */
    fun saveTemplate(filesDir: File, id: String, screen: Bitmap, region: Region) {
        val frame = JSONObject()
            .put("frameWidth", screen.width)
            .put("frameHeight", screen.height)
            .put("region", JSONArray(listOf(region.left, region.top, region.right, region.bottom)))
        atomicWrite(templateFrameFile(filesDir, id), frame.toString(2).toByteArray(Charsets.UTF_8))
        val crop = Bitmap.createBitmap(screen, region.left, region.top, region.width, region.height)
        val png = try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { crop.recycle() }
        atomicWrite(templateFile(filesDir, id), png)
    }

    /**
     * The village name and tag on the Settings strip, read by [AccountIdentityParser] after the
     * line that starts with # is tried as the tag: a village name with digits in it passes the
     * parser's own tag rule. The tag keeps its #, loses OCR's spaces, and must look like a tag.
     */
    fun readIdentity(boxes: List<RecognizedBox>): SettingsIdentity? {
        val lines = boxes.filter { it.text.isNotBlank() }.sortedBy { it.top }
        val hash = lines.indexOfFirst { it.text.trim().startsWith("#") }
        val read = if (hash > 0) SettingsIdentity(lines[hash - 1].text.trim(), lines[hash].text)
            else AccountIdentityParser.parseSettingsIdentity(lines) ?: return null
        val tag = AccountIdentityParser.normalizeTag(read.playerTag).replace(" ", "")
        val name = read.villageName.trim()
        if (!TAG.matches(tag) || name.isBlank() || AccountIdentityParser.normalizeTag(name).replace(" ", "") == tag) return null
        return SettingsIdentity(name, "#$tag")
    }

    /**
     * Whether the Settings tab's language row reads English: false when it names another language,
     * null when the row is not read (no Language label, or nothing under it yet, as while the tab
     * slides in). The value is the box right under the label. OCR mixes case ("ENglisH") and can
     * misread a letter, and a wrong stop would block the owner's read, so a value that shares four
     * letters in order with ENGLISH counts as English. The other languages' own names (Deutsch,
     * Español, Français, Nederlands and the rest) share at most three.
     */
    fun englishLanguage(boxes: List<RecognizedBox>): Boolean? {
        val label = boxes.firstOrNull { AccountIdentityParser.normalize(it.text) == "LANGUAGE" } ?: return null
        val height = label.bottom - label.top
        val value = boxes.filter {
            it !== label && it.top >= label.bottom - height / 2 && it.top <= label.bottom + height * 2 &&
                it.right > label.left && it.left < label.right
        }.minByOrNull { it.top } ?: return null
        val letters = value.text.filter { it.isLetter() }.uppercase()
        if (letters.length < 2) return null
        return inOrder(letters, "ENGLISH") >= 4
    }

    /** How many letters [a] and [b] share in the same order (their longest common subsequence). */
    private fun inOrder(a: String, b: String): Int {
        var previous = IntArray(b.length + 1)
        for (x in a) {
            val row = IntArray(b.length + 1)
            for (j in b.indices) row[j + 1] = if (x == b[j]) previous[j] + 1 else maxOf(previous[j + 1], row[j])
            previous = row
        }
        return previous[b.length]
    }

    private val TAG = Regex("^[0-9A-Z]{4,14}$")

    private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private fun accountDir(filesDir: File, id: String): File {
        require(UUID_REGEX.matches(id)) { "Invalid account id: '$id'" }
        return File(File(filesDir, "accounts"), id)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("No parent directory for $target")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Failed to create parent directory: $parent")
        }
        val temp = File.createTempFile(target.name + ".", ".tmp", parent)
        try {
            temp.writeBytes(bytes)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }
}
