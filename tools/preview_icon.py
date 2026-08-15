from PIL import Image, ImageDraw
import math

S = 4                      # supersampling factor
CANVAS = 432               # adaptive-icon canvas (108dp @ 4x)
VISIBLE = 288              # circle mask diameter (72dp @ 4x)

# Proportions measured from the reference image.
BODY_W_REL, BODY_H_REL = 0.235, 0.62
PAD = dict(cx=0.465, cy=0.229, r=0.37)
DOTS_ROW = dict(lx=0.296, rx=0.661, y=0.466, r=0.113)
LEFT_DOTS = [0.598, 0.729]
CAPSULE = dict(x0=0.557, x1=0.783, y0=0.559, y1=0.788)


def draw_remote(bg, body, hole):
    """Render one icon at CANVAS size with the given colours."""
    size = CANVAS * S
    img = Image.new("RGBA", (size, size), bg)
    d = ImageDraw.Draw(img)

    bw = VISIBLE * BODY_W_REL * S
    bh = VISIBLE * BODY_H_REL * S
    x0 = (size - bw) / 2
    y0 = (size - bh) / 2

    # Remote body
    d.rounded_rectangle([x0, y0, x0 + bw, y0 + bh], radius=bw * 0.30, fill=body)

    def px(rx, ry):
        return x0 + rx * bw, y0 + ry * bh

    # Clickpad
    cx, cy = px(PAD["cx"], PAD["cy"])
    r = PAD["r"] * bw
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=hole)

    # Two buttons under the pad
    r = DOTS_ROW["r"] * bw
    for rx in (DOTS_ROW["lx"], DOTS_ROW["rx"]):
        bx, by = px(rx, DOTS_ROW["y"])
        d.ellipse([bx - r, by - r, bx + r, by + r], fill=hole)

    # Left column
    for ry in LEFT_DOTS:
        bx, by = px(DOTS_ROW["lx"], ry)
        d.ellipse([bx - r, by - r, bx + r, by + r], fill=hole)

    # Volume rocker
    ax, ay = px(CAPSULE["x0"], CAPSULE["y0"])
    bx2, by2 = px(CAPSULE["x1"], CAPSULE["y1"])
    d.rounded_rectangle([ax, ay, bx2, by2], radius=(bx2 - ax) / 2, fill=hole)

    return img.resize((CANVAS, CANVAS), Image.LANCZOS)


def mask(img, shape):
    m = Image.new("L", (CANVAS * S, CANVAS * S), 0)
    md = ImageDraw.Draw(m)
    if shape == "circle":
        inset = (CANVAS - VISIBLE) / 2 * S
        md.ellipse([inset, inset, CANVAS * S - inset, CANVAS * S - inset], fill=255)
    else:  # squircle-ish
        inset = (CANVAS - VISIBLE * 1.06) / 2 * S
        md.rounded_rectangle(
            [inset, inset, CANVAS * S - inset, CANVAS * S - inset],
            radius=VISIBLE * 0.28 * S, fill=255)
    m = m.resize((CANVAS, CANVAS), Image.LANCZOS)
    out = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out


VARIANTS = [
    ("A  Charcoal",  (16, 16, 20, 255), (255, 255, 255, 255), (16, 16, 20, 255)),
    ("B  Blue",      (110, 168, 255, 255), (255, 255, 255, 255), (26, 74, 143, 255)),
    ("C  Warm taupe",(138, 112, 100, 255), (255, 255, 255, 255), (138, 112, 100, 255)),
]

pad, label_h = 28, 34
sheet_w = pad + len(VARIANTS) * (CANVAS + pad)
sheet_h = label_h + pad + (CANVAS + pad) * 2
sheet = Image.new("RGBA", (sheet_w, sheet_h), (245, 245, 248, 255))
sd = ImageDraw.Draw(sheet)

for i, (name, bg, body, hole) in enumerate(VARIANTS):
    icon = draw_remote(bg, body, hole)
    x = pad + i * (CANVAS + pad)
    sd.text((x + 6, 10), name, fill=(30, 30, 35, 255))
    sheet.paste(mask(icon, "circle"), (x, label_h), mask(icon, "circle"))
    sheet.paste(mask(icon, "squircle"), (x, label_h + CANVAS + pad),
                mask(icon, "squircle"))

sd.text((pad, label_h + CANVAS + pad // 3), "circle mask", fill=(90, 90, 95, 255))
sheet.save("/tmp/claude-1000/-home-msthind-Projects-AppleTV-Remote/0fb61d80-f75c-4048-86ee-fa917b7fea9f/scratchpad/icon_preview.png")
print("wrote preview", sheet.size)
