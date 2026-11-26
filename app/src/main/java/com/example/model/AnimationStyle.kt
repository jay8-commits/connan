package com.example.model

import androidx.compose.ui.graphics.Color

enum class AnimationStyle(
    val displayName: String,
    val badge: String,
    val subtitle: String,
    val accentHex: Long,
    val backdropPromptSuffix: String,
    val usesNetworkArt: Boolean,
    val captionStyleLabel: String
) {
    ATMOSPHERIC_2D_STORY(
        displayName = "Atmospheric 2D Vector Story",
        badge = "CINEMATIC 2D",
        subtitle = "Shaded 2D rooms, light shafts, dust motes & expressive vector casts",
        accentHex = 0xFFFFB703,
        backdropPromptSuffix = "atmospheric 2D vector storytelling stage, dramatic angled light shafts, warm rim shadows, architectural depth, no text, no people, empty stage, centered composition",
        usesNetworkArt = false,
        captionStyleLabel = "1–2 word yellow & crimson punch"
    ),
    STYLIZED_25D_EXPLAINER(
        displayName = "2.5D Stylized Vector Explainer",
        badge = "2.5D VECTOR",
        subtitle = "Low-poly layered SVG hills, 3-plane parallax & clean vector cast",
        accentHex = 0xFF2DD4BF,
        backdropPromptSuffix = "clean 2.5D stylized layered vector explainer backdrop, faceted hills and perspective depth, crisp studio lighting, no text, no people, empty stage, centered composition",
        usesNetworkArt = false,
        captionStyleLabel = "1–2 word yellow & crimson punch"
    ),
    PAPER_DIORAMA(
        displayName = "Paper Cut-Out & Diorama",
        badge = "DIORAMA",
        subtitle = "Textured paper layers, crisp drop shadows & brass split-pin pivots",
        accentHex = 0xFFFF5E36,
        backdropPromptSuffix = "stylized 3D miniature paper-cut diorama set, layered craft paper depth, soft studio lighting, tilt-shift bokeh, no text, no people, empty stage, centered composition",
        usesNetworkArt = true,
        captionStyleLabel = "1–2 word yellow & crimson punch"
    ),
    KINETIC_TYPOGRAPHY_DIAGRAM(
        displayName = "Kinetic Typography & Diagram",
        badge = "KINETIC + DIAGRAM",
        subtitle = "Swiss grids, animated SVG counters & glowing callout brackets",
        accentHex = 0xFF00E5FF,
        backdropPromptSuffix = "clean Swiss grid motion graphics stage, dark architectural slate with subtle grid hairlines, no text, no people, empty stage, centered composition",
        usesNetworkArt = false,
        captionStyleLabel = "1–2 word yellow & crimson punch"
    ),
    STICKMAN_WHITEBOARD(
        displayName = "Stickman Whiteboard Explainer",
        badge = "100% OFFLINE",
        subtitle = "Textured paper grain, hand-drawn wobble, chalk PathMeasure",
        accentHex = 0xFF38BDF8,
        backdropPromptSuffix = "",
        usesNetworkArt = false,
        captionStyleLabel = "Bold black & red marker emphasis"
    ),
    FLAT_VECTOR(
        displayName = "Modern Flat Vector",
        badge = "CLEAN VECTOR",
        subtitle = "Borderless gradients, infographic icons, smooth zoom-ins",
        accentHex = 0xFF2DD4BF,
        backdropPromptSuffix = "clean modern flat vector illustration backdrop, smooth geometric gradients, minimalist architectural depth, no text, no people, empty stage, centered composition",
        usesNetworkArt = true,
        captionStyleLabel = "Yellow text in dark rounded pill"
    ),
    DARK_SUSPENSE(
        displayName = "Dark Motion Comic",
        badge = "HORROR / DRAMA",
        subtitle = "Moody cel-shading, rim light, vignette + film grain",
        accentHex = 0xFFEF4444,
        backdropPromptSuffix = "high contrast dark motion comic backdrop, moody dramatic chiaroscuro lighting, deep shadows, atmospheric fog, no text, no people, empty stage, centered composition",
        usesNetworkArt = true,
        captionStyleLabel = "Red-accented suspense captions"
    ),
    CHIBI_STORYTIME(
        displayName = "Chibi Anime Storytime",
        badge = "KAWAII ANIME",
        subtitle = "Big-eyed squash & stretch, anime emotes, bubbly captions",
        accentHex = 0xFFF472B6,
        backdropPromptSuffix = "colorful vibrant anime background art, soft pastel sky highlights, cozy detailed environment, Makoto Shinkai inspired lighting, no text, no people, empty stage, centered composition",
        usesNetworkArt = true,
        captionStyleLabel = "Pastel-outlined bubbly captions"
    ),
    TECH_EXPLAINER(
        displayName = "Viral HUD & Motion Explainer",
        badge = "HUD EXPLAINER",
        subtitle = "Clean vector diagrams, dynamic zoom/pan, animated HUD overlays",
        accentHex = 0xFF00E5FF,
        backdropPromptSuffix = "clean dark tech infographic vector stage, subtle geometric grid, glowing cyan and emerald depth accents, no text, no people, empty stage, centered composition",
        usesNetworkArt = true,
        captionStyleLabel = "Kinetic spring pop + keyword glow"
    ),
    CROSS_SECTION(
        displayName = "2D Cutaway & Engineering Diagram",
        badge = "CUTAWAY 2D",
        subtitle = "Layered 2D cutaways, animated flow paths & dynamic vector art",
        accentHex = 0xFF22D3EE,
        backdropPromptSuffix = "clean 2D Kurzgesagt flat vector cross-section cutaway diagram illustration, bold layered depth, geometric precision, no text, no labels, no watermark",
        usesNetworkArt = true,
        captionStyleLabel = "Cyan-glow engineering pill"
    ),
    VIRAL_2D_EXPLAINER(
        displayName = "Viral 2D Science & Story Explainer",
        badge = "2D VIRAL",
        subtitle = "6px bold outlines, cartoon reactions, dynamic diagrams & yellow captions",
        accentHex = 0xFFFFD600,
        backdropPromptSuffix = "vibrant clean 2D animated story explainer backdrop, crisp geometric shapes, bright studio lighting, no text, no people, empty stage, centered composition",
        usesNetworkArt = false,
        captionStyleLabel = "Thick black-outlined yellow/white captions"
    ),
    PIXEL_CUTAWAY_EXPLAINER(
        displayName = "16-Bit Pixel Cutaway Explainer",
        badge = "16-BIT PIXEL",
        subtitle = "270x480 crisp nearest-neighbor pixel cutaways, animated particles & callouts",
        accentHex = 0xFF4ADE80,
        backdropPromptSuffix = "16-bit retro pixel art scientific cross-section cutaway diagram illustration, crisp geometric pixel clusters, rich contrast, no text, no labels, no watermark",
        usesNetworkArt = true,
        captionStyleLabel = "Crisp pixel-framed retro dialog captions"
    );

    val accentColor: Color
        get() = Color(accentHex)

    companion object {
        val STUDIO_VIRAL_PRESETS: List<AnimationStyle> = listOf(
            ATMOSPHERIC_2D_STORY,
            STYLIZED_25D_EXPLAINER,
            PAPER_DIORAMA,
            KINETIC_TYPOGRAPHY_DIAGRAM
        )
    }
}
