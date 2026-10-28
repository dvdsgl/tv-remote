#!/usr/bin/env python3
# Writes the hero animation's keyframes from a timeline in seconds (T = loop length).
T = 20.0
def pct(t): return f"{t / T * 100:.2f}%"
def kf(name, stops):
    out = [f"@keyframes {name} {{"]
    for t, props in stops:
        ts = ", ".join(pct(x) for x in (t if isinstance(t, tuple) else (t,)))
        out.append(f"  {ts} {{ {props} }}")
    out.append("}")
    return "\n".join(out)
def vis(name, segs, on, off):
    """segs: list of (start, end) when shown; fades 0.1 s at both ends."""
    st = [((0,), off)]
    for a, b in segs:
        st += [((a,), off), ((a + .1, b - .1), on), ((b,), off)]
    st.append(((T,), off))
    return kf(name, st)
F = lambda x, y: f"left: {x - 22}px; top: {y - 22}px;"
G = lambda x, y: f"left: {x - 20}px; top: {y - 120}px;"
icon, row, tile = (246, 568), (172, 668), (105, 216)
k3, k8, k1, k5 = (307, 452), (201, 640), (95, 452), (201, 546)
blocks = []
# Android-style launch: the app's window grows out of the icon (or the Quick Settings tile)
# as a rounded rectangle, straight into the remote (no icon splash).
# Closing shrinks it back into the icon. The home screen stays put underneath, receding a bit.
CI = "clip-path: inset(540px 128px 224px 218px round 28px);"   # the Remote icon on the home screen
CT = "clip-path: inset(184px 207px 572px 16px round 32px);"    # the Remote tile in Quick Settings
FULL = "clip-path: inset(0px 0px 0px 0px round 0px);"
blocks.append(kf("d-home", [((0, 1.25), "transform: scale(1);"), ((1.75, 11.3), "transform: scale(.94);"),
  ((11.8, 14.35), "transform: scale(1);"), ((14.85, 17.5), "transform: scale(.94);"), ((18.0, T), "transform: scale(1);")]))
blocks.append(kf("d-app", [((0, 1.25), "opacity: 0; " + CI), ((1.3,), "opacity: 1; " + CI), ((1.75, 11.3), "opacity: 1; " + FULL),
  ((11.75,), "opacity: 1; " + CI), ((11.8, 14.35), "opacity: 0; " + CI), ((14.36,), "opacity: 0; " + CT), ((14.4,), "opacity: 1; " + CT),
  ((14.85, 17.5), "opacity: 1; " + FULL), ((17.95,), "opacity: 1; " + CI), ((18.0, T), "opacity: 0; " + CI)]))
blocks.append(kf("d-name-a", [((0, 6.79), "opacity: 1;"), ((6.8, T), "opacity: 0;")]))
blocks.append(kf("d-name-b", [((0, 6.79), "opacity: 0;"), ((6.8, 19.9), "opacity: 1;"), ((T,), "opacity: 0;")]))
blocks.append(kf("d-scrim", [((0, 1.8), "opacity: 0;"), ((2.3, 3.4), "opacity: .6;"), ((3.9, T), "opacity: 0;")]))
blocks.append(kf("d-sheet", [((0, 1.8), "transform: translateY(120%);"), ((2.3, 3.4), "transform: translateY(0);"), ((3.9, T), "transform: translateY(120%);")]))
blocks.append(kf("d-row", [((0, 3.14), "background: transparent;"), ((3.2, 3.32), "background: #2c2c2e;"), ((3.42, T), "background: transparent;")]))
blocks.append(kf("d-pair", [((0, 3.4), "transform: translateY(100%);"), ((3.9, 6.8), "transform: translateY(0);"), ((7.3, T), "transform: translateY(100%);")]))
blocks.append(kf("d-msg-a", [((0, 6.19), "opacity: 1;"), ((6.2, T), "opacity: 0;")]))
blocks.append(kf("d-msg-b", [((0, 6.19), "opacity: 0;"), ((6.2, T), "opacity: 1;")]))
blocks.append(kf("d-ring", [((0, 4.3), "left: 71px; opacity: 1;"), ((4.31, 4.8), "left: 139px; opacity: 1;"), ((4.81, 5.3), "left: 207px; opacity: 1;"),
  ((5.31, 5.8), "left: 275px; opacity: 1;"), ((5.81, T), "left: 275px; opacity: 0;")]))
for i, t in enumerate((4.3, 4.8, 5.3, 5.8), 1):
    blocks.append(kf(f"d-d{i}", [((0, t), "opacity: 0;"), ((t + .01, 19.8), "opacity: 1;"), ((19.9, T), "opacity: 0;")]))
    blocks.append(kf(f"d-k{i}", [((0, t - .03), "background: #2c2c2e;"), ((t, t + .1), "background: #48484a;"), ((t + .18, T), "background: #2c2c2e;")]))
fs = []
def tap(at, p, appear=.25):
    fs.extend([((at - appear,), F(*p) + " opacity: 0; transform: scale(1);"), ((at - appear + .12,), F(*p) + " opacity: 1;"),
               ((at - .06,), F(*p) + " transform: scale(1);"), ((at,), F(*p) + " transform: scale(.8);"),
               ((at + .1,), F(*p) + " transform: scale(1);")])
def hide(at, p): fs.append(((at,), F(*p) + " opacity: 0; transform: scale(1);"))
def drag(a, b, p, q):
    fs.extend([((a - .12,), F(*p) + " opacity: 0;"), ((a,), F(*p) + " opacity: 1;"), ((b,), F(*q) + " opacity: 1;"), ((b + .12,), F(*q) + " opacity: 0;")])
fs.append(((0,), F(*icon) + " opacity: 0; transform: scale(1);"))
tap(1.2, icon); hide(1.32, icon)
tap(3.2, row); hide(3.42, row)
tap(4.3, k3); fs.append(((4.42,), F(*k3)))
for at, p in ((4.8, k8), (5.3, k1), (5.8, k5)):
    fs.extend([((at - .1,), F(*p) + " transform: scale(1);"), ((at,), F(*p) + " transform: scale(.8);"), ((at + .1,), F(*p) + " transform: scale(1); opacity: 1;")])
hide(6.05, k5)
drag(7.6, 8.5, (110, 385), (300, 385))
drag(9.1, 10.0, (300, 425), (110, 425))
tap(10.6, (201, 405)); hide(10.82, (201, 405))
drag(12.4, 13.1, (201, 18), (201, 300))
tap(14.2, tile, appear=.4); hide(14.42, tile)
drag(15.7, 16.5, (110, 405), (300, 405))
fs.append(((T,), F(300, 405) + " opacity: 0; transform: scale(1);"))
blocks.append(kf("d-finger", fs))
gl = [((0,), G(110, 385) + " opacity: 0;")]
def gdrag(a, b, p, q, o=1):
    gl.extend([((a - .12,), G(*p) + " opacity: 0;"), ((a,), G(*p) + f" opacity: {o};"), ((b,), G(*q) + f" opacity: {o};"), ((b + .2,), G(*q) + " opacity: 0;")])
gdrag(7.6, 8.5, (110, 385), (300, 385)); gdrag(9.1, 10.0, (300, 425), (110, 425)); gdrag(10.5, 10.7, (201, 380), (201, 380), .8)
gdrag(15.7, 16.5, (110, 405), (300, 405)); gl.append(((T,), G(300, 380) + " opacity: 0;"))
blocks.append(kf("d-glow", gl))
blocks.append(kf("d-qs", [((0, 12.4), "transform: translateY(-105%);"), ((13.1, 14.45), "transform: translateY(0);"), ((14.85, T), "transform: translateY(-105%);")]))
blocks.append(kf("d-qs-scrim", [((0, 12.4), "opacity: 0;"), ((13.1, 14.45), "opacity: 1;"), ((14.85, T), "opacity: 0;")]))
blocks.append(kf("d-tile", [((0, 14.17), "background: #e1e3e8;"), ((14.22, 14.4), "background: #c9ccd3;"), ((14.5, T), "background: #e1e3e8;")]))
DARK, WHITE = "color: #1f1f1f;", "color: #fff;"
# White everywhere except over the light Quick Settings panel.
bar = [((0, 12.45), WHITE), ((12.75, 14.6), DARK), ((14.8, T), WHITE)]
blocks.append(kf("d-bar", bar))
print("\n".join(blocks))
