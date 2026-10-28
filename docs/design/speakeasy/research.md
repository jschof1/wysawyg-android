# Speakeasy — reference research

28 October 2026. Design research only. Sources are public first-party pages, published product imagery and current site CSS; this is not a hands-on audit of installed competitor apps. Exact competitor colours below are observed website CSS values, not claimed native-app tokens.

## What the supplied screenshots establish

Inspected `/tmp/wysa-shots/phone.png`, `main1.png`, `main2.png`, `dark2.png`. The real-phone screenshot has saturated purple text against grey cards, competing Turn on/Pause controls, a very large setup area, and style options rendered as ordinary text buttons. The later dark screenshot improves the accent but retains the same weak hierarchy. Writing style needs a visible example, clear settings rows and a dedicated destination. Completed setup needs much less space. Light and dark modes need semantic colour tokens, not colour substitution at individual buttons.

## Wispr Flow — useful personality and concrete style examples

- [Website](https://wisprflow.ai/) and [current stylesheet](https://cdn.prod.website-files.com/682f84b3838c89f8ff7667db/css/flowsite-dev.webflow.shared.dbf2a2d16.min.css): cream `#FFFFEB`, near-black `#1A1A1A`, lilac `#F0D7FF` and warm white `#FFFDF9` occur in the site palette. The stylesheet includes Figtree, EB Garamond, Inter and IBM Plex Mono. These are website observations; do not assume all are app fonts.
- [Personalized Style announcement](https://wisprflow.ai/post/personalized-style): category examples make punctuation and capitalisation tangible. Its published dark preview uses cream type on charcoal with a messaging icon; viewed image saved as `references/wispr-style.png` for research only.
- [Current Styles instructions](https://docs.wisprflow.ai/articles/2368263928-how-to-setup-flow-styles): Android separates app categories from a style selection sheet. Published style availability differs by category and platform; Speakeasy follows Jack's three-style brief instead.
- Takeaway: generous type and real output examples give settings a recognisable personality. Borrow the explanation pattern, not its assets, navigation structure or unsupported rewrite promises. No decorative illustration is needed inside the control-heavy screen.

## Superwhisper — progressive disclosure for a powerful tool

- [Website and product demonstrations](https://superwhisper.com/): visibly separate modes, vocabulary and configuration; mode controls expose model choices and app activation through labelled selections. The public page puts sophisticated controls behind Advanced settings.
- Current linked CSS uses Inter, neutral `#FAFAFA`, `#F0F0F0`, charcoal `#1C1D1F` and blue `#0072FB`. CSS is supporting palette evidence, not a native theme inventory.
- Takeaway: consistent sans type, neutral surfaces, app icons and small functional panels let powerful settings remain approachable. Use a familiar label/value/chevron row for a choice; use a switch only for a Boolean. Keep server, model and prompt controls in Advanced. Avoid importing its model-selection density into Speakeasy's home.

## Granola — character without making the working UI theatrical

- [Brand/design account](https://www.granola.ai/blog/a-new-look-for-granola), [product website](https://www.granola.ai/): its designers describe a green system, Quadrant slab-serif display type, Melange UI type and a deliberately imperfect hand-drawn logo. The stated goal is calm product use with an energetic identity.
- Takeaway: a distinctive mark and restrained brand details can carry the personality; ordinary reading and editing stay easy. Speakeasy uses a geometric doorway, warm neutrals and a small brass detail instead of borrowing the hand-drawn identity. This source does not establish exact toggle sizing or native settings behaviour; those are our design decisions.

## Linear — hierarchy, density and coherent themes

- [2026 interface refresh](https://linear.app/now/behind-the-latest-design-refresh), [theme design explanation](https://linear.app/now/how-we-redesigned-the-linear-ui), [Preferences](https://linear.app/docs/account-preferences): reduced icon decoration, quieter separators and navigation, predictable action placement, warmer neutral themes and controllable contrast. Preferences use controls appropriate to the setting, including dropdown choices and toggles.
- Takeaway: premium feeling comes from consistent spacing and visual priority. Use 56–64dp rows, restrained line icons and quiet dividers; reserve the strongest surface and action for Writing style. Dense desktop text sizes are not a suitable mobile target. Illustration would compete with these controls.

## Directions and recommendation

| Direction | Visual language | Why consider it | Trade-off |
|---|---|---|---|
| A — Quiet confidence | Forest ink, mint, warm ivory, Manrope, doorway microphone | Distinct identity; strong dark mode; calm, approachable privacy story | Must keep semantic contrast strong rather than making every surface muted |
| B — The daily note | Ivory, terracotta, Fraunces display with sans UI, paper-like samples | Editorial and warm; makes writing feel personal | Display serif and paper metaphor occupy more space |
| C — Signal | Cool white, cobalt, Plus Jakarta Sans, squared panels, waveform | Very clear and contemporary; strong information hierarchy | Closer to familiar productivity software, less distinctive |

Recommend A. The benefit to Jack is immediate legibility and a style feature he can understand and try without navigating technical configuration. First intended use: see whether dictation is on, resolve any permission issue, preview a sentence, then dictate into a real chat. The PNGs are proposals; implementation and phone verification are separate work.
