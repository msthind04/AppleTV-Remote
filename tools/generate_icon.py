# Build the launcher-icon path once, then emit it as both an Android vector
# drawable and an SVG, so the shipped XML can be verified by rendering.
VIEW = 108.0        # adaptive icon viewport (dp)
VISIBLE = 72.0      # diameter the launcher actually shows

BODY_W = VISIBLE * 0.235
BODY_H = VISIBLE * 0.62
X0 = (VIEW - BODY_W) / 2
Y0 = (VIEW - BODY_H) / 2
X1, Y1 = X0 + BODY_W, Y0 + BODY_H

def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")

def rrect(x0, y0, x1, y1, r):
    return (
        f"M{f(x0+r)},{f(y0)} H{f(x1-r)} A{f(r)},{f(r)} 0 0 1 {f(x1)},{f(y0+r)} "
        f"V{f(y1-r)} A{f(r)},{f(r)} 0 0 1 {f(x1-r)},{f(y1)} H{f(x0+r)} "
        f"A{f(r)},{f(r)} 0 0 1 {f(x0)},{f(y1-r)} V{f(y0+r)} "
        f"A{f(r)},{f(r)} 0 0 1 {f(x0+r)},{f(y0)} Z"
    )

def circle(cx, cy, r):
    return (
        f"M{f(cx-r)},{f(cy)} A{f(r)},{f(r)} 0 1 0 {f(cx+r)},{f(cy)} "
        f"A{f(r)},{f(r)} 0 1 0 {f(cx-r)},{f(cy)} Z"
    )

def rel(rx, ry):
    return X0 + rx * BODY_W, Y0 + ry * BODY_H

parts = [rrect(X0, Y0, X1, Y1, BODY_W * 0.30)]

# Clickpad
cx, cy = rel(0.465, 0.229)
parts.append(circle(cx, cy, 0.37 * BODY_W))

# Two buttons under the pad, then the left column
dot_r = 0.113 * BODY_W
for rx, ry in [(0.296, 0.466), (0.661, 0.466), (0.296, 0.598), (0.296, 0.729)]:
    bx, by = rel(rx, ry)
    parts.append(circle(bx, by, dot_r))

# Volume rocker
ax, ay = rel(0.557, 0.559)
bx, by = rel(0.783, 0.788)
parts.append(rrect(ax, ay, bx, by, (bx - ax) / 2))

path = " ".join(parts)

vector = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  Siri Remote silhouette. Buttons are cut out with evenOdd so the adaptive
  icon's background colour shows through them.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:fillType="evenOdd"
        android:pathData="{path}" />
</vector>
'''

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="#101014"/>
  <path fill="#FFFFFF" fill-rule="evenodd" d="{path}"/>
</svg>
'''

open("/home/msthind/Projects/AppleTV-Remote/app/src/main/res/drawable/ic_launcher_foreground.xml", "w").write(vector)
open("/tmp/claude-1000/-home-msthind-Projects-AppleTV-Remote/0fb61d80-f75c-4048-86ee-fa917b7fea9f/scratchpad/icon_check.svg", "w").write(svg)
print("path length:", len(path), "chars")
