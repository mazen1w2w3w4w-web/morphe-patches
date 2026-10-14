package app.morphe.patches.youtube.layout.subtitles.interactive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

internal object MorpheUtilsGetContextFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/shared/Utils;",
    name = "getContext",
)

internal object MorpheUtilsSetContextFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/shared/Utils;",
    name = "setContext",
)

internal object MainActivityInitFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;",
    name = "<init>",
)

internal object MainActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;",
    name = "onCreate",
)

internal object MainActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;",
    name = "onResume",
)

internal object SettingsActivityInitFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/settings/SettingsActivity;",
    name = "<init>",
)

internal object SettingsActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/settings/SettingsActivity;",
    name = "onCreate",
)

internal object TikTokAppInitFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/YouTubeTikTokRoot_Application;",
    name = "<init>",
)

internal object ShellHomeInitFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/application/Shell_HomeActivity;",
    name = "<init>",
)

internal object SubtitleWindowInitFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;",
    name = "<init>",
)

@Suppress("unused")
val interactiveSubtitlesPatch = bytecodePatch(
    name = "Interactive subtitles",
    description = "Click on any English word in YouTube subtitles to show instant Arabic translation and explanation via Gemini or Nvidia AI.",
) {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith("com.google.android.youtube")

    execute {
        val initCall = """
            invoke-static {}, Lapp/morphe/extension/youtube/patches/subtitles/InteractiveSubtitleOverlay;->init()V
        """

        MorpheUtilsGetContextFingerprint.methodOrNull?.addInstructions(0, initCall)
        MorpheUtilsSetContextFingerprint.methodOrNull?.addInstructions(0, initCall)
        MainActivityInitFingerprint.methodOrNull?.addInstructions(0, initCall)
        SettingsActivityInitFingerprint.methodOrNull?.addInstructions(0, initCall)
        TikTokAppInitFingerprint.methodOrNull?.addInstructions(0, initCall)
        ShellHomeInitFingerprint.methodOrNull?.addInstructions(0, initCall)
        SubtitleWindowInitFingerprint.methodOrNull?.addInstructions(0, initCall)

        val actCall = """
            invoke-static { p0 }, Lapp/morphe/extension/youtube/patches/subtitles/InteractiveSubtitleOverlay;->onActivityHook(Landroid/app/Activity;)V
        """

        MainActivityOnCreateFingerprint.methodOrNull?.addInstructions(0, actCall)
        MainActivityOnResumeFingerprint.methodOrNull?.addInstructions(0, actCall)
        SettingsActivityOnCreateFingerprint.methodOrNull?.addInstructions(0, actCall)
    }
}
