package local.clashfarmer.accounts

import local.clashfarmer.recognition.PhoneProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID

class AccountStore(private val root: File) {

    private val accountsDir = File(root, "accounts")
    private val indexFile = File(accountsDir, "index.json")

    fun ensureMigrated(): List<AccountRecord> {
        if (indexFile.exists()) {
            // Repeated ensureMigrated must not overwrite edited profiles or reset existing index
            return load()
        }

        if (!accountsDir.exists() && !accountsDir.mkdirs() && !accountsDir.isDirectory) {
            throw IOException("Failed to create accounts directory: ${accountsDir.absolutePath}")
        }

        val sweatRecord = AccountRecord(
            id = SWEAT_ID,
            villageName = SWEAT_VILLAGE_NAME,
            supercellName = SWEAT_SUPERCELL_NAME,
            playerTag = SWEAT_PLAYER_TAG,
            settings = AccountSettings(wallsEnabled = false)
        )

        val bloodRecord = AccountRecord(
            id = BLOOD_ID,
            villageName = BLOOD_VILLAGE_NAME,
            supercellName = BLOOD_SUPERCELL_NAME,
            playerTag = BLOOD_PLAYER_TAG,
            settings = AccountSettings(wallsEnabled = false)
        )

        val legacyProfileFile = File(root, "profile.json")
        if (legacyProfileFile.exists()) {
            val legacyProfile = PhoneProfile.loadFromFile(legacyProfileFile)

            // Sweat receives legacy profile; retain legacy file untouched
            val sweatProfileFile = File(File(accountsDir, SWEAT_ID), "profile.json")
            if (!sweatProfileFile.exists()) {
                atomicCopy(legacyProfileFile, sweatProfileFile)
            }

            // Copy legacy calibration/frozen.png only into Sweat
            val legacyCalibrationDir = File(root, "calibration")
            val legacyFrozen = File(legacyCalibrationDir, "frozen.png")
            if (legacyFrozen.exists()) {
                val sweatCalibrationDir = File(File(accountsDir, SWEAT_ID), "calibration")
                val sweatFrozen = File(sweatCalibrationDir, "frozen.png")
                if (!sweatFrozen.exists()) {
                    atomicCopy(legacyFrozen, sweatFrozen)
                }
            }

            // Copy common calibration to Blood, but Blood wallPoints must be empty and isVerified=false;
            // common wallConfig ROIs can be copied.
            val bloodProfileFile = File(File(accountsDir, BLOOD_ID), "profile.json")
            if (!bloodProfileFile.exists()) {
                val bloodProfile = legacyProfile.copy(
                    wallPoints = emptyList(),
                    isVerified = false
                )
                atomicWrite(bloodProfileFile, bloodProfile.toJson())
            }
        }

        saveIndex(SWEAT_ID, listOf(sweatRecord, bloodRecord))
        return load()
    }

    fun load(): List<AccountRecord> {
        if (!indexFile.exists()) {
            throw IllegalStateException("Accounts index file does not exist: ${indexFile.absolutePath}")
        }
        val content = indexFile.readText(Charsets.UTF_8)
        if (content.isBlank()) {
            throw IllegalStateException("Accounts index file is empty: ${indexFile.absolutePath}")
        }
        val json = try {
            JSONObject(content)
        } catch (e: Exception) {
            throw IllegalStateException("Malformed index JSON: ${e.message}", e)
        }

        if (!json.has("selectedId")) {
            throw IllegalStateException("Missing 'selectedId' in index JSON")
        }
        val selId = json.getString("selectedId")
        if (!UUID_REGEX.matches(selId)) {
            throw IllegalStateException("Invalid 'selectedId' format in index: '$selId'")
        }

        if (!json.has("accounts")) {
            throw IllegalStateException("Missing 'accounts' in index JSON")
        }
        val accArray = json.optJSONArray("accounts")
            ?: throw IllegalStateException("Field 'accounts' must be a JSON array")

        val accounts = mutableListOf<AccountRecord>()
        val seenIds = mutableSetOf<String>()
        val seenTags = mutableSetOf<String>()
        for (i in 0 until accArray.length()) {
            val item = accArray.optJSONObject(i)
                ?: throw IllegalStateException("Malformed account at index $i: expected JSON object")
            val record = try {
                AccountRecord.fromJson(item)
            } catch (e: Exception) {
                throw IllegalStateException("Invalid account record at index $i: ${e.message}", e)
            }
            if (!seenIds.add(record.id)) {
                throw IllegalStateException("Duplicate account id '${record.id}' in index JSON")
            }
            val normTag = record.playerTag.trim().uppercase(java.util.Locale.ROOT)
            if (!seenTags.add(normTag)) {
                throw IllegalStateException("Duplicate player tag '${record.playerTag}' in index JSON")
            }
            accounts.add(record)
        }

        if (accounts.none { it.id == selId }) {
            throw IllegalStateException("Selected account id '$selId' does not exist in accounts list")
        }

        return Collections.unmodifiableList(accounts)
    }

    fun selectedId(): String {
        if (!indexFile.exists()) {
            throw IllegalStateException("Accounts index file does not exist: ${indexFile.absolutePath}")
        }
        val content = indexFile.readText(Charsets.UTF_8)
        val json = try {
            JSONObject(content)
        } catch (e: Exception) {
            throw IllegalStateException("Malformed index JSON: ${e.message}", e)
        }
        val selId = json.optString("selectedId")
        if (selId.isNullOrBlank() || !UUID_REGEX.matches(selId)) {
            throw IllegalStateException("Invalid or missing 'selectedId' in index JSON: '$selId'")
        }
        // Verify consistency with loaded accounts
        load()
        return selId
    }

    fun select(id: String) {
        val accounts = load()
        if (accounts.none { it.id == id }) {
            throw IllegalArgumentException("Cannot select unknown account id: '$id'")
        }
        saveIndex(id, accounts)
    }

    fun update(account: AccountRecord) {
        account.validate()
        val accounts = load()
        val idx = accounts.indexOfFirst { it.id == account.id }
        if (idx == -1) {
            throw IllegalArgumentException("Cannot update unknown account id: '${account.id}'")
        }
        val updatedList = accounts.toMutableList()
        updatedList[idx] = account
        val currentSelected = selectedId()
        saveIndex(currentSelected, updatedList)
    }

    fun profileFile(id: String): File {
        validateId(id)
        return File(File(accountsDir, id), "profile.json")
    }

    fun calibrationDir(id: String): File {
        validateId(id)
        return File(File(accountsDir, id), "calibration")
    }

    fun readProfile(id: String): PhoneProfile? {
        validateId(id)
        val file = profileFile(id)
        if (!file.exists()) return null
        return PhoneProfile.loadFromFile(file)
    }

    fun saveProfile(id: String, profile: PhoneProfile) {
        validateId(id)
        profile.validate()
        val file = profileFile(id)
        synchronized(PROFILE_LOCK) {
            atomicWrite(file, profile.toJson())
        }
    }

    /**
     * Changes the saved profile by reading what is on disk now, not what some caller loaded
     * earlier. The wall phase moves its cursor, reorders its probe points and earns verification
     * from one run-start copy, and writing that copy back whole undid the write before it.
     */
    fun updateProfile(id: String, update: (PhoneProfile) -> PhoneProfile): PhoneProfile {
        validateId(id)
        val file = profileFile(id)
        synchronized(PROFILE_LOCK) {
            if (!file.exists()) throw IllegalStateException("No saved profile for account '$id'")
            val updated = update(PhoneProfile.loadFromFile(file))
            updated.validate()
            atomicWrite(file, updated.toJson())
            return updated
        }
    }

    /**
     * Saves what the calibration screen changed since it loaded [opened], and nothing else, so
     * a run that moved on while the screen was open keeps its progress.
     */
    fun saveCalibration(id: String, opened: PhoneProfile, edited: PhoneProfile): PhoneProfile {
        validateId(id)
        edited.validate()
        val file = profileFile(id)
        synchronized(PROFILE_LOCK) {
            if (!file.exists()) {
                atomicWrite(file, edited.toJson())
                return edited
            }
            return updateProfile(id) { current -> edited.editsOnto(opened, current) }
        }
    }

    /**
     * Clears the saved collectors at once, for a camera reference being retired: a run started
     * before Save would otherwise tap them where the old village had its collectors.
     * Returns null when the account has no saved profile yet.
     */
    fun clearCollectionTargets(id: String): PhoneProfile? {
        validateId(id)
        synchronized(PROFILE_LOCK) {
            if (!profileFile(id).exists()) return null
            return updateProfile(id) { it.withCollectionTargets(emptyList()) }
        }
    }

    private fun validateId(id: String) {
        if (!UUID_REGEX.matches(id)) {
            throw IllegalArgumentException("Invalid opaque UUID id format: '$id'")
        }
        try {
            UUID.fromString(id)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid opaque UUID id format: '$id'", e)
        }

        val allowedIds = if (indexFile.exists()) {
            load().map { it.id }.toSet() + INITIAL_MIGRATION_IDS
        } else {
            INITIAL_MIGRATION_IDS
        }

        if (id !in allowedIds) {
            throw IllegalArgumentException("Unknown account id '$id'; must be in loaded index or fixed migration IDs")
        }

        val accountDir = File(accountsDir, id).canonicalFile
        val expectedParent = accountsDir.canonicalFile
        if (accountDir.parentFile != expectedParent) {
            throw IllegalArgumentException("Path traversal detected for id: '$id'")
        }
    }

    private fun saveIndex(selectedId: String, accounts: List<AccountRecord>) {
        val rootObj = JSONObject()
        rootObj.put("selectedId", selectedId)
        val arr = JSONArray()
        for (acc in accounts) {
            arr.put(acc.toJson())
        }
        rootObj.put("accounts", arr)
        atomicWrite(indexFile, rootObj.toString(2))
    }

    private fun atomicWrite(target: File, content: String) {
        val parent = target.parentFile ?: throw IOException("No parent directory for $target")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Failed to create parent directory: $parent")
        }
        val tempFile = File.createTempFile(target.name + ".", ".tmp", parent)
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            val sourcePath = tempFile.toPath()
            val targetPath = target.toPath()
            try {
                Files.move(
                    sourcePath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            try {
                tempFile.delete()
            } catch (_: Throwable) {}
            throw e
        }
    }

    private fun atomicCopy(source: File, target: File) {
        if (!source.exists()) return
        val parent = target.parentFile ?: throw IOException("No parent directory for $target")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Failed to create parent directory: $parent")
        }
        val tempFile = File.createTempFile(target.name + ".", ".tmp", parent)
        try {
            source.copyTo(tempFile, overwrite = true)
            val sourcePath = tempFile.toPath()
            val targetPath = target.toPath()
            try {
                Files.move(
                    sourcePath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            try {
                tempFile.delete()
            } catch (_: Throwable) {}
            throw e
        }
    }

    companion object {
        val SWEAT_ID: String =
            UUID.nameUUIDFromBytes("local.clashfarmer.account.sweat".toByteArray(Charsets.UTF_8)).toString()
        val BLOOD_ID: String =
            UUID.nameUUIDFromBytes("local.clashfarmer.account.blood".toByteArray(Charsets.UTF_8)).toString()

        const val SWEAT_VILLAGE_NAME = "Sweat"
        const val SWEAT_SUPERCELL_NAME = "Philly0105"
        const val SWEAT_PLAYER_TAG = "#8YQCYG2RU"

        const val BLOOD_VILLAGE_NAME = "Blood"
        const val BLOOD_SUPERCELL_NAME = "VastMaNiSh"
        const val BLOOD_PLAYER_TAG = "#Y2G29YJQ8"

        val INITIAL_MIGRATION_IDS: Set<String> = setOf(SWEAT_ID, BLOOD_ID)

        /** One for the process: the service and the calibration screen each build their own store. */
        private val PROFILE_LOCK = Any()

        private val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
