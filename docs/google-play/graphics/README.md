# Play listing artwork

- `store-icon.png`: 512×512, RGBA PNG, opaque background, no baked corners.
- `feature-graphic.png`: 1024×500, RGB PNG without alpha.

The icon uses the shipped adaptive icon's vector mark. The feature graphic uses
the app's Manrope fonts and brand colours. These are listing artwork, not device
screenshots. Capture at least two screenshots from the tested release separately.

Regenerate with `python3 scripts/render-play-assets.py` in an environment with
Pillow and CairoSVG. The script reads the bundled vector and fonts; it does not
download assets. The font's OFL notice is in
`app/src/main/assets/notices/Manrope-OFL.txt`.

Dimensions and formats follow Google's
[preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151).
