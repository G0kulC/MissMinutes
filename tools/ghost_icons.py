"""
Draws Pico's app and notification icons from the same ghost shape as the Lottie animations (ghost_lottie.py).
Run: python tools/ghost_icons.py   (from the MissMinutes folder; not -I, which hides ghost_lottie.py).
"""
import os

from ghost_lottie import body_path

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")
CENTER = (100, 106)  # middle of the ghost in the animations' 200x200 canvas


def mapper(scale, cx, cy):
    return lambda x, y: (cx + (x - CENTER[0]) * scale, cy + (y - CENTER[1]) * scale)


def f(n):
    return f"{n:.2f}".rstrip("0").rstrip(".")


def body_d(m):
    k = body_path()["ks"]["k"]
    v, i, o = k["v"], k["i"], k["o"]
    x, y = m(*v[0])
    d = [f"M{f(x)},{f(y)}"]
    for n in range(len(v)):
        a, b = v[n], v[(n + 1) % len(v)]
        c1 = m(a[0] + o[n][0], a[1] + o[n][1])
        c2 = m(b[0] + i[(n + 1) % len(v)][0], b[1] + i[(n + 1) % len(v)][1])
        end = m(*b)
        d.append(f"C{f(c1[0])},{f(c1[1])} {f(c2[0])},{f(c2[1])} {f(end[0])},{f(end[1])}")
    return " ".join(d) + "Z"


def oval_d(m, scale, x, y, w, h):
    cx, cy = m(x, y)
    rx, ry = w / 2 * scale, h / 2 * scale
    return (f"M{f(cx - rx)},{f(cy)}a{f(rx)},{f(ry)} 0,1 0,{f(2 * rx)},0"
            f"a{f(rx)},{f(ry)} 0,1 0,{f(-2 * rx)},0Z")


EYES = [(86, 100, 13, 16), (114, 100, 13, 16)]
SHINE = [(88.5, 96, 5, 5), (116.5, 96, 5, 5)]
BLUSH = [(78, 113, 11, 6), (122, 113, 11, 6)]


def smile_d(m):
    a, c1, c2, b = m(95, 112), m(97, 115), m(103, 115), m(105, 112)
    return f"M{f(a[0])},{f(a[1])}C{f(c1[0])},{f(c1[1])} {f(c2[0])},{f(c2[1])} {f(b[0])},{f(b[1])}"


def launcher_foreground():
    s, m = 0.5, mapper(0.5, 54, 56)
    eyes = "".join(oval_d(m, s, *e) for e in EYES)
    shine = "".join(oval_d(m, s, *e) for e in SHINE)
    blush = "".join(oval_d(m, s, *e) for e in BLUSH)
    gx, gy = m(100, 104)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- App icon foreground: Pico the ghost (made by tools/ghost_icons.py), inside the 66dp safe zone of a 108dp icon. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M{f(gx - 40)},{f(gy)}a40,40 0,1 0,80,0a40,40 0,1 0,-80,0Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="{f(gx)}" android:centerY="{f(gy)}" android:gradientRadius="40">
                <item android:offset="0" android:color="#B3F5C266" />
                <item android:offset="0.55" android:color="#40F5C266" />
                <item android:offset="1" android:color="#00F5C266" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:strokeColor="#FFF5C266" android:strokeWidth="1.5" android:pathData="{body_d(m)}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="54" android:startY="{f(m(0, 50)[1])}"
                android:endX="54" android:endY="{f(m(0, 158)[1])}">
                <item android:offset="0" android:color="#FFFFFFFF" />
                <item android:offset="1" android:color="#FFDBD6F7" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:fillColor="#CCF9A3BD" android:pathData="{blush}" />
    <path android:fillColor="#FF3B212B" android:pathData="{eyes}" />
    <path android:fillColor="#FFFFFFFF" android:pathData="{shine}" />
    <path android:strokeColor="#FF3B212B" android:strokeWidth="1.1" android:strokeLineCap="round" android:pathData="{smile_d(m)}" />
</vector>
"""


def silhouette(comment, size, viewport, scale, cx, cy):
    """One white shape with the eyes cut out: for themed icons and the status bar."""
    m = mapper(scale, cx, cy)
    holes = "".join(oval_d(m, scale, *e) for e in EYES)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} (made by tools/ghost_icons.py) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">
    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{body_d(m)} {holes}" />
</vector>
"""


if __name__ == "__main__":
    files = {
        "ic_launcher_foreground.xml": launcher_foreground(),
        "ic_launcher_monochrome.xml": silhouette("Themed app icon: Pico's outline", 108, 108, 0.5, 54, 56),
        "ic_stat_minutes.xml": silhouette("Notification icon: Pico's outline", 24, 24, 0.19, 12, 12.5),
    }
    for name, xml in files.items():
        with open(os.path.join(RES, name), "w", encoding="utf-8") as fh:
            fh.write(xml)
        print("wrote", name)
