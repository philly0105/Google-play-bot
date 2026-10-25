package local.clashfarmer.desktop.ui.calibration

import local.clashfarmer.core.PcGeometry
import local.clashfarmer.core.StopException
import local.clashfarmer.core.Viewport
import local.clashfarmer.desktop.PcProfiles
import local.clashfarmer.desktop.sidecar.SurfaceSize
import local.clashfarmer.recognition.PhoneProfile

/**
 * The profile being edited, made to fit a frame of another size, and [note], what the user is
 * told about it, or null when there is nothing to say.
 */
internal class Refit(val profile: PhoneProfile, val note: String?)

/**
 * [profile] on a captured frame of [width]x[height]. Google Play Games can give the game another
 * size or shape than the calibration was taken at, and the phone's way, keeping the points and
 * changing only the size, left every point where the old frame had it. The points are moved with
 * the rule a run uses ([PcProfiles.derive]) and the result is unverified, as any edit leaves it. A
 * profile that can't be moved, such as the phone-shaped default, keeps the phone's relabel, and a
 * frame the bot can't read says why. Collectors are dropped with the move: they belong to the
 * camera reference, which was taken at the old size.
 *
 * At the profile's own size the points stay, but on a PC frame the game area is made the whole
 * frame, as a move makes it: the corners can't be picked there ([targetEnabled]), and an earlier
 * build let a stray click on them shrink it, which every move and check would then keep.
 */
internal fun refit(profile: PhoneProfile, width: Int, height: Int): Refit {
    if (width == profile.resolutionWidth && height == profile.resolutionHeight) {
        val whole = Viewport(0, 0, width, height)
        if (profile.viewport == whole || !PcGeometry.isPc(whole)) return Refit(profile, null)
        return Refit(
            profile.copy(viewport = whole, isVerified = false),
            "The game area was set to the whole screenshot. Check the points on this frame, then save."
        )
    }
    val from = "${profile.resolutionWidth}x${profile.resolutionHeight}"
    val empty = profile.copy(isVerified = false) == PhoneProfile.createDefault(profile.resolutionWidth, profile.resolutionHeight)
    return try {
        val moved = PcProfiles.derive(profile, SurfaceSize(width, height), emptySet()).copy(isVerified = false)
        val collectors = if (profile.collectionTargets.isEmpty()) "" else
            " The collectors were cleared; mark them again on a new camera reference."
        Refit(moved, if (empty) null else "The points were moved from $from to ${width}x$height. Check them on this frame, then save.$collectors")
    } catch (e: StopException) {
        val relabelled = profile.copy(
            resolutionWidth = width,
            resolutionHeight = height,
            viewport = Viewport(0, 0, width, height),
            isVerified = false
        )
        Refit(relabelled, PcProfiles.surfaceProblem(SurfaceSize(width, height)) ?: e.message.takeUnless { empty })
    }
}

/**
 * The question Save asks before it replaces a saved calibration of one shape with one of another,
 * or null when it needn't ask: nothing is saved yet, or the shape is the same.
 */
internal fun saveQuestion(opened: PhoneProfile, edited: PhoneProfile, hasSaved: Boolean): String? {
    if (!hasSaved) return null
    if (PcProfiles.sameAspect(opened.resolutionWidth, opened.resolutionHeight, edited.resolutionWidth, edited.resolutionHeight)) return null
    val was = PcProfiles.shape(opened.resolutionWidth, opened.resolutionHeight)
    val now = PcProfiles.shape(edited.resolutionWidth, edited.resolutionHeight)
    return "The saved calibration is for a $was game area, and this one is $now. Saving replaces it, and the bot " +
        "then needs one supervised attack before it runs on its own."
}

/**
 * What Show camera reference says when the saved reference, [width]x[height], is not the size of
 * [profile], the one being edited, or null when it is. No point can be marked on it
 * ([frameMismatch]), and only Retake sets it aside: showing it changes nothing, so a capture at
 * another size that is never saved costs no collectors.
 */
internal fun referenceMismatch(width: Int, height: Int, profile: PhoneProfile): String? {
    if (width == profile.resolutionWidth && height == profile.resolutionHeight) return null
    return "This camera reference was taken at ${width}x$height, and the calibration is now " +
        "${profile.resolutionWidth}x${profile.resolutionHeight}, so no points can be marked on it. " +
        "To mark collectors at this size, press Retake camera reference."
}

/**
 * What Apply says when the frame on show, [width]x[height], is not the size of [profile], the one
 * being edited, or null when it is. A click is a pixel of that frame, so it would be saved where
 * that frame had it: the screenshot of a capture at another size that was never saved, which the
 * screen shows again when it opens, or a camera reference taken before the game area changed.
 */
internal fun frameMismatch(width: Int, height: Int, profile: PhoneProfile): String? {
    if (width == profile.resolutionWidth && height == profile.resolutionHeight) return null
    return "This screenshot was taken at ${width}x$height, and the calibration is " +
        "${profile.resolutionWidth}x${profile.resolutionHeight}, so no points can be set on it. " +
        "Press Capture game screen first."
}

/** Why Save refuses a profile of this size, in words for the user, or null when it may be saved. */
internal fun saveProblem(edited: PhoneProfile): String? =
    PcProfiles.surfaceProblem(SurfaceSize(edited.resolutionWidth, edited.resolutionHeight))
