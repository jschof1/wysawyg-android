> Lifecycle amendment, 29 October 2026: [persistent dictation](../../recording-persistence-plan.md) supersedes this design's Cancel/discard and keyboard-bound lifetime assumptions. Holding Stop now means Stop & keep; Pause only prevents new floating starts.

# Speakeasy — native Android implementation specification

Recommended direction A. This document is authoritative where generated images simplify spacing, introduce tonal variation or omit off-screen states. Design-only handoff: no app source was changed and no runtime behaviour is verified by these mockups.

## 1. Platform and tokens

XML views, AppCompat and Material Components **1.12.0**; no Compose. Use a DayNight no-action-bar theme, MaterialToolbar, NestedScrollView containing one vertical layout, MaterialCardView, MaterialButton, TextInputLayout/TextInputEditText, MaterialSwitch, ChipGroup/Chip, BottomSheetDialogFragment and LinearProgressIndicator. Use the existing Activity/Fragment architecture; no architectural migration is required by this design.

Define the semantic colours from `brand.md` in `values/colors.xml` and `values-night/colors.xml`. Exact mapping:

| Token | Light | Dark |
|---|---|---|
| background | #F6F5EF | #111916 |
| surface | #FFFFFF | #1B2621 |
| hero | #E7F1E9 | #22382D |
| raised | #EDF0E9 | #293D32 |
| textPrimary | #172A21 | #F5F3EA |
| textSecondary | #4D6356 | #BBC8BF |
| accent | #245C43 | #A7E6C1 |
| onAccent | #FFFFFF | #10251A |
| controlOutline | #6B8072 | #7F998B |
| divider | #D7E1D9 | #354B3E |
| error | #A32828 | #FFB4AB |
| warning / brass | #76551C | #DEC58B |

Map colorPrimary to accent, colorOnPrimary to onAccent, colorSurface to surface, colorOnSurface to textPrimary, colorError to error and window background to background. Explicitly style text, icon tint, ripple, stroke, thumb and track state lists so no purple baseline colour survives. System bars match background with theme-appropriate icon brightness. Consume status, navigation and IME insets; do not hard-code a status bar height. Follow system day/night; no new appearance settings are necessary.

## 2. Layout and component grammar

- Reference width 412dp, but support 320–600dp. Centre content at max 560dp on wider screens. Outer margins 20dp (16dp below 360dp); 16dp between sections and inside cards. All heights below are minima, never fixed heights around multiline text.
- Typography: bundled Manrope 400/600/700. Wordmark 30sp/38sp line; screen/hero 26/34; section 20/28; row/body 16/24; metadata 14/20; button 16/20 at 600. Normal letter spacing, no all-caps buttons. Reserve 14sp semibold uppercase for optional group labels only.
- Card radius 20dp, input/inset/button 12dp, sheet 24dp top corners. Home cards have 0dp elevation and no essential border. Use 1dp decorative divider between rows. Outlined controls use 1dp controlOutline, focused input 2dp accent.
- Row: min 56dp; two-line 72dp. Start padding 16dp, optional icon 24dp, 12dp gap, flexible label, 12dp gap, current value, 20dp chevron, end padding 16dp. At large fonts put value below label, preserve chevron. Whole row is the action; do not make only the tiny chevron tappable.
- Buttons: min 48dp height, 16dp horizontal padding. Filled = accent/onAccent. Outlined = transparent surface/accent label/controlOutline stroke. Disabled = raised fill, textSecondary label, no ripple; explain the reason nearby in readable text. Do not apply group alpha.
- Switch: MaterialSwitch with min 48dp touch target. Checked track accent, thumb onAccent with check glyph in accent; unchecked track surface, outline controlOutline, thumb controlOutline. Disabled track raised, thumb textSecondary. Expose checked and enabled state to TalkBack. A labelled switch row must expose one logical toggle, not two competing focus stops.
- Value choices use rows with chevrons and a choice sheet, never a stack of text buttons. Status badges are noninteractive TextViews, not switches or chips. Choice chips are used only inside the style sheet; selection includes a checkmark. Chip touch target 48dp, visual min height 40dp, 8dp spacing, wrapping permitted.
- Keep the full screen vertically scrollable. Never shrink fonts to fit a reference image. At 200% font scale, everything remains reachable; previews and summaries wrap. No bottom navigation is needed for this small settings hierarchy.

## 3. Home, in order

1. **Header**: small 28dp doorway microphone, 12dp gap, Speakeasy wordmark; tagline below. Header content approximately 76dp plus system inset. The brand is not a giant hero.
2. **Floating dictation status card**: 20sp title; status text and icon below; one 48dp action on the right. On → Pause outlined. Paused → Turn on filled. Never show both actions enabled. Supporting line: Offline · English · On-device, but only while that engine is selected. In cloud mode say Cloud transcription · [configured host]; remove the offline/private-on-device claims.
3. **Setup**: incomplete state is expanded, title Finish setup, counts only the two setup checks (Accessibility required; background running recommended). Rows: Enable Accessibility / Required, and Allow background running / Recommended. These are navigable system-settings actions with chevrons, not toggles that claim to grant permission themselves. Supporting Accessibility copy: “Show the microphone and insert text into the app you're using.” Battery copy: “Help dictation stay available in the background.” Return to the app and re-read real state before changing a checkmark. Completed state shows Setup complete and check summaries, as in the mockups; tapping the disclosure collapses them to one 48dp summary row and expands them again. Preserve the user's expansion choice; do not conceal a newly missing permission.
4. **Writing style hero**: hero fill, 26sp heading, noninteractive On/Off badge, description Sound like yourself, everywhere. Two vertical examples using the real saved settings: Personal · Casual / See you at six; Email · Formal / See you at six. When a different style is saved, regenerate both examples. No decorative font changes. Button Make it yours opens Writing style. It remains available when automatic styling is off. Off state says “Standard formatting is in use”; show saved choices as inactive examples, not applied output.
5. **Tools group**: Try dictation → scratch screen; Recovered transcripts → list, with dynamic count badge including 0; More settings → destination below. Each is a 56dp navigation row. If recovery count is nonzero, add a 14sp helper “Saved when insertion failed · kept for 24h” and allow 72dp height.
6. **Footer**: Private by default. Ready when you are. Only use this local-mode home copy with accurate engine status; cloud mode footer says “Using your configured transcription server.”

### Status failures

Missing Accessibility overrides an apparent On setting: show Setup needed, warning icon, filled Enable Accessibility action. Do not display On merely because a preference is true. Background restriction while service works: On plus warning “Background running is restricted”; expose the battery step. If start/stop is in flight, show Starting… / Pausing…, prevent duplicate taps and announce the result. Failure returns to actual known state with Retry. Microphone denial gets its own actionable message and Android permission flow; no automatic retry loop. Try dictation may work without Accessibility if microphone access exists; do not disable the entire app because insertion setup is incomplete.

## 4. Writing style destination

Order matches `final-style.png`:

1. Back toolbar, title Writing style, subtitle Your words, for every app.
2. **Master switch** Match style to the app, helper Changes punctuation and capitals. Changes persist immediately. Switching off retains category choices, does not disable unrelated cleanup or voice commands, and uses existing standard formatting. Category rows remain editable to prepare choices; add “Saved styles apply when matching is on.”
3. **Your app styles** group, four rows: Personal messages — Casual; Work messages — Casual; Email — Formal; Everything else — Formal. These are defaults, not forced resets on migration. Existing user choices win. Use message, briefcase, envelope and more-horizontal icons. “Per-app” means these four categories, not a new per-package override editor. Unknown apps use Everything else; preserve the existing app classifier.
4. **Try your own words** hero card: helper Preview styles with your own text; labelled multiline input “Your text”, minimum 96dp, hint “Type a sentence to compare”; full-width Preview styles button. Mockup example input is “see you at six”. Render all four category outputs vertically, each with category + selected style and selectable text. Defaults: Personal/Work → “See you at six”; Email/Everything else → “See you at six.” Do not invent vocabulary or punctuation rules inconsistent with the actual formatter.
5. **Cleanup & voice commands** chevron row opens the settings destination in section 5.

### Style selection sheet

Title e.g. Personal messages. Three single-selection wrapping chips: Formal, Casual, Very casual. Each choice has explanatory sample below the group: Formal → capitals and punctuation / “See you at six.”; Casual → capitals and lighter punctuation / “See you at six”; Very casual → fewer capitals and lighter punctuation / “see you at six”. All three styles are available in every category per the brief. Selected choice is a draft until Save; Save disabled when unchanged. Cancel, back and swipe dismissal discard the draft. On Save update category row, home example and preview state together. Preserve current selection on rotation.

### Preview states

Preview never alters the saved categories and never inserts into another app. Run the same local formatting pipeline used for dictation. Empty/whitespace input: button disabled, explanation “Add some text to preview.” Editing text or styles marks old results “Preview out of date”; don't silently present stale output. Loading: Previewing… plus progress, keep input. Success: announce once, not each result row. Error: inline “Couldn't preview this text. Try again.”, preserve input and old result with stale label. A text preview must not contact the optional cloud transcription server. Keyboard inset keeps button reachable. Results can grow vertically; do not truncate the meaningful comparison.

## 5. Cleanup & voice commands

Three independent switch rows in this order, separated by 16dp:

- **Remove filler words** — “Remove fillers such as um, uh and erm.” Preserve saved default; sample design assumes on. Do not confuse filler removal with style matching.
- **Voice commands** — “Say ‘new line’ or ‘scratch that’. Corrections such as ‘at 5, no wait, 6’ keep the correction.” Default design on. Include a chevron help row Examples opening a short sheet; this is separate from the switch target. Implementation must verify what existing command handling supports before shipping examples.
- **Smart cleanup** — “Optional on-device AI for punctuation and corrections.” Default off. Secondary text “345 MB download required” until installed. Download is not triggered merely by opening this screen.

When Smart cleanup is requested without the model, keep the actual switch off and show a sheet: Download Smart cleanup?, “Downloads 345 MB. After download, cleanup runs on your phone.” Buttons Download 345 MB and Not now. Confirming download displays numeric transferred/total bytes, percentage and a 4dp LinearProgressIndicator, plus Cancel download (48dp). Show unknown totals as indeterminate; never fake progress. A download finishing is not readiness: say Preparing model… while verifying/loading. Set On only after success. Failure keeps off with readable reason and Retry; no loss of the ordinary speech model. Offline state explains that download needs internet but basic dictation is available offline. Low storage says “Not enough space to finish this download”; use the implementation's actual required-space calculation, not an invented threshold. Turning off retains the downloaded model; deleting it, if supported, is an explicit secondary action. Preserve/restore actual download state after process recreation. Do not promise resumability if the downloader restarts.

## 6. More settings and subsidiary screens

Order: **Input**, **Transcription**, **Settings backup**, **Support**.

- Input: Speakeasy keyboard row, status Not enabled / Enabled / Selected and chevron. Helper “Optional. Keep using your usual keyboard.” Open Android input settings or picker as appropriate. This optional choice never blocks Setup complete.
- Transcription: On-device transcription status row, helper Offline · English. Advanced · cloud transcription chevron opens a separate destination. Engine selection belongs there, not on home. Native radio rows select On-device (default) or Cloud; cloud selection requires valid configuration. Cloud page fields in order: Server URL, API key (password field with show/hide), Model, Prompt (multiline). Persistent labels, 56dp min single-line fields, 12dp gaps. Save button; validation inline; cancel/back keeps persisted values unchanged. Explain “Audio is sent to your configured server when Cloud is selected.” No claim that cloud is offline. Redact API key from logs/export, show no secret in the ordinary summary. Never switch away from local because a field gained focus.
- Settings backup: Export settings and Import settings, separate chevron rows with system document picker. Export excludes credentials and recovered transcripts; explain this before creating the file. Import validates first, shows a summary and Replace settings confirmation, preserves existing settings on malformed/unsupported files. Cancellation is harmless. State whether older versions are supported; do not invent migration success. Imported credentials must not be displayed or logged.
- Support: Speech model licences row (scrollable selectable licence text and font licence); Logs row (View, Share, Clear within that destination). Logs use readable 14sp monospace, filter-free initial list and time stamps; empty copy No logs yet. Share uses Android Sharesheet and redacted content; Clear requires confirmation. Do not add analytics or send diagnostics automatically.
- Try dictation: back toolbar, short instructions “Tap to record. Tap again to stop. Hold to cancel.”, large scratch input, explicit recording control, timer/status; result stays in scratch field. Clear is explicit and disabled when empty. No automatic clipboard changes, message sending or switching to cloud.
- Recovered transcripts: count reflects nonexpired records. Empty: “Nothing to recover” / “If text can't be inserted, it will appear here for up to 24 hours.” Nonempty: preview, saved time, expiry time/remaining duration, Copy and Delete. Detail provides selectable full text. No hidden replay into another app. Deleting requires confirmation or short Undo while the record still exists. Expire based on the existing 24-hour retention contract even if the list remains open; remove expired entries and announce calmly. Report failed insertion through overlay text “Saved to Recovered transcripts”; never claim insertion succeeded.

## 7. Floating bubble and recording panel

Keep existing permission and overlay mechanisms. This is a visual/interaction specification, not permission to redesign the recording service.

Idle bubble: 56dp circular accent fill, 24dp onAccent microphone, 1dp controlOutline ring, 8dp elevation. Default right margin 16dp, at least 12dp above keyboard top; if composer bounds are available, place above them too. User can drag to an unclipped position. Clamp to usable screen/window bounds after rotation or keyboard changes. Preserve the user's position. Label for accessibility: Start dictation. Do not use the detailed brand mark as the recording action glyph.

Active recording expands towards screen centre to width min(336dp, availableWidth − 32dp), minimum height 96dp, 24dp radius. Solid dark surface #1B2621 with #F5F3EA text in both host themes for consistent recognition. Border #7F998B at 1dp, dark shadow at 8dp. Title row: small brand microphone, Speakeasy at 14sp semibold, Recording with status dot and text. Main row: timer 20sp tabular figures, live meter, 56dp circular stop control in #A7E6C1 with #10251A square. Footer “Tap to stop · Hold to cancel” at 14sp; at large fonts wrap and grow panel. Preserve at least 48dp touch targets and usable IME area. If there is insufficient horizontal room, stack meter below timer rather than shrinking text.

Meter: 12 bars, 3dp width, 3dp gaps, heights 4–24dp, mint fill. Animate actual amplitude; silence has low bars, not a fake active waveform. Respect disabled animation settings while retaining timer/status. Do not announce every timer tick to TalkBack.

Tap bubble starts. Tap stop ends recording and transcribes; it does not send the host message. Hold active stop cancels and discards audio. Drag begins only beyond touch slop; a drag must never trigger start, stop or cancel. Preserve existing gesture timings unless tested changes are requested; provide an accessibility Cancel recording action and a visible Cancel action in the expanded accessible layout so long-press is not the sole route. Use haptic feedback when the app already supports it. The mockup shows the expanded recording bubble, not two independent recording controls.

Transcribing: replace waveform with progress and Transcribing…; prevent new recording while processing, preserve actual supported cancellation. Success collapses to idle only after insertion result is known. Failed insertion says Saved to Recovered transcripts and offers Open; if persistence itself fails, show explicit error rather than recovery success. Microphone unavailable/permission revoked: stop safely and show actionable state. No automatic send. No service behaviour is claimed by the image alone.

## 8. Verification required when implemented

Test both themes on Jack's phone and an emulator; compare against the original dark-purple failure. Check the specified token pairs after compositing actual pressed/disabled states. Verify 320dp width, 200% font scale, TalkBack order, keyboard insets, rotation and process recreation. Exercise paused/on/missing-permission/battery-restricted/cloud states; all three style choices in all four categories; preview empty/stale/error; download cancel/fail/ready; recovery zero/nonzero/expiry; import invalid/cancel; and recording stop/cancel/drag. Confirm no default-purple button or link remains. PNG review and contrast maths are completed design checks; these runtime checks remain for the implementing agent.

Version-specific references: [MaterialSwitch 1.12.0](https://raw.githubusercontent.com/material-components/material-components-android/1.12.0/docs/components/Switch.md), [Chips 1.12.0](https://raw.githubusercontent.com/material-components/material-components-android/1.12.0/docs/components/Chip.md). Use their supported XML attributes rather than Compose components.
