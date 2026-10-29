"""
Builds Miss Minutes' ghost as Lottie animations, one file per emotion, into app/src/main/assets/ghost/.
Run: python -I tools/ghost_lottie.py   (from the MissMinutes folder). Edit shapes or timings here, then re-run.

Canvas 200x200 at 30 fps. Layers are listed top first; inside a group, shapes listed first draw on top.
"""
import json
import os

FR = 30
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "ghost")

WHITE = [1, 1, 1]
LAVENDER = [0.86, 0.84, 0.97]
ICE = [0.84, 0.92, 1.0]
AMBER = [0.96, 0.76, 0.40]
EYE = [0.23, 0.13, 0.17]
MOUTH = [0.47, 0.12, 0.22]
BLUSH = [0.98, 0.64, 0.74]
RED = [0.93, 0.29, 0.33]
BLUE = [0.36, 0.67, 0.98]
GREEN = [0.30, 0.78, 0.47]
NAVY = [0.10, 0.12, 0.25]


# ---------- property helpers ----------

def still(v):
    return {"a": 0, "k": v}


def keys(*frames):
    """Animated property from (frame, value) pairs, eased in and out."""
    out = []
    for n, (t, v) in enumerate(frames):
        v = v if isinstance(v, list) else [v]
        k = {"t": t, "s": v}
        if n < len(frames) - 1:
            k["i"] = {"x": [0.45] * len(v), "y": [1] * len(v)}
            k["o"] = {"x": [0.55] * len(v), "y": [0] * len(v)}
        out.append(k)
    return {"a": 1, "k": out}


def prop(v):
    return v if isinstance(v, dict) else still(v)


def tr(p=(0, 0), a=None, s=100, r=0, o=100):
    a = p if a is None else a
    return {"ty": "tr", "p": prop(list(p) if isinstance(p, tuple) else p),
            "a": prop(list(a) if isinstance(a, tuple) else a),
            "s": prop([s, s] if isinstance(s, (int, float)) else s), "r": prop(r), "o": prop(o),
            "sk": still(0), "sa": still(0)}


def group(name, items, transform=None):
    return {"ty": "gr", "nm": name, "it": items + [transform or tr()]}


def ellipse(x, y, w, h):
    return {"ty": "el", "p": still([x, y]), "s": still([w, h]), "d": 1}


def rect(x, y, w, h, r):
    return {"ty": "rc", "p": still([x, y]), "s": still([w, h]), "r": still(r), "d": 1}


def path(points, closed=False):
    """points: (x, y, in_dx, in_dy, out_dx, out_dy)"""
    return {"ty": "sh", "d": 1, "ks": still({
        "v": [[p[0], p[1]] for p in points],
        "i": [[p[2], p[3]] for p in points],
        "o": [[p[4], p[5]] for p in points],
        "c": closed})}


def fill(c, o=100):
    return {"ty": "fl", "c": still(c + [1]), "o": prop(o), "r": 1}


def stroke(c, w, o=100):
    return {"ty": "st", "c": still(c + [1]), "o": prop(o), "w": still(w), "lc": 2, "lj": 2, "ml": 4}


def linear(c1, c2, start, end):
    return {"ty": "gf", "o": still(100), "r": 1, "t": 1, "s": still(start), "e": still(end),
            "g": {"p": 2, "k": still([0] + c1 + [1] + c2)}}


def radial(c, center, radius, alpha):
    return {"ty": "gf", "o": prop(alpha), "r": 1, "t": 2, "s": still(center),
            "e": still([center[0] + radius, center[1]]), "h": still(0), "a": still(0),
            "g": {"p": 3, "k": still([0] + c + [0.6] + c + [1] + c + [0, 0.85, 0.55, 0.4, 1, 0])}}


def trim(start, end, offset):
    return {"ty": "tm", "s": prop(start), "e": prop(end), "o": prop(offset), "m": 1}


def layer(ind, name, shapes, op, p=(100, 100), a=(100, 100), s=100, r=0, o=100, parent=None):
    lay = {"ddd": 0, "ind": ind, "ty": 4, "nm": name, "sr": 1, "ao": 0, "ip": 0, "op": op, "st": 0, "bm": 0,
           "ks": {"o": prop(o), "r": prop(r),
                  "p": prop(list(p) + [0] if isinstance(p, tuple) else p),
                  "a": prop(list(a) + [0]),
                  "s": prop([s, s, 100] if isinstance(s, (int, float)) else s)},
           "shapes": shapes}
    if parent:
        lay["parent"] = parent
    return lay


# ---------- the ghost ----------

def body_path():
    # A round dome over short sides, with a scalloped hem: wider and softer, like the reference sheet.
    pts = [(57, 148, 2, 4, 0, 0), (57, 104, 0, 0, 0, -30), (100, 54, -26, 0, 26, 0),
           (143, 104, 0, -30, 0, 0), (143, 148, 0, 0, -2, 4)]
    for x, y in [(132.25, 157), (121.5, 149), (110.75, 157), (100, 149), (89.25, 157), (78.5, 149), (67.75, 157)]:
        pts.append((x, y, 4.5, 0, -4.5, 0))
    return path(pts, closed=True)


def body(tint=LAVENDER, outline=AMBER):
    return group("body", [body_path(), stroke(outline, 3), linear(WHITE, tint, [100, 50], [100, 158])])


def arms(left_r=-25, right_r=25, left=(60, 126), right=(140, 126)):
    def arm(name, x, y, r):
        return group(name, [ellipse(0, 0, 18, 13), stroke(AMBER, 2), fill(WHITE)],
                     tr(p=(x, y) if isinstance(x, (int, float)) else x, a=(0, 0), r=r))
    return [arm("arm L", *left, left_r), arm("arm R", *right, right_r)]


def eyes_open(op, blink_at=None, y=100):
    def eye(name, x):
        scale = still([100, 100])
        if blink_at is not None:
            b = blink_at
            scale = keys((0, [100, 100]), (b, [100, 100]), (b + 3, [100, 10]), (b + 6, [100, 100]), (op, [100, 100]))
        return group(name, [ellipse(x + 2.5, y - 4, 5, 5), fill(WHITE), ellipse(x, y, 13, 16), fill(EYE)],
                     tr(p=(x, y), s=scale))
    return [eye("eye L", 86), eye("eye R", 114)]


def eyes_closed(y=100):
    return [group(n, [path([(x - 6, y, 0, 0, 3, 4), (x + 6, y, -3, 4, 0, 0)]), stroke(EYE, 2.5)])
            for n, x in (("eye L", 86), ("eye R", 114))]


def eyes_happy(y=103):
    return [group(n, [path([(x - 6, y, 0, 0, 3, -7), (x + 6, y, -3, -7, 0, 0)]), stroke(EYE, 2.5)])
            for n, x in (("eye L", 86), ("eye R", 114))]


def blush():
    return [group("blush", [ellipse(78, 113, 11, 6), ellipse(122, 113, 11, 6), fill(BLUSH, 80)])]


def smile():
    return group("mouth", [path([(95, 112, 0, 0, 2, 3), (105, 112, -2, 3, 0, 0)]), stroke(EYE, 2.2)])


def frown():
    return group("mouth", [path([(95, 116, 0, 0, 2, -3), (105, 116, -2, -3, 0, 0)]), stroke(EYE, 2.2)])


def big_smile():
    return group("mouth", [path([(91, 110, 0, 11, 0, 0), (109, 110, 0, 0, 0, 11)], closed=True), fill(MOUTH)])


def talking(op):
    frames = [(t, [100, 100 if (t // 5) % 2 == 0 else 35]) for t in range(0, op, 5)] + [(op, [100, 100])]
    return group("mouth", [ellipse(100, 114, 9, 8), fill(MOUTH)], tr(p=(100, 114), s=keys(*frames)))


def glow(color, op, pulse=(55, 80), speed=None):
    lo, hi = pulse
    half = (speed or op) // 2
    alpha = keys(*[(t, lo if (t // half) % 2 == 0 else hi) for t in range(0, op + 1, half)])
    return group("glow", [ellipse(100, 104, 170, 170), radial(color, [100, 104], 85, alpha)])


def pedestal(op, bob):
    s = keys((0, [100, 100]), (op // 2, [100 - bob * 3, 100 - bob * 3]), (op, [100, 100]))
    return group("pedestal", [ellipse(100, 172, 64, 11), stroke(AMBER, 2), fill(AMBER, 25)], tr(p=(100, 172), s=s))


def bob(op, height=4):
    return keys((0, [100, 100, 0]), (op // 2, [100, 100 - height, 0]), (op, [100, 100, 0]))


def badge_circle(color, x=130, y=60, size=16):
    return [ellipse(x, y, size, size), fill(color)]


# ---------- emotions ----------

def ghost_layer(face, op, height=4, tint=LAVENDER, outline=AMBER, arm_items=None, extra=None, r=0):
    items = (extra or []) + face + blush() + (arm_items if arm_items is not None else arms()) + [body(tint, outline)]
    return layer(2, "ghost", items, op, p=bob(op, height), r=r)


def build(name, op, ghost, badges=(), glow_color=AMBER, glow_pulse=(55, 80), glow_speed=None, bob_h=4):
    layers = [dict(b, parent=2) for b in badges] + [ghost,
              layer(90, "pedestal", [pedestal(op, bob_h)], op),
              layer(91, "glow", [glow(glow_color, op, glow_pulse, glow_speed)], op)]
    for n, lay in enumerate(layers):
        if lay["ind"] != 2:
            lay["ind"] = 10 + n
    return {"v": "5.7.4", "fr": FR, "ip": 0, "op": op, "w": 200, "h": 200, "nm": name, "ddd": 0,
            "assets": [], "layers": layers}


def idle():
    op = 90
    return build("idle", op, ghost_layer(eyes_open(op, blink_at=60) + [smile()], op))


def listening():
    op = 60
    bars = []
    for n, x in enumerate([138, 143, 148, 153, 158]):
        h = keys(*[(t, [100, [35, 100, 60, 90, 45][(n + t // 6) % 5]]) for t in range(0, op + 1, 6)])
        bars.append(group(f"bar {n}", [rect(x, 64, 3, 14, 1.5), fill(BLUE)], tr(p=(x, 64), s=h)))
    waves = layer(3, "sound", bars, op)
    return build("listening", op, ghost_layer(eyes_open(op, blink_at=40) + [smile()], op), [waves],
                 glow_pulse=(60, 85), glow_speed=20)


def loading():
    op = 60
    ring = group("ring", [ellipse(100, 0, 120, 30), trim(0, 28, keys((0, 0), (op, 360))), stroke(BLUE, 2.5)],
                 tr(p=(100, 96), a=(100, 0), r=-8))
    halo = group("halo", [ellipse(100, 36, 11, 11), stroke(BLUE, 2.5)],
                 tr(p=(100, 36), s=keys((0, [100, 100]), (op // 2, [80, 80]), (op, [100, 100]))))
    spin = layer(3, "spinner", [halo, ring], op)
    return build("loading", op, ghost_layer(eyes_closed() + [smile()], op), [spin], glow_speed=30)


def notification():
    op = 45
    excl = [path([(130, 54, 0, 0, 0, 0), (130, 61, 0, 0, 0, 0)]), stroke(WHITE, 2.5),
            ellipse(130, 65.5, 2.6, 2.6), fill(WHITE)]
    pop = keys((0, [90, 90]), (8, [118, 118]), (16, [100, 100]), (op, [100, 100]))
    badge = layer(3, "badge", [group("dot", excl + badge_circle(RED), tr(p=(130, 60), s=pop))], op)
    return build("notification", op, ghost_layer(eyes_open(op) + [smile()], op, height=8), [badge], bob_h=8)


def sleeping():
    # A Z floats up every 40 frames and lives 60, so two overlap; the last one wraps round to the loop's start.
    op, life = 120, 60
    zs = []
    for n, (start, size) in enumerate([(0, 70), (40, 85), (80, 100), (-40, 100)]):
        rise = keys((0, [0, 0]), (life, [14, -40]))
        fade = keys((0, 0), (10, 100), (40, 100), (life, 0))
        z = group("z", [path([(0, 0, 0, 0, 0, 0), (8, 0, 0, 0, 0, 0), (0, 9, 0, 0, 0, 0), (8, 9, 0, 0, 0, 0)]),
                        stroke(BLUE, 2.4)], tr(p=rise, a=(4, 4), o=fade))
        lay = layer(3 + n, f"z {n}", [z], op, p=(126, 68), a=(0, 0), s=size)
        lay.update(st=start, ip=max(start, 0), op=min(start + life, op))
        zs.append(lay)
    return build("sleeping", op, ghost_layer(eyes_closed() + [smile()], op, height=3), zs,
                 glow_pulse=(35, 55), glow_speed=60, bob_h=3)


def speaking():
    op = 60
    wave = arms(left_r=keys((0, -25), (15, -40), (30, -25), (45, -35), (60, -25)), right_r=25)
    return build("speaking", op, ghost_layer(eyes_open(op, blink_at=48) + [talking(op)], op, arm_items=wave),
                 glow_pulse=(60, 90), glow_speed=10)


def success():
    op = 60
    check = [path([(124.5, 60.5, 0, 0, 0, 0), (128.5, 64.5, 0, 0, 0, 0), (135, 56.5, 0, 0, 0, 0)]), stroke(WHITE, 2.4)]
    pop = keys((0, [0, 0]), (10, [125, 125]), (18, [100, 100]), (op, [100, 100]))
    badge = layer(3, "check", [group("check", check + badge_circle(GREEN), tr(p=(130, 60), s=pop))], op)
    sparkles = []
    for n, (x, y, d) in enumerate([(66, 58, 0), (142, 92, 12), (58, 96, 24)]):
        tw = keys((0, [0, 0]), (d + 6, [100, 100]), (d + 14, [0, 0]), (op, [0, 0]))
        sparkles.append(group(f"spark {n}", [path([(x - 4, y, 0, 0, 0, 0), (x + 4, y, 0, 0, 0, 0)]),
                                             path([(x, y - 4, 0, 0, 0, 0), (x, y + 4, 0, 0, 0, 0)]), stroke(AMBER, 1.8)],
                              tr(p=(x, y), s=tw)))
    up = arms(left_r=keys((0, -25), (12, -60), (24, -45), (36, -60), (60, -25)),
              right_r=keys((0, 25), (12, 60), (24, 45), (36, 60), (60, 25)))
    ghost = ghost_layer(eyes_happy() + [big_smile()], op, height=6, arm_items=up)
    return build("success", op, ghost, [badge, layer(4, "sparkles", sparkles, op)], bob_h=6)


def error():
    op = 60
    x = [path([(126, 56, 0, 0, 0, 0), (134, 64, 0, 0, 0, 0)]), path([(134, 56, 0, 0, 0, 0), (126, 64, 0, 0, 0, 0)]),
         stroke(WHITE, 2.4)]
    badge = layer(3, "badge", [group("x", x + badge_circle(RED))], op)
    shake = keys((0, 0), (4, -6), (8, 6), (12, -5), (16, 5), (20, 0), (op, 0))
    ghost = ghost_layer(eyes_open(op) + [frown()], op, outline=RED, r=shake, height=2)
    return build("error", op, ghost, [badge], glow_color=RED, glow_pulse=(50, 80), glow_speed=30, bob_h=2)


def typing():
    op = 60
    laptop = [group("logo", [path([(100, 142, 0, 0, 0, 0), (103.5, 148, 0, 0, 0, 0), (96.5, 148, 0, 0, 0, 0)], closed=True),
                             stroke(BLUE, 1.5)]),
              group("screen", [rect(100, 146, 38, 22, 3.5), fill(NAVY)]),
              group("base", [rect(100, 159, 50, 4, 2), fill([0.16, 0.19, 0.36])])]
    left = keys(*[(t, [85, 136 if (t // 4) % 2 == 0 else 133]) for t in range(0, op + 1, 4)])
    right = keys(*[(t, [115, 133 if (t // 4) % 2 == 0 else 136]) for t in range(0, op + 1, 4)])
    hands = arms(left_r=0, right_r=0, left=(left, None), right=(right, None))
    dots = []
    for n, dx in enumerate([-5, 0, 5]):
        blink = keys(*[(t, 100 if (t // 10) % 3 == n else 30) for t in range(0, op + 1, 10)])
        dots.append(group(f"dot {n}", [ellipse(134 + dx, 62, 3, 3), fill([0.35, 0.40, 0.55], blink)]))
    bubble = layer(3, "bubble", dots + [group("bubble", [rect(134, 62, 22, 13, 6.5), fill(WHITE)])], op)
    ghost = ghost_layer(eyes_open(op, blink_at=50, y=102) + [smile()], op, tint=ICE, arm_items=[],
                        extra=hands + laptop, height=2)  # hands on the keyboard, in front of the laptop
    return build("typing", op, ghost, [bubble], glow_speed=30, bob_h=2)


EMOTIONS = {"idle": idle, "listening": listening, "loading": loading, "notification": notification,
            "sleeping": sleeping, "speaking": speaking, "success": success, "error": error, "typing": typing}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, make in EMOTIONS.items():
        with open(os.path.join(OUT, f"{name}.json"), "w", encoding="utf-8") as f:
            json.dump(make(), f, separators=(",", ":"))
        print("wrote", name)
