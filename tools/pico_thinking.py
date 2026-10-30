"""
Builds Pico's thinking animation: the designed ghost (lottie_animations/ship-thinking.json, left exactly as drawn)
with a small thought cloud beside her saying "Hmm... Let's see...", plus a question mark, a gear and a lightbulb.
She raises a hand to her chin and one eyebrow. Writes app/src/main/assets/pico/thinking.json.
Run: python tools/pico_thinking.py   (from the MissMinutes folder; not -I, which hides ghost_lottie.py).

512x512 at 30 fps, 120 frames (4 s): bubbles and cloud pop in (0-1 s), she thinks (1-3.3 s), the cloud shrinks
away as her eyes come back (3.3-4 s), so each loop starts fresh with no jump.
"""
import json
import math
import os

from ghost_lottie import ellipse, fill, group, keys, linear, path, radial, rect, still, stroke, tr

HERE = os.path.dirname(__file__)
SOURCE = os.path.join(HERE, "..", "..", "lottie_animations", "ship-thinking.json")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "pico", "thinking.json")
OP = 120

NAVY = [0.12, 0.23, 0.54]
BLUE = [0.23, 0.51, 0.96]
OUTLINE = [0.55, 0.55, 0.96]
WHITE = [1, 1, 1]
LAVENDER = [0.93, 0.94, 1.0]
BULB = [1.0, 0.84, 0.29]
EYE = [0.05, 0.09, 0.16]  # the ghost's own eye colour (#0d1728)


def shape_layer(ind, name, shapes, p=(0, 0), a=(0, 0), s=100, r=0, o=100, parent=None):
    lay = {"ddd": 0, "ind": ind, "ty": 4, "nm": name, "sr": 1, "ao": 0, "ip": 0, "op": OP, "st": 0, "bm": 0,
           "ks": {"o": o if isinstance(o, dict) else still(o), "r": r if isinstance(r, dict) else still(r),
                  "p": p if isinstance(p, dict) else still(list(p) + [0]), "a": still(list(a) + [0]),
                  "s": s if isinstance(s, dict) else still([s, s, 100])},
           "shapes": shapes}
    if parent:
        lay["parent"] = parent
    return lay


def null_layer(ind, name, p, a=(0, 0), s=100, parent=None):
    lay = shape_layer(ind, name, [], p, a, s, parent=parent)
    lay["ty"] = 3
    del lay["shapes"]
    return lay


def text_layer(ind, name, text, x, y, size, opacity, parent):
    return {"ddd": 0, "ind": ind, "ty": 5, "nm": name, "sr": 1, "ao": 0, "ip": 0, "op": OP, "st": 0, "bm": 0,
            "parent": parent,
            "ks": {"o": opacity, "r": still(0), "p": still([x, y, 0]), "a": still([0, 0, 0]), "s": still([100, 100, 100])},
            "t": {"d": {"k": [{"s": {"s": size, "f": "Outfit-Bold", "t": text, "j": 2, "tr": 0, "lh": size * 1.2,
                                     "ls": 0, "fc": NAVY}, "t": 0}]},
                  "p": {}, "m": {"g": 1, "a": still([0, 0])}, "a": []}}


def cloud_outline(rx, ry, bumps):
    """A scalloped cloud: bumps around an ellipse, flatter along the bottom."""
    pts = [(rx * math.cos(2 * math.pi * i / bumps - math.pi / 2), ry * math.sin(2 * math.pi * i / bumps - math.pi / 2))
           for i in range(bumps)]
    ins = [[0, 0] for _ in pts]
    outs = [[0, 0] for _ in pts]
    for i in range(bumps):
        a, b = pts[i], pts[(i + 1) % bumps]
        mid = 2 * math.pi * (i + 0.5) / bumps - math.pi / 2
        nx, ny = math.cos(mid), math.sin(mid)
        chord = math.dist(a, b)
        bulge = chord * (0.32 if ny > 0.6 else 0.62)  # flatter bottom, puffier top and sides
        dx, dy = (b[0] - a[0]) * 0.12, (b[1] - a[1]) * 0.12
        outs[i] = [nx * bulge + dx, ny * bulge + dy]
        ins[(i + 1) % bumps] = [nx * bulge - dx, ny * bulge - dy]
    return path([(p[0], p[1], ins[i][0], ins[i][1], outs[i][0], outs[i][1]) for i, p in enumerate(pts)], closed=True)


def gear(r_out, r_in, teeth):
    pts = []
    for t in range(teeth):
        base = 2 * math.pi * t / teeth
        for ang, rad in ((base - 0.22, r_in), (base - 0.12, r_out), (base + 0.12, r_out), (base + 0.22, r_in)):
            pts.append((rad * math.cos(ang), rad * math.sin(ang), 0, 0, 0, 0))
    return path(pts, closed=True)


def pop(start, end_in, out_start, out_end, peak=112):
    """Scale 0 -> overshoot -> 100, held, then back to 0."""
    return keys((0, [0, 0]), (start, [0, 0]), (end_in, [peak, peak]), (end_in + 4, [100, 100]),
                (out_start, [100, 100]), (out_end, [0, 0]), (OP, [0, 0]))


def build():
    d = json.load(open(SOURCE, encoding="utf-8"))
    by_name = {l["nm"]: l for l in d["layers"]}
    rig = by_name["rig"]["ind"]
    # The old little thought dots and sparkle make way for the new cloud.
    layers = [l for l in d["layers"] if l["nm"] != "fx"]

    # A stage that holds the whole ghost, a little smaller and lower left, so the cloud fits up and to the right.
    stage = 50
    for l in layers:
        if "parent" not in l:
            l["parent"] = stage
    layers.append(null_layer(stage, "stage", p=(196, 318), a=(256, 300), s=74))

    # Her hand rises to her chin, in the body's own colours (so it follows the chosen theme too).
    body_fill = next(g for g in by_name["body"]["shapes"] if g["nm"] == "fill")
    gradient = json.loads(json.dumps(next(i for i in body_fill["it"] if i["ty"] == "gf")))
    gradient["s"] = still([0, -14]); gradient["e"] = still([0, 14])
    hand_pos = keys((0, [-80, 60, 0]), (6, [-80, 60, 0]), (18, [-28, 16, 0]), (94, [-28, 16, 0]), (108, [-80, 60, 0]),
                    (OP, [-80, 60, 0]))
    hand_op = keys((0, 0), (6, 0), (12, 100), (100, 100), (106, 0), (OP, 0))
    # A darker mint rim makes it read as a paw against her body (mint, so it follows the theme as well).
    hand = group("hand", [ellipse(0, 0, 38, 28), stroke([0.15, 0.78, 0.80], 3), gradient], tr(p=(0, 0), r=-32))
    hand_layer = shape_layer(61, "hand", [hand], p=hand_pos, o=hand_op, parent=rig)

    # Eyebrows follow her eyes' glance (+11, -12); the right one lifts a little more, curious.
    glance = keys((0, [0, 0, 0]), (10, [11, -12, 0]), (96, [11, -12, 0]), (104, [0, 0, 0]), (OP, [0, 0, 0]))
    brow_op = keys((0, 0), (8, 0), (14, 100), (96, 100), (102, 0), (OP, 0))
    left_brow = group("left brow", [path([(-44, -50, 0, 0, 6, -3), (-20, -52, -6, -2, 0, 0)]), stroke(EYE, 5)])
    right_brow = group("right brow", [path([(18, -56, 0, 0, 6, -6), (42, -58, -6, -4, 0, 0)]), stroke(EYE, 5)],
                       tr(p=keys((0, [0, 0]), (14, [0, 0]), (22, [0, -7]), (90, [0, -7]), (98, [0, 0]), (OP, [0, 0])), a=(0, 0)))
    brows = shape_layer(62, "brows", [left_brow, right_brow], p=glance, o=brow_op, parent=rig)

    # ---------- the thought cloud (world coordinates) ----------
    cx, cy = 372, 106
    bubbles = shape_layer(70, "bubbles", [
        group("bubble 1", [ellipse(0, 0, 13, 13), stroke(OUTLINE, 2), fill(WHITE)],
              tr(p=(256, 202), a=(0, 0), s=pop(2, 7, 106, 112))),
        group("bubble 2", [ellipse(0, 0, 20, 20), stroke(OUTLINE, 2), fill(WHITE)],
              tr(p=(276, 182), a=(0, 0), s=pop(5, 10, 102, 108))),
    ])
    cloud_rig = 80
    float_y = keys((0, [cx, cy, 0]), (60, [cx, cy - 3, 0]), (OP, [cx, cy, 0]))
    outline = cloud_outline(114, 72, 10)
    cloud = shape_layer(81, "cloud", [
        group("glow", [outline, stroke([0.44, 0.55, 1.0], 12, 22)]),
        group("cloud", [outline, stroke(OUTLINE, 3), linear(WHITE, LAVENDER, [0, -70], [0, 70])]),
    ], parent=cloud_rig)
    hmm = text_layer(82, "Hmm", "Hmm…", -22, -20, 42, keys((0, 0), (11, 0), (18, 100), (OP, 100)), cloud_rig)
    see = text_layer(83, "Let's see", "Let’s see…", -22, 24, 42, keys((0, 0), (15, 0), (23, 100), (OP, 100)), cloud_rig)

    # A wide, open curl and a thinner line, so it still reads as "?" (not "!") when Pico is small.
    qmark = shape_layer(84, "question", [group("q", [
        path([(-9, -9, 0, 0, 0, -13), (9, -9, 0, -13, 0, 8), (1, 5, 3, -6, 0, 0), (1, 10, 0, 0, 0, 0)]), stroke(BLUE, 3.6),
        ellipse(1, 18, 5, 5), fill(BLUE)], tr(s=125))],
        p=keys((0, [84, -28, 0]), (40, [84, -28, 0]), (46, [84, -40, 0]), (52, [84, -28, 0]), (56, [84, -33, 0]),
               (60, [84, -28, 0]), (OP, [84, -28, 0])),
        parent=cloud_rig)
    cog = shape_layer(85, "gear", [group("gear", [ellipse(0, 0, 8, 8), fill(WHITE), gear(13, 9.5, 8), fill(BLUE)])],
                      p=(56, 40), r=keys((0, 0), (OP, 360)), parent=cloud_rig)
    for kf in cog["ks"]["r"]["k"][:-1]:  # a steady turn, not eased
        kf["i"] = {"x": [1], "y": [1]}; kf["o"] = {"x": [0], "y": [0]}
    pulse = keys(*[(t, 35 if (t // 20) % 2 == 0 else 90) for t in range(0, OP + 1, 20)])
    bulb = shape_layer(86, "bulb", [
        group("glass", [ellipse(0, -5, 18, 18), fill(BULB)]),
        group("neck", [rect(0, 6, 9, 6, 1.5), fill([0.78, 0.63, 0.24])]),
        group("base", [rect(0, 11, 7, 3, 1.5), fill([0.55, 0.47, 0.30])]),
        group("glow", [ellipse(0, -5, 40, 40), radial(BULB, [0, -5], 20, pulse)]),
    ], p=(86, 36), parent=cloud_rig)
    sparkles = shape_layer(87, "sparkles", [
        group(f"spark {n}", [path([(0, -5, 0, 0, 0, 0), (1.2, -1.2, 0, 0, 0, 0), (5, 0, 0, 0, 0, 0), (1.2, 1.2, 0, 0, 0, 0),
                                   (0, 5, 0, 0, 0, 0), (-1.2, 1.2, 0, 0, 0, 0), (-5, 0, 0, 0, 0, 0), (-1.2, -1.2, 0, 0, 0, 0)],
                                  closed=True), fill(BULB)],
              tr(p=(x, y), a=(0, 0), s=keys((0, [0, 0]), (dl, [0, 0]), (dl + 8, [100, 100]), (dl + 16, [0, 0]), (OP, [0, 0]))))
        for n, (x, y, dl) in enumerate([(-124, -42, 30), (126, 12, 54), (100, -82, 78)])
    ], parent=cloud_rig)
    # Quick: Haiku often answers within 1.5 s, so the words have to be up by about 0.8 s.
    grow = pop(7, 14, 98, 106, peak=108)
    for kf in grow["k"]:
        kf["s"] = kf["s"] + [100]  # a layer's scale has three values
        if "i" in kf:
            kf["i"] = {"x": [0.45] * 3, "y": [1] * 3}; kf["o"] = {"x": [0.55] * 3, "y": [0] * 3}
    cloud_null = null_layer(cloud_rig, "cloud rig", p=float_y, s=grow)

    # Top first: sparkles and icons, text, cloud, bubbles, then her brows and hand over her own layers.
    d["layers"] = [sparkles, qmark, cog, bulb, hmm, see, cloud, cloud_null, bubbles, brows, hand_layer] + layers
    d["fonts"] = {"list": [{"fName": "Outfit-Bold", "fFamily": "Outfit", "fStyle": "Bold", "ascent": 75}]}
    d["nm"] = "thinking"
    return d


if __name__ == "__main__":
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(build(), f, separators=(",", ":"), ensure_ascii=False)
    print("wrote", OUT)
