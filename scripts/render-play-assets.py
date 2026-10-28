#!/usr/bin/env python3
from io import BytesIO
from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs/google-play/graphics"
ANDROID = "{http://schemas.android.com/apk/res/android}"
OUTPUT.mkdir(parents=True, exist_ok=True)
vector = ET.parse(ROOT / "app/src/main/res/drawable/se_launcher_foreground.xml").getroot()
group = vector.find("group")
paths = []
for path in group.findall("path"):
    attributes = {"d": path.get(ANDROID + "pathData"), "fill": path.get(ANDROID + "fillColor", "none")}
    for source, target in (("strokeColor", "stroke"), ("strokeWidth", "stroke-width"), ("strokeLineCap", "stroke-linecap")):
        if path.get(ANDROID + source): attributes[target] = path.get(ANDROID + source)
    paths.append(ET.tostring(ET.Element("path", attributes), encoding="unicode"))
svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">'
       '<rect width="108" height="108" fill="#111916"/>'
       f'<g transform="translate({group.get(ANDROID + "translateX")} {group.get(ANDROID + "translateY")}) '
       f'scale({group.get(ANDROID + "scaleX")} {group.get(ANDROID + "scaleY")})">'
       + "".join(paths) + '</g></svg>')
(OUTPUT / "store-icon.svg").write_text(svg + "\n")
Image.open(BytesIO(cairosvg.svg2png(bytestring=svg.encode()))).convert("RGBA").save(OUTPUT / "store-icon.png")

image = Image.new("RGB", (1024, 500), "#111916")
draw = ImageDraw.Draw(image)
fonts = ROOT / "app/src/main/res/font"
def font(weight, size):
    return ImageFont.truetype(str(fonts / f"manrope_{weight}.ttf"), size)

draw.text((64, 56), "Speakeasy", font=font("bold", 29), fill="#A7E6C1")
draw.text((60, 130), "Speak naturally.", font=font("bold", 62), fill="#F5F3EA")
draw.text((60, 207), "Keep your keyboard.", font=font("bold", 62), fill="#F5F3EA")
draw.text((64, 313), "On-device dictation for everyday writing.", font=font("regular", 25), fill="#BBC8BF")
heights = (10, 18, 30, 44, 24, 14, 34, 50, 29, 16, 8)
for index, height in enumerate(heights):
    x = 68 + index * 18
    draw.rounded_rectangle((x, 416 - height / 2, x + 5, 416 + height / 2), radius=3, fill="#A7E6C1")
draw.line((297, 416, 954, 416), fill="#354B3E", width=1)
image.save(OUTPUT / "feature-graphic.png")
print(f"Wrote store icon and feature graphic to {OUTPUT}")
