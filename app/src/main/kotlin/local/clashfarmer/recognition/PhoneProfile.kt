package local.clashfarmer.recognition

import local.clashfarmer.core.CollectionPolicy
import local.clashfarmer.core.CollectionTarget
import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.Point
import local.clashfarmer.core.Region
import local.clashfarmer.core.Resource
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Step
import local.clashfarmer.core.WallCursor
import local.clashfarmer.core.WallRows
import local.clashfarmer.core.Viewport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WallCurrencyConfig(
    val costRoi: Region,
    val buttonPoint: Point,
    val iconRoi: Region? = null,
    val iconFile: String? = null,
    val confirmationRoi: Region? = null,
    val confirmationButton: Point? = null
)

data class WallConfig(
    val currencies: Map<String, WallCurrencyConfig> = emptyMap(),
    val bulkButton: Point? = null,
    val removeButton: Point? = null,
    val titleRoi: Region? = null,
    val bulkConfirmationBodyRoi: Region? = null,
    val bulkConfirmationButton: Point? = null,
    val resourceRois: Map<String, Region> = emptyMap()
)

data class PhoneProfile(
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val viewport: Viewport,
    val stateAnchors: Map<String, Point> = emptyMap(),
    val troopAnchors: Map<String, Point> = emptyMap(),
    val troopRois: Map<String, Region> = emptyMap(),
    val deploymentPoints: List<Point> = emptyList(),
    val perimeterSegments: List<Pair<Point, Point>> = emptyList(),
    val heroDrop: Point? = null,
    /**
     * [wallPoints], [wallRows] and [wallCursor]: nothing selects or taps a wall by these any more,
     * since walls are chosen only from the builder's list. They are kept only so older
     * profile.json files round-trip, and [validate] does not check them.
     */
    val wallPoints: List<Point> = emptyList(),
    val wallRows: WallRows? = null,
    val wallCursor: WallCursor = WallCursor(0, 0),
    val wallConfig: WallConfig? = null,
    /** Where each village balance is drawn on HOME. A resource without one cannot be a goal. */
    val homeResourceRois: Map<Resource, Region> = emptyMap(),
    /** Each row of the scout's Available Loot panel. A resource without one is never read. */
    val scoutLootRois: Map<Resource, Region> = emptyMap(),
    /** The scout's "29s" countdown. Without it Next is never pressed. */
    val scoutCountdownRoi: Region? = null,
    /** The gold price printed under Next, without its coin. Without it Next is never pressed. */
    val scoutFeeRoi: Region? = null,
    /** Collectors and mines to tap, on the account's camera reference. Empty means no collection. */
    val collectionTargets: List<CollectionTarget> = emptyList(),
    val isVerified: Boolean = false
) {

    fun validate() {
        if (resolutionWidth <= 0 || resolutionHeight <= 0) {
            throw StopException("Invalid resolution: ${resolutionWidth}x${resolutionHeight}")
        }
        if (viewport.left < 0 || viewport.top < 0 || viewport.right > resolutionWidth ||
            viewport.bottom > resolutionHeight || viewport.width <= 0 || viewport.height <= 0
        ) {
            throw StopException("Viewport $viewport out of bounds for resolution ${resolutionWidth}x${resolutionHeight}")
        }
        for ((name, pt) in stateAnchors) {
            if (!viewport.contains(pt)) {
                throw StopException("State anchor '$name' at $pt is outside viewport $viewport")
            }
        }
        for ((name, pt) in troopAnchors) {
            if (!viewport.contains(pt)) {
                throw StopException("Troop anchor '$name' at $pt is outside viewport $viewport")
            }
        }
        for ((name, roi) in troopRois) {
            if (!viewport.contains(roi.left, roi.top) || roi.right > viewport.right || roi.bottom > viewport.bottom) {
                throw StopException("Troop ROI '$name' at $roi is outside viewport $viewport")
            }
        }
        for (pt in deploymentPoints) {
            if (!viewport.contains(pt)) {
                throw StopException("Deployment point at $pt is outside viewport $viewport")
            }
        }
        for ((p1, p2) in perimeterSegments) {
            if (!viewport.contains(p1) || !viewport.contains(p2)) {
                throw StopException("Perimeter segment ($p1, $p2) is outside viewport $viewport")
            }
        }
        heroDrop?.let {
            if (!viewport.contains(it)) {
                throw StopException("Hero drop point at $it is outside viewport $viewport")
            }
        }
        if (collectionTargets.size > CollectionPolicy.MAX_TARGETS) {
            throw StopException("${collectionTargets.size} collection targets; at most ${CollectionPolicy.MAX_TARGETS}")
        }
        for (target in collectionTargets) {
            if (!viewport.contains(target.point)) {
                throw StopException("Collection target '${target.name}' at ${target.point} is outside viewport $viewport")
            }
        }
    }

    fun validateForAttack() {
        validate()
        val missing = mutableListOf<String>()
        if (!stateAnchors.containsKey("attack")) missing.add("attack anchor")
        if (!stateAnchors.containsKey("find_match")) missing.add("find_match anchor")
        if (!stateAnchors.containsKey("return_home")) missing.add("return_home anchor")
        if (!troopAnchors.containsKey("valkyries") && !troopAnchors.containsKey("valkyrie") && !troopAnchors.containsKey("troop_1")) {
            missing.add("valkyries troop anchor")
        }
        if (!troopAnchors.containsKey("rages") && !troopAnchors.containsKey("rage") && !troopAnchors.containsKey("troop_2")) {
            missing.add("rages spell anchor")
        }
        if (perimeterSegments.size < 4 && deploymentPoints.size < 2) {
            missing.add("4 perimeter segments (or 2+ deployment points)")
        }
        if (missing.isNotEmpty()) {
            throw StopException("Attack workflow configuration incomplete. Missing: ${missing.joinToString(", ")}")
        }
    }

    fun validateForWalls() {
        validate()
        val missing = mutableListOf<String>()
        if (!stateAnchors.containsKey("village_neutral")) missing.add("village_neutral anchor")
        if (!homeResourceRois.containsKey(Resource.GOLD)) missing.add("home gold storage ROI")
        if (!homeResourceRois.containsKey(Resource.ELIXIR)) missing.add("home elixir storage ROI")
        if (missing.isNotEmpty()) {
            throw StopException("Wall workflow configuration incomplete. Missing: ${missing.joinToString(", ")}")
        }
    }

    /**
     * This profile's changes since [baseline], laid over [current].
     *
     * Each field this copy did not change is taken from [current], so progress saved while the
     * calibration screen was open survives. Any calibration change leaves the result unverified;
     * with none, [current]'s verification stands.
     */
    fun editsOnto(baseline: PhoneProfile, current: PhoneProfile): PhoneProfile {
        fun <T> pick(field: (PhoneProfile) -> T): T =
            if (field(this) != field(baseline)) field(this) else field(current)
        val merged = PhoneProfile(
            resolutionWidth = pick { it.resolutionWidth },
            resolutionHeight = pick { it.resolutionHeight },
            viewport = pick { it.viewport },
            stateAnchors = pick { it.stateAnchors },
            troopAnchors = pick { it.troopAnchors },
            troopRois = pick { it.troopRois },
            deploymentPoints = pick { it.deploymentPoints },
            perimeterSegments = pick { it.perimeterSegments },
            heroDrop = pick { it.heroDrop },
            wallPoints = pick { it.wallPoints },
            wallRows = pick { it.wallRows },
            wallCursor = pick { it.wallCursor },
            wallConfig = pick { it.wallConfig },
            homeResourceRois = pick { it.homeResourceRois },
            scoutLootRois = pick { it.scoutLootRois },
            scoutCountdownRoi = pick { it.scoutCountdownRoi },
            scoutFeeRoi = pick { it.scoutFeeRoi },
            collectionTargets = pick { it.collectionTargets },
            isVerified = current.isVerified
        )
        val calibrationEdited = copy(
            wallCursor = baseline.wallCursor,
            collectionTargets = baseline.collectionTargets,
            isVerified = baseline.isVerified
        ) != baseline
        return if (calibrationEdited) merged.copy(isVerified = false) else merged
    }

    fun withAnchor(name: String, point: Point): PhoneProfile {
        val updated = stateAnchors.toMutableMap()
        updated[name] = point
        return copy(stateAnchors = updated, isVerified = false)
    }

    fun withTroopAnchor(name: String, point: Point): PhoneProfile {
        val updated = troopAnchors.toMutableMap()
        updated[name] = point
        return copy(troopAnchors = updated, isVerified = false)
    }

    fun withTroopRoi(name: String, roi: Region): PhoneProfile {
        val updated = troopRois.toMutableMap()
        updated[name] = roi
        return copy(troopRois = updated, isVerified = false)
    }

    /**
     * A troop, spell or hero card tapped in calibration: the tap, and the card area centred on it
     * that the attack's card reads and the tray layout read. A tap that moved only the anchor left
     * those reads on the old card. The area is 67 px wide, as the areas measured by hand in
     * September are, and [tap] gives no height, so the top and bottom come from the slot's own
     * area, else the Valkyries', else a card's height around the tap. On the PC's frames the area
     * is the PC's card size ([PC_CARD_LEFT]).
     */
    fun withTroopCard(name: String, tap: Point): PhoneProfile {
        val rows = troopRois[name] ?: troopRois["valkyries"]
        fun size(phone: Int, pc: Int) = PcGeometry.pick(viewport, phone, PcGeometry.length(viewport, pc))
        val area = Region(
            (tap.x - size(CARD_LEFT, PC_CARD_LEFT)).coerceAtLeast(viewport.left),
            rows?.top ?: (tap.y - size(CARD_ABOVE, PC_CARD_ABOVE)).coerceAtLeast(viewport.top),
            (tap.x + size(CARD_RIGHT, PC_CARD_RIGHT)).coerceAtMost(viewport.right),
            rows?.bottom ?: (tap.y + size(CARD_BELOW, PC_CARD_BELOW)).coerceAtMost(viewport.bottom)
        )
        return withTroopAnchor(name, tap).withTroopRoi(name, area)
    }

    fun withPerimeterSegment(index: Int, start: Point, end: Point): PhoneProfile {
        val list = perimeterSegments.toMutableList()
        while (list.size <= index) {
            list.add(Pair(Point(0, 0), Point(0, 0)))
        }
        list[index] = Pair(start, end)
        return copy(perimeterSegments = list, isVerified = false)
    }

    /**
     * Replaces the collection targets. The attack and wall phases never read them, so, like the
     * wall cursor, they leave verification alone.
     */
    fun withCollectionTargets(targets: List<CollectionTarget>): PhoneProfile {
        return copy(collectionTargets = targets)
    }

    fun withHeroDrop(point: Point): PhoneProfile {
        return copy(heroDrop = point, isVerified = false)
    }

    fun withViewport(newViewport: Viewport): PhoneProfile {
        return copy(viewport = newViewport, isVerified = false)
    }

    fun saveToFile(file: File) {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(toJson())
        // Desktop: renameTo never replaces on Windows, so the move replaces in place.
        try {
            local.clashfarmer.core.AtomicFile.replace(tempFile, file)
        } catch (e: java.io.IOException) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("resolutionWidth", resolutionWidth)
        root.put("resolutionHeight", resolutionHeight)

        val vpObj = JSONObject()
        vpObj.put("left", viewport.left)
        vpObj.put("top", viewport.top)
        vpObj.put("right", viewport.right)
        vpObj.put("bottom", viewport.bottom)
        root.put("viewport", vpObj)

        val stateObj = JSONObject()
        for ((k, v) in stateAnchors) {
            val ptObj = JSONObject().apply {
                put("x", v.x)
                put("y", v.y)
            }
            stateObj.put(k, ptObj)
        }
        root.put("stateAnchors", stateObj)

        val troopObj = JSONObject()
        for ((k, v) in troopAnchors) {
            val ptObj = JSONObject().apply {
                put("x", v.x)
                put("y", v.y)
            }
            troopObj.put(k, ptObj)
        }
        root.put("troopAnchors", troopObj)

        val troopRoisObj = JSONObject()
        for ((k, v) in troopRois) {
            val roiObj = JSONObject().apply {
                put("left", v.left)
                put("top", v.top)
                put("right", v.right)
                put("bottom", v.bottom)
            }
            troopRoisObj.put(k, roiObj)
        }
        root.put("troopRois", troopRoisObj)

        val deployArr = JSONArray()
        for (pt in deploymentPoints) {
            deployArr.put(JSONObject().apply {
                put("x", pt.x)
                put("y", pt.y)
            })
        }
        root.put("deploymentPoints", deployArr)

        val perimArr = JSONArray()
        for ((p1, p2) in perimeterSegments) {
            val segObj = JSONObject().apply {
                put("start", JSONObject().apply { put("x", p1.x); put("y", p1.y) })
                put("end", JSONObject().apply { put("x", p2.x); put("y", p2.y) })
            }
            perimArr.put(segObj)
        }
        root.put("perimeterSegments", perimArr)

        heroDrop?.let {
            root.put("heroDrop", JSONObject().apply { put("x", it.x); put("y", it.y) })
        }

        val wallArr = JSONArray()
        for (pt in wallPoints) {
            wallArr.put(JSONObject().apply {
                put("x", pt.x)
                put("y", pt.y)
            })
        }
        root.put("wallPoints", wallArr)

        wallRows?.let { r ->
            root.put("wallRows", JSONObject().apply {
                put("origin", JSONObject().apply { put("x", r.origin.x); put("y", r.origin.y) })
                put("alongStep", JSONObject().apply { put("dx", r.alongStep.dx); put("dy", r.alongStep.dy) })
                put("rowStep", JSONObject().apply { put("dx", r.rowStep.dx); put("dy", r.rowStep.dy) })
                put("rows", r.rows)
                put("wallsPerRow", r.wallsPerRow)
            })
        }
        root.put("wallCursor", JSONObject().apply {
            put("row", wallCursor.row)
            put("index", wallCursor.index)
        })

        wallConfig?.let { wc ->
            val wcObj = JSONObject()
            val currObj = JSONObject()
            for ((cName, cCfg) in wc.currencies) {
                val cc = JSONObject()
                cc.put("costRoi", JSONObject().apply {
                    put("left", cCfg.costRoi.left)
                    put("top", cCfg.costRoi.top)
                    put("right", cCfg.costRoi.right)
                    put("bottom", cCfg.costRoi.bottom)
                })
                cc.put("buttonPoint", JSONObject().apply {
                    put("x", cCfg.buttonPoint.x)
                    put("y", cCfg.buttonPoint.y)
                })
                cCfg.iconRoi?.let { r ->
                    cc.put("iconRoi", JSONObject().apply {
                        put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
                    })
                }
                cCfg.iconFile?.let { cc.put("iconFile", it) }
                cCfg.confirmationRoi?.let { r ->
                    cc.put("confirmationRoi", JSONObject().apply {
                        put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
                    })
                }
                cCfg.confirmationButton?.let { p ->
                    cc.put("confirmationButton", JSONObject().apply { put("x", p.x); put("y", p.y) })
                }
                currObj.put(cName, cc)
            }
            wcObj.put("currencies", currObj)
            wc.bulkButton?.let { wcObj.put("bulkButton", JSONObject().apply { put("x", it.x); put("y", it.y) }) }
            wc.removeButton?.let { wcObj.put("removeButton", JSONObject().apply { put("x", it.x); put("y", it.y) }) }
            wc.titleRoi?.let { r ->
                wcObj.put("titleRoi", JSONObject().apply {
                    put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
                })
            }
            wc.bulkConfirmationBodyRoi?.let { r ->
                wcObj.put("bulkConfirmationBodyRoi", JSONObject().apply {
                    put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
                })
            }
            wc.bulkConfirmationButton?.let { p ->
                wcObj.put("bulkConfirmationButton", JSONObject().apply { put("x", p.x); put("y", p.y) })
            }
            val resObj = JSONObject()
            for ((rk, rv) in wc.resourceRois) {
                resObj.put(rk, JSONObject().apply {
                    put("left", rv.left); put("top", rv.top); put("right", rv.right); put("bottom", rv.bottom)
                })
            }
            wcObj.put("resourceRois", resObj)
            root.put("wallConfig", wcObj)
        }

        val homeObj = JSONObject()
        for ((resource, r) in homeResourceRois) {
            homeObj.put(resource.key, JSONObject().apply {
                put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
            })
        }
        root.put("homeResourceRois", homeObj)

        val scoutObj = JSONObject()
        for ((resource, r) in scoutLootRois) scoutObj.put(resource.key, regionJson(r))
        root.put("scoutLootRois", scoutObj)
        scoutCountdownRoi?.let { root.put("scoutCountdownRoi", regionJson(it)) }
        scoutFeeRoi?.let { root.put("scoutFeeRoi", regionJson(it)) }

        val targetArr = JSONArray()
        for (target in collectionTargets) {
            targetArr.put(JSONObject().apply {
                put("name", target.name); put("x", target.point.x); put("y", target.point.y)
            })
        }
        root.put("collectionTargets", targetArr)

        root.put("isVerified", isVerified)
        return root.toString(2)
    }

    private fun regionJson(r: Region) = JSONObject().apply {
        put("left", r.left); put("top", r.top); put("right", r.right); put("bottom", r.bottom)
    }

    companion object {
        /**
         * A card area around its tap, from the areas measured in September: 67 px wide, and
         * 600-702 for taps at y 650. Every tap in that profile rebuilds its area within 1 px.
         */
        private const val CARD_LEFT = 33
        private const val CARD_RIGHT = 34
        private const val CARD_ABOVE = 50
        private const val CARD_BELOW = 52

        /**
         * The same card area on the PC, in pixels of 1740x978: the phone's 1.13 times, as the PC's
         * cards are. Its scout tray has cards 102x134 px, 110 px apart from hero to hero, where
         * Sweat's phone had 89x119, 97 apart (audit-evidence/spike/geo-scout.png, and the phone
         * tree's audit-evidence/clash-18647/r4-07-sweat-scout-a.png). The count badges stay the
         * phone's size, 25x17 px for an x5 on both, so the badge band around the area keeps the
         * phone's offsets.
         */
        private const val PC_CARD_LEFT = 37
        private const val PC_CARD_RIGHT = 38
        private const val PC_CARD_ABOVE = 57
        private const val PC_CARD_BELOW = 59

        fun createDefault(width: Int = 1600, height: Int = 720): PhoneProfile {
            return PhoneProfile(
                resolutionWidth = width,
                resolutionHeight = height,
                viewport = Viewport(0, 0, width, height),
                stateAnchors = emptyMap(),
                troopAnchors = emptyMap(),
                troopRois = emptyMap(),
                deploymentPoints = emptyList(),
                perimeterSegments = emptyList(),
                heroDrop = null,
                wallPoints = emptyList(),
                wallRows = null,
                wallCursor = WallCursor(0, 0),
                wallConfig = null,
                isVerified = false
            )
        }

        fun loadFromFile(file: File): PhoneProfile {
            if (!file.exists()) {
                throw StopException("Profile file does not exist: ${file.absolutePath}")
            }
            return fromJson(file.readText())
        }

        /** A lattice step, which is fractional: a wall is 12.9 pixels along a row, not 13. */
        private fun parseStep(obj: JSONObject): Step {
            return Step(requireDouble(obj, "dx"), requireDouble(obj, "dy"))
        }

        private fun requireDouble(obj: JSONObject, key: String): Double {
            if (!obj.has(key)) throw StopException("Missing required number field: '$key'")
            val v = obj.get(key)
            if (v is Number) return v.toDouble()
            throw StopException("Field '$key' must be a number, got: $v")
        }

        private fun requireInt(obj: JSONObject, key: String): Int {
            if (!obj.has(key)) throw StopException("Missing required integer field: '$key'")
            val v = obj.get(key)
            if (v is Int) return v
            if (v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE) return v.toInt()
            if (v is Number) {
                val d = v.toDouble()
                if (d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) {
                    throw StopException("Field '$key' must be an integer, got fraction: $v")
                }
                return d.toInt()
            }
            throw StopException("Field '$key' must be an integer, got: $v")
        }

        private fun parsePoint(obj: JSONObject): Point {
            val x = requireInt(obj, "x")
            val y = requireInt(obj, "y")
            return Point(x, y)
        }

        private fun parseRegion(obj: JSONObject): Region {
            val left = requireInt(obj, "left")
            val top = requireInt(obj, "top")
            val right = requireInt(obj, "right")
            val bottom = requireInt(obj, "bottom")
            if (left >= right || top >= bottom) {
                throw StopException("Invalid region: [$left, $top, $right, $bottom] (left < right and top < bottom required)")
            }
            return Region(left, top, right, bottom)
        }

        private fun parseViewport(obj: JSONObject): Viewport {
            val left = requireInt(obj, "left")
            val top = requireInt(obj, "top")
            val right = requireInt(obj, "right")
            val bottom = requireInt(obj, "bottom")
            if (left >= right || top >= bottom) {
                throw StopException("Invalid viewport: [$left, $top, $right, $bottom]")
            }
            return Viewport(left, top, right, bottom)
        }

        fun fromJson(jsonText: String): PhoneProfile {
            if (jsonText.isBlank()) {
                throw StopException("Profile JSON is empty")
            }
            val root = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                throw StopException("Malformed profile JSON: ${e.message}", e)
            }

            val width = requireInt(root, "resolutionWidth")
            val height = requireInt(root, "resolutionHeight")
            if (width <= 0 || height <= 0) {
                throw StopException("Invalid dimensions: width=$width, height=$height")
            }

            if (!root.has("viewport")) {
                throw StopException("Missing viewport entry")
            }
            val vpObj = root.getJSONObject("viewport")
            val viewport = parseViewport(vpObj)

            val stateAnchors = mutableMapOf<String, Point>()
            if (root.has("stateAnchors")) {
                val so = root.getJSONObject("stateAnchors")
                val keys = so.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val pObj = so.optJSONObject(k)
                        ?: throw StopException("Malformed state anchor record for '$k'")
                    stateAnchors[k] = parsePoint(pObj)
                }
            }

            val troopAnchors = mutableMapOf<String, Point>()
            if (root.has("troopAnchors")) {
                val to = root.getJSONObject("troopAnchors")
                val keys = to.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val pObj = to.optJSONObject(k)
                        ?: throw StopException("Malformed troop anchor record for '$k'")
                    troopAnchors[k] = parsePoint(pObj)
                }
            }

            val troopRois = mutableMapOf<String, Region>()
            if (root.has("troopRois")) {
                val tro = root.getJSONObject("troopRois")
                val keys = tro.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val rObj = tro.optJSONObject(k)
                        ?: throw StopException("Malformed troop ROI record for '$k'")
                    troopRois[k] = parseRegion(rObj)
                }
            }

            val deploymentPoints = mutableListOf<Point>()
            if (root.has("deploymentPoints")) {
                val dArr = root.getJSONArray("deploymentPoints")
                for (i in 0 until dArr.length()) {
                    val pObj = dArr.optJSONObject(i)
                        ?: throw StopException("Malformed deployment point record at index $i")
                    deploymentPoints.add(parsePoint(pObj))
                }
            }

            val perimeterSegments = mutableListOf<Pair<Point, Point>>()
            if (root.has("perimeterSegments")) {
                val pArr = root.getJSONArray("perimeterSegments")
                for (i in 0 until pArr.length()) {
                    val sObj = pArr.optJSONObject(i)
                        ?: throw StopException("Malformed perimeter segment record at index $i")
                    val startObj = sObj.optJSONObject("start")
                        ?: throw StopException("Missing 'start' in perimeter segment $i")
                    val endObj = sObj.optJSONObject("end")
                        ?: throw StopException("Missing 'end' in perimeter segment $i")
                    perimeterSegments.add(Pair(parsePoint(startObj), parsePoint(endObj)))
                }
            }

            val heroDrop = if (root.has("heroDrop") && !root.isNull("heroDrop")) {
                val ho = root.getJSONObject("heroDrop")
                parsePoint(ho)
            } else null

            val wallPoints = mutableListOf<Point>()
            if (root.has("wallPoints")) {
                val wArr = root.getJSONArray("wallPoints")
                for (i in 0 until wArr.length()) {
                    val pObj = wArr.optJSONObject(i)
                        ?: throw StopException("Malformed wall point record at index $i")
                    wallPoints.add(parsePoint(pObj))
                }
            }

            var wallRows: WallRows? = null
            if (root.has("wallRows") && !root.isNull("wallRows")) {
                val wrObj = root.getJSONObject("wallRows")
                wallRows = WallRows(
                    origin = parsePoint(wrObj.getJSONObject("origin")),
                    alongStep = parseStep(wrObj.getJSONObject("alongStep")),
                    rowStep = parseStep(wrObj.getJSONObject("rowStep")),
                    rows = requireInt(wrObj, "rows"),
                    wallsPerRow = requireInt(wrObj, "wallsPerRow")
                )
            }

            val wallCursor = if (root.has("wallCursor") && !root.isNull("wallCursor")) {
                val wcObj = root.getJSONObject("wallCursor")
                WallCursor(requireInt(wcObj, "row"), requireInt(wcObj, "index"))
            } else WallCursor(0, 0)

            var wallConfig: WallConfig? = null
            if (root.has("wallConfig") && !root.isNull("wallConfig")) {
                val wcObj = root.getJSONObject("wallConfig")
                val currMap = mutableMapOf<String, WallCurrencyConfig>()
                if (wcObj.has("currencies")) {
                    val cObj = wcObj.getJSONObject("currencies")
                    val cKeys = cObj.keys()
                    while (cKeys.hasNext()) {
                        val cName = cKeys.next()
                        val ccObj = cObj.getJSONObject(cName)
                        val costRoi = parseRegion(ccObj.getJSONObject("costRoi"))
                        val btnPt = parsePoint(ccObj.getJSONObject("buttonPoint"))
                        val iconRoi = if (ccObj.has("iconRoi") && !ccObj.isNull("iconRoi")) parseRegion(ccObj.getJSONObject("iconRoi")) else null
                        val iconFile = if (ccObj.has("iconFile") && !ccObj.isNull("iconFile")) ccObj.getString("iconFile") else null
                        val confRoi = if (ccObj.has("confirmationRoi") && !ccObj.isNull("confirmationRoi")) parseRegion(ccObj.getJSONObject("confirmationRoi")) else null
                        val confBtn = if (ccObj.has("confirmationButton") && !ccObj.isNull("confirmationButton")) parsePoint(ccObj.getJSONObject("confirmationButton")) else null
                        currMap[cName] = WallCurrencyConfig(costRoi, btnPt, iconRoi, iconFile, confRoi, confBtn)
                    }
                }
                val bulkBtn = if (wcObj.has("bulkButton") && !wcObj.isNull("bulkButton")) parsePoint(wcObj.getJSONObject("bulkButton")) else null
                val removeBtn = if (wcObj.has("removeButton") && !wcObj.isNull("removeButton")) parsePoint(wcObj.getJSONObject("removeButton")) else null
                val titleRoi = if (wcObj.has("titleRoi") && !wcObj.isNull("titleRoi")) parseRegion(wcObj.getJSONObject("titleRoi")) else null
                val bulkConfBody = if (wcObj.has("bulkConfirmationBodyRoi") && !wcObj.isNull("bulkConfirmationBodyRoi")) parseRegion(wcObj.getJSONObject("bulkConfirmationBodyRoi")) else null
                val bulkConfBtn = if (wcObj.has("bulkConfirmationButton") && !wcObj.isNull("bulkConfirmationButton")) parsePoint(wcObj.getJSONObject("bulkConfirmationButton")) else null
                val resMap = mutableMapOf<String, Region>()
                if (wcObj.has("resourceRois")) {
                    val ro = wcObj.getJSONObject("resourceRois")
                    val rKeys = ro.keys()
                    while (rKeys.hasNext()) {
                        val rk = rKeys.next()
                        resMap[rk] = parseRegion(ro.getJSONObject(rk))
                    }
                }
                wallConfig = WallConfig(currMap, bulkBtn, removeBtn, titleRoi, bulkConfBody, bulkConfBtn, resMap)
            }

            // Profiles saved before goals only had the wall phase's copy of the gold and elixir
            // bars. Those are the same bars on the same village screen.
            val homeResourceRois = mutableMapOf<Resource, Region>()
            if (root.has("homeResourceRois")) {
                val ho = root.getJSONObject("homeResourceRois")
                val hKeys = ho.keys()
                while (hKeys.hasNext()) {
                    val hk = hKeys.next()
                    val resource = Resource.fromKey(hk)
                        ?: throw StopException("Unknown home resource '$hk'")
                    homeResourceRois[resource] = parseRegion(ho.getJSONObject(hk))
                }
            } else {
                for (resource in listOf(Resource.GOLD, Resource.ELIXIR)) {
                    wallConfig?.resourceRois?.get(resource.key)?.let { homeResourceRois[resource] = it }
                }
            }

            val scoutLootRois = mutableMapOf<Resource, Region>()
            if (root.has("scoutLootRois")) {
                val so = root.getJSONObject("scoutLootRois")
                for (sk in so.keys()) {
                    val resource = Resource.fromKey(sk)
                        ?: throw StopException("Unknown scout loot resource '$sk'")
                    scoutLootRois[resource] = parseRegion(so.getJSONObject(sk))
                }
            }
            val collectionTargets = mutableListOf<CollectionTarget>()
            if (root.has("collectionTargets")) {
                val cArr = root.getJSONArray("collectionTargets")
                for (i in 0 until cArr.length()) {
                    val cObj = cArr.optJSONObject(i)
                        ?: throw StopException("Malformed collection target at index $i")
                    val name = cObj.optString("name", "").trim()
                    if (name.isEmpty()) throw StopException("Collection target at index $i has no name")
                    collectionTargets.add(CollectionTarget(name, parsePoint(cObj)))
                }
            }
            fun optionalRegion(key: String): Region? =
                if (root.has(key) && !root.isNull(key)) parseRegion(root.getJSONObject(key)) else null

            val profile = PhoneProfile(
                resolutionWidth = width,
                resolutionHeight = height,
                viewport = viewport,
                stateAnchors = stateAnchors,
                troopAnchors = troopAnchors,
                troopRois = troopRois,
                deploymentPoints = deploymentPoints,
                perimeterSegments = perimeterSegments,
                heroDrop = heroDrop,
                wallPoints = wallPoints,
                wallRows = wallRows,
                wallCursor = wallCursor,
                wallConfig = wallConfig,
                homeResourceRois = homeResourceRois,
                scoutLootRois = scoutLootRois,
                scoutCountdownRoi = optionalRegion("scoutCountdownRoi"),
                scoutFeeRoi = optionalRegion("scoutFeeRoi"),
                collectionTargets = collectionTargets,
                // The runner writes this back after a supervised attack finishes, and the
                // activity reloads from the file every time it resumes, so dropping it here
                // meant a profile could never stay verified and unattended never unlocked.
                // Every calibration edit clears it again on the way through.
                isVerified = root.optBoolean("isVerified", false)
            )
            profile.validate()
            return profile
        }
    }
}
