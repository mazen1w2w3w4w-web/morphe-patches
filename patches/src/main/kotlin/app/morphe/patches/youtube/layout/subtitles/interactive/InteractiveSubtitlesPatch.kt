package app.morphe.patches.youtube.layout.subtitles.interactive

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fingerprint.MethodFingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.mapping.misc.ResourceType
import app.morphe.patches.shared.mapping.misc.getResourceId
import app.morphe.patches.shared.mapping.misc.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.Opcode

private var subtitleWindowId = -1L

internal object SubtitleWindowFingerprint : MethodFingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/CharSequence;"),
    opcodes = listOf(
        Opcode.INVOKE_VIRTUAL,
    ),
    customFingerprint = { methodDef, _ ->
        methodDef.definingClass.contains("SubtitleWindow") ||
        methodDef.implementation?.instructions?.any { instruction ->
            instruction.toString().contains(subtitleWindowId.toString())
        } == true
    }
)

@Suppress("unused")
val interactiveSubtitlesPatch = bytecodePatch(
    name = "Interactive subtitles",
    description = "Click on any English word in YouTube subtitles to show instant Arabic translation and common meanings via Gemini AI.",
) {
    dependsOn(
        sharedExtensionPatch,
        resourceMappingPatch,
    )

    compatibleWith("com.google.android.youtube")

    execute {
        subtitleWindowId = getResourceId(ResourceType.ID, "subtitle_window_identifier")

        SubtitleWindowFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, Lapp/morphe/extension/youtube/patches/subtitles/InteractiveSubtitleOverlay;->attachInteractiveSubtitles(Landroid/view/View;Ljava/lang/CharSequence;)V
            """
        )
    }
}
