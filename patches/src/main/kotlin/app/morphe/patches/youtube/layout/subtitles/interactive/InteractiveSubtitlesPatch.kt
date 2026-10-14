package app.morphe.patches.youtube.layout.subtitles.interactive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

internal object YouTubeAppOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/YouTubeApplication;",
    name = "onCreate",
)

internal object ShellHomeOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/application/Shell_HomeActivity;",
    name = "onCreate",
)

internal object WatchWhileOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/watchwhile/WatchWhileActivity;",
    name = "onCreate",
)

internal object SubtitleWindowOnLayoutFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;",
    name = "onLayout",
)

internal object PlayerOverlaysOnLayoutFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/player/overlay/YouTubePlayerOverlaysLayout;",
    name = "onLayout",
)

@Suppress("unused")
val interactiveSubtitlesPatch = bytecodePatch(
    name = "Interactive subtitles",
    description = "Click on any English word in YouTube subtitles to show instant Arabic translation and common meanings via Gemini or Nvidia AI.",
) {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith("com.google.android.youtube")

    execute {
        val initCall = """
            invoke-static {}, Lapp/morphe/extension/youtube/patches/subtitles/InteractiveSubtitleOverlay;->init()V
        """

        YouTubeAppOnCreateFingerprint.methodOrNull?.addInstructions(0, initCall)
        ShellHomeOnCreateFingerprint.methodOrNull?.addInstructions(0, initCall)
        WatchWhileOnCreateFingerprint.methodOrNull?.addInstructions(0, initCall)

        val layoutCall = """
            invoke-static { p0 }, Lapp/morphe/extension/youtube/patches/subtitles/InteractiveSubtitleOverlay;->onSubtitleViewLayout(Landroid/view/View;)V
        """

        SubtitleWindowOnLayoutFingerprint.methodOrNull?.addInstructions(0, layoutCall)
        PlayerOverlaysOnLayoutFingerprint.methodOrNull?.addInstructions(0, layoutCall)
    }
}
