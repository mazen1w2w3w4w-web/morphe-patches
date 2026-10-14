package app.morphe.patches.youtube.layout.subtitles.interactive

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

@Suppress("unused")
val interactiveSubtitlesPatch = bytecodePatch(
    name = "Interactive subtitles",
    description = "Click on any English word in YouTube subtitles to show instant Arabic translation and common meanings via Gemini AI.",
) {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith("com.google.android.youtube")

    execute {
    }
}
