# Speakeasy — Quiet confidence

## Identity

Write **Speakeasy**, one word, capital S, no all-caps treatment. Wordmark: Manrope 700, 30sp in the home header, normal letter spacing. Use the tagline **Your voice. In your words.** only on home/onboarding. Functional text should be plain: On, Paused, Turn on, Pause, Recording, Saved for recovery.

Logo concept: an open-bottom doorway arch enclosing a microphone. The doorway is the only speakeasy reference. No cocktails, prohibition lettering, theatrical neon, velvet textures, password metaphors or promises that users can whisper more quietly than recognition supports. A mint monochrome mark is the app identity; a muted brass version may appear beside the wordmark. The actual recording control uses the simpler microphone glyph for immediate recognition.

## Exact semantic palette

All values are opaque sRGB. These tokens, not sampled image pixels, are implementation truth. Image generation introduces slight tonal variation.

| Token | Light | Dark | Purpose |
|---|---|---|---|
| background | `#F6F5EF` | `#111916` | App and system bars |
| surface | `#FFFFFF` | `#1B2621` | Cards, grouped rows, sheets |
| hero | `#E7F1E9` | `#22382D` | Writing style emphasis |
| raised | `#EDF0E9` | `#293D32` | Inset areas, neutral badges, disabled fills |
| textPrimary | `#172A21` | `#F5F3EA` | Headings and body |
| textSecondary | `#4D6356` | `#BBC8BF` | Descriptions, metadata, placeholders |
| accent | `#245C43` | `#A7E6C1` | Action text, primary fill, selected controls |
| onAccent | `#FFFFFF` | `#10251A` | Text/icons on accent fill |
| controlOutline | `#6B8072` | `#7F998B` | Essential control outlines, focus affordances |
| divider | `#D7E1D9` | `#354B3E` | Decorative separators only |
| error | `#A32828` | `#FFB4AB` | Error labels and icons |
| brass / warning | `#76551C` | `#DEC58B` | Brand detail; warning text with icon |

TextPrimary, textSecondary, accent, error and warning are safe on background, surface, hero and raised. Do not use onAccent on any background except accent. Do not put cream text on mint buttons. Do not use divider as an essential control outline. Disabled explanatory text retains textSecondary at full opacity; do not fade whole groups.

Calculated minimum contrast across the four permitted base surfaces:

| Pair family | Light | Dark |
|---|---:|---:|
| Primary text | 13.07:1 | 10.45:1 |
| Secondary text | 5.61:1 | 6.71:1 |
| Accent text | 6.76:1 | 8.15:1 |
| Error text | 6.28:1 | 6.84:1 |
| Brass/warning text | 5.88:1 | 6.90:1 |
| onAccent on accent | 7.82:1 | 11.32:1 |
| Essential outline against base surfaces | 3.66:1 | 3.78:1 |

These calculations exceed [WCAG AA's 4.5:1 ordinary-text threshold](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). They validate token combinations, not every pixel of a generated image or an unbuilt app. See `contrast-check.py` and `contrast-report.json` for reproducible checks.

## Typography and rhythm

Use [Manrope from Google Fonts](https://fonts.google.com/specimen/Manrope), bundled locally with its [OFL licence](https://github.com/google/fonts/tree/main/ofl/manrope). Weights 400, 600, 700. No runtime font fetch; use platform sans-serif fallback. Use the same font in previews: a style changes punctuation and capitals, not typeface.

30/38sp wordmark; 26/34sp screen and hero titles; 20/28sp section titles; 16/24sp body and row labels; 14/20sp metadata; 16/20sp semibold buttons. Use sp for text and line heights; dp for layout. No core instruction below 14sp. Let user font scaling expand rows and scroll.

Base grid 4dp; outer margin 20dp; card padding 16dp; section gap 16dp; label-to-helper gap 4dp. Card radius 20dp, inset/input 12dp, button 12dp, sheet top corners 24dp, circular bubble 56dp diameter. Primary controls at least 48dp tall; ordinary rows at least 56dp.

## Icons, motion and surfaces

One rounded outline family: 24dp viewport, approximately 2dp stroke, rounded caps and joins. Use microphone, message, briefcase, envelope, more-horizontal, shield/check, document, keyboard, settings, chevron and back. Avoid emoji as interface icons. Accent is for interactive/status emphasis, not every icon. Success, warning and recording always include words or shape, never colour alone.

Flat fills; no gradients or texture in implementation. Home cards 0dp elevation; sheets 8dp; overlay 8dp. Gentle 150ms colour and 180ms expand transitions; respect reduced/disabled animation settings. No perpetual decorative pulsing. Meter animation reflects microphone amplitude only.

## App icon handoff

`icon.png` is the square solid-background concept. It can occupy an opaque foreground layer over the matching solid background; it is not a separated transparent production asset. Use background token `#111916` for both light/dark launcher variants. Keep all meaningful geometry inside Android's central safe zone; inspect circle, squircle and rounded-square masks. This PNG is generated artwork; trace/rebuild a clean VectorDrawable during implementation and supply transparent foreground and monochrome adaptive layers. Do not bake rounded outer corners into the resource. [Android adaptive-icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) specifies 108×108dp layers and the central 66×66dp safe zone. Two generated transparent extractions showed stray pixels and were rejected rather than included in this handoff.
