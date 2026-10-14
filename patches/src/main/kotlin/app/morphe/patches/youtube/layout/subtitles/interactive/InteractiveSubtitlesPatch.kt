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

internal object TikTokAppOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/YouTubeTikTokRoot_Application;",
    name = "onCreate",
)

internal object TikTokAppAttachFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/YouTubeTikTokRoot_Application;",
    name = "attachBaseContext",
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

internal object SettingsActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/settings/SettingsActivity;",
    name = "onCreate",
)

internal object SubtitleWindowOnLayoutFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;",
    name = "onLayout",
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

        val selfHookCall = """
            invoke-static/range { p0 .. p0 }, Lapp/morphe/extension/youtube/patches/subtitles/InteractiveSubtitleOverlay;->onHook(Ljava/lang/Object;)V
        """

        TikTokAppOnCreateFingerprint.methodOrNull?.addInstructions(0, selfHookCall)
        TikTokAppAttachFingerprint.methodOrNull?.addInstructions(0, selfHookCall)
        MainActivityOnCreateFingerprint.methodOrNull?.addInstructions(0, selfHookCall)
        MainActivityOnResumeFingerprint.methodOrNull?.addInstructions(0, selfHookCall)
        SettingsActivityOnCreateFingerprint.methodOrNull?.addInstructions(0, selfHookCall)
        SubtitleWindowOnLayoutFingerprint.methodOrNull?.addInstructions(0, selfHookCall)
    }
}
