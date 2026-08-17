"""Generates the two demos as animated SVG, one file per theme.

SMIL rather than CSS animation: an SVG inside an <img> is an image document, so its script never
runs, but <animate> does. Two files rather than one with prefers-color-scheme, because that query
does not reliably reach an SVG through <img>; GitHub's <picture> element picks the right one.
"""
import pathlib

LIGHT = dict(
    page="#f5f6f8", panel="#ffffff", chrome="#f0f1f4", line="#d3d5db", soft="#e6e8ec",
    ink="#1e1f22", dim="#6c707e", faint="#9aa0ab", accent="#3574f0", accentSoft="#e3ecfd",
    shade="#1e1f22", tag="#0d7d8c", ok="#2f8a3b", rec="#c9453f",
)
DARK = dict(
    page="#17181b", panel="#1e1f22", chrome="#2b2d30", line="#393b40", soft="#303236",
    ink="#dfe1e5", dim="#9da0a8", faint="#6f737a", accent="#548af7", accentSoft="#24314c",
    shade="#000000", tag="#56b6c2", ok="#5fad65", rec="#e06c6c",
)

MONO = "ui-monospace, 'JetBrains Mono', 'Cascadia Code', 'DejaVu Sans Mono', Consolas, monospace"
SANS = "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"


def esc(text):
    """XML-escapes text content.

    The demo's own code contains angle brackets, so an unescaped line turns the whole SVG into a
    parse error and the image renders as a red box instead of a demo.
    """
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def keytimes(n):
    """Evenly spaced keyTimes for n discrete phases."""
    return ";".join(f"{i / n:.4f}" for i in range(n)) + ";1"


def discrete(values, n, dur):
    """A discrete animation holding each of n values for an equal slice of dur."""
    vals = ";".join(values) + ";" + values[0]
    return (f'calcMode="discrete" values="{vals}" keyTimes="{keytimes(n)}" '
            f'dur="{dur}s" repeatCount="indefinite"')


def smooth(values, n, dur):
    """A continuous animation easing between n values, returning to the first."""
    vals = ";".join(values) + ";" + values[0]
    return (f'values="{vals}" keyTimes="{keytimes(n)}" dur="{dur}s" '
            f'repeatCount="indefinite" calcMode="spline" '
            f'keySplines="{" ".join(["0.4 0 0.2 1"] * n)}"')


# --------------------------------------------------------------------------- framing demo
MODES = [
    ("Crop window",        (18, 18, 540, 380), "Set Crop window"),
    ("Crop editor",        (92, 50, 466, 306), "Set Crop editor"),
    ("Crop component",     (92, 18, 466, 338), 'Set Crop component "EditorTabs"'),
    ("Crop region",        (186, 110, 300, 176), "Set Crop region 360,160 1030x600"),
    ("Crop fit",           (150, 138, 344, 150), "Set Crop fit"),
    ("Crop follow mouse",  (238, 168, 208, 122), "Set Crop follow mouse 840x390"),
    ("Crop follow caret",  (196, 146, 262, 132), "Set Crop follow caret 950x415"),
]
FRAME_DUR = 18.2


def framing_svg(c):
    n = len(MODES)
    W, H = 940, 452
    rows_x, rows_y, row_h = 588, 62, 30

    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
        f'role="img" aria-label="Seven crop modes, each compiling to one line of tape">',
        f'<rect width="{W}" height="{H}" rx="10" fill="{c["page"]}"/>',
    ]

    # ---- IDE schematic
    out += [
        f'<rect x="18" y="18" width="540" height="380" rx="6" fill="{c["chrome"]}" stroke="{c["line"]}"/>',
        f'<rect x="18" y="18" width="74" height="338" fill="{c["panel"]}"/>',
        f'<rect x="92" y="50" width="466" height="306" fill="{c["panel"]}"/>',
        f'<rect x="92" y="356" width="466" height="42" fill="{c["chrome"]}"/>',
        f'<line x1="18" y1="356" x2="558" y2="356" stroke="{c["line"]}"/>',
        f'<line x1="92" y1="18" x2="92" y2="356" stroke="{c["line"]}"/>',
        f'<line x1="92" y1="50" x2="558" y2="50" stroke="{c["line"]}"/>',
        f'<text x="28" y="372" font-family="{MONO}" font-size="9" fill="{c["faint"]}">PROJECT</text>',
        f'<text x="102" y="372" font-family="{MONO}" font-size="9" fill="{c["faint"]}">TOOL WINDOW</text>',
        f'<text x="30" y="40" font-family="{MONO}" font-size="10" fill="{c["dim"]}">demo</text>',
    ]
    # code lines, one highlighted as the tape's target
    for i, w in enumerate((300, 210, 340, 250, 180, 270, 150)):
        y = 74 + i * 26
        fill = c["accent"] if i == 3 else c["soft"]
        op = "0.85" if i == 3 else "1"
        out.append(f'<rect x="116" y="{y}" width="{w}" height="6" rx="3" fill="{fill}" opacity="{op}"/>')

    # ---- shaded outside, four rects that track the crop box
    box_x = [str(m[1][0]) for m in MODES]
    box_y = [str(m[1][1]) for m in MODES]
    box_w = [str(m[1][2]) for m in MODES]
    box_h = [str(m[1][3]) for m in MODES]

    def bx(i):  # right edge
        return [str(m[1][0] + m[1][2]) for m in MODES][i]

    out.append(f'<g fill="{c["shade"]}" opacity="0.5">')
    # top
    out.append(f'<rect x="18" y="18" width="540">'
               f'<animate attributeName="height" {smooth([str(v - 18) for v in [m[1][1] for m in MODES]], n, FRAME_DUR)}/>'
               f'</rect>')
    # bottom
    out.append(f'<rect x="18" width="540" height="398">'
               f'<animate attributeName="y" {smooth([str(m[1][1] + m[1][3]) for m in MODES], n, FRAME_DUR)}/>'
               f'<animate attributeName="height" {smooth([str(398 - (m[1][1] + m[1][3])) for m in MODES], n, FRAME_DUR)}/>'
               f'</rect>')
    # left
    out.append(f'<rect x="18" width="540">'
               f'<animate attributeName="y" {smooth(box_y, n, FRAME_DUR)}/>'
               f'<animate attributeName="height" {smooth(box_h, n, FRAME_DUR)}/>'
               f'<animate attributeName="width" {smooth([str(m[1][0] - 18) for m in MODES], n, FRAME_DUR)}/>'
               f'</rect>')
    # right
    out.append(f'<rect width="540">'
               f'<animate attributeName="x" {smooth([bx(i) for i in range(n)], n, FRAME_DUR)}/>'
               f'<animate attributeName="y" {smooth(box_y, n, FRAME_DUR)}/>'
               f'<animate attributeName="height" {smooth(box_h, n, FRAME_DUR)}/>'
               f'<animate attributeName="width" {smooth([str(558 - (m[1][0] + m[1][2])) for m in MODES], n, FRAME_DUR)}/>'
               f'</rect>')
    out.append('</g>')

    # ---- the crop rectangle itself
    out.append(f'<rect rx="3" fill="none" stroke="{c["accent"]}" stroke-width="2">'
               f'<animate attributeName="x" {smooth(box_x, n, FRAME_DUR)}/>'
               f'<animate attributeName="y" {smooth(box_y, n, FRAME_DUR)}/>'
               f'<animate attributeName="width" {smooth(box_w, n, FRAME_DUR)}/>'
               f'<animate attributeName="height" {smooth(box_h, n, FRAME_DUR)}/>'
               f'</rect>')

    # size tag riding the top-left corner
    out.append(f'<g><animateTransform attributeName="transform" type="translate" '
               f'{smooth([f"{m[1][0]},{m[1][1]}" for m in MODES], n, FRAME_DUR)}/>'
               f'<rect x="0" y="-16" width="86" height="14" rx="3" fill="{c["accent"]}"/>'
               f'<text x="5" y="-5" font-family="{MONO}" font-size="9" fill="#ffffff">'
               + "".join(
                   f'<tspan x="5" opacity="0"><animate attributeName="opacity" '
                   f'{discrete(["1" if j == i else "0" for j in range(n)], n, FRAME_DUR)}/>'
                   f'{["1900x1150", "1630x900", "1630x990", "1030x600", "1180x480", "840x390", "950x415"][i]}</tspan>'
                   for i in range(n))
               + '</text></g>')

    # ---- mode list
    out.append(f'<text x="{rows_x}" y="40" font-family="{MONO}" font-size="10" '
               f'fill="{c["faint"]}" letter-spacing="1.2">CROP MODE</text>')
    out.append(f'<rect x="{rows_x - 8}" width="344" height="{row_h}" rx="4" fill="{c["accentSoft"]}">'
               f'<animate attributeName="y" '
               f'{smooth([str(rows_y + i * row_h - 20) for i in range(n)], n, FRAME_DUR)}/>'
               f'</rect>')
    for i, (name, _, _) in enumerate(MODES):
        y = rows_y + i * row_h
        out.append(f'<circle cx="{rows_x + 4}" cy="{y - 8}" r="5" fill="none" stroke="{c["faint"]}"/>')
        out.append(f'<circle cx="{rows_x + 4}" cy="{y - 8}" r="2.6" fill="{c["accent"]}" opacity="0">'
                   f'<animate attributeName="opacity" '
                   f'{discrete(["1" if j == i else "0" for j in range(n)], n, FRAME_DUR)}/></circle>')
        out.append(f'<text x="{rows_x + 18}" y="{y - 4}" font-family="{MONO}" font-size="13" '
                   f'fill="{c["ink"]}">{esc(name)}</text>')

    # ---- emitted tape
    ty = rows_y + n * row_h + 24
    out.append(f'<rect x="{rows_x - 8}" y="{ty}" width="344" height="86" rx="5" '
               f'fill="{c["panel"]}" stroke="{c["line"]}"/>')
    out.append(f'<rect x="{rows_x - 8}" y="{ty}" width="2" height="86" fill="{c["accent"]}"/>')
    out.append(f'<text x="{rows_x + 6}" y="{ty + 20}" font-family="{MONO}" font-size="10" '
               f'fill="{c["faint"]}">compiles to</text>')
    for i, (_, _, code) in enumerate(MODES):
        out.append(f'<text x="{rows_x + 6}" y="{ty + 42}" font-family="{MONO}" font-size="11.5" '
                   f'fill="{c["tag"]}" opacity="0"><animate attributeName="opacity" '
                   f'{discrete(["1" if j == i else "0" for j in range(n)], n, FRAME_DUR)}/>'
                   f'{esc(code)}</text>')
    out.append(f'<text x="{rows_x + 6}" y="{ty + 62}" font-family="{MONO}" font-size="11.5" '
               f'fill="{c["dim"]}">Set Padding 24</text>')
    out.append(f'<text x="{rows_x + 6}" y="{ty + 78}" font-family="{MONO}" font-size="11.5" '
               f'fill="{c["dim"]}">Output docs/demo.gif</text>')

    out.append('</svg>')
    return "\n".join(out)


# --------------------------------------------------------------------------- shot list demo
STEPS = [
    ("open App.tsx",            "Open file"),
    ('caret → Baz',        "Move caret"),
    ("glide",                   "Mouse glide"),
    ("ctrlclick",               "Ctrl+Click"),
    ("waitfor popup",           "Wait"),
    ('popup pick AcmeBaz',      "Select popup row"),
    ("sleep 2.6s",              "Wait"),
    ("action Back",             "IDE action"),
    ('caret → qux',        "Move caret"),
    ("glide + ctrlclick",       "Ctrl+Click"),
    ("key Escape",              "Key press"),
]
STEP_DUR = 16.5
# where the pointer sits for each step, and whether the popup is up
POINTER = [(470, 96), (352, 132), (352, 132), (352, 132), (352, 132),
           (300, 234), (300, 234), (352, 132), (318, 132), (318, 132), (318, 132)]
POPUP_ON = [0, 0, 0, 1, 1, 1, 0, 0, 0, 1, 0]


def shotlist_svg(c):
    n = len(STEPS)
    W, H = 940, 452
    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
        f'role="img" aria-label="A shot list replaying step by step">',
        f'<rect width="{W}" height="{H}" rx="10" fill="{c["page"]}"/>',
        # editor pane
        f'<rect x="18" y="18" width="904" height="192" rx="6" fill="{c["panel"]}" stroke="{c["line"]}"/>',
        f'<rect x="18" y="18" width="904" height="26" rx="6" fill="{c["chrome"]}"/>',
        f'<text x="32" y="35" font-family="{MONO}" font-size="10" fill="{c["dim"]}">App.tsx</text>',
    ]
    code = [
        ('  return (', c["dim"]),
        ('    <div>', c["dim"]),
        ('      <FooRegistry.Bar label="flat slot" />', c["tag"]),
        ('      <FooRegistry.qux.Baz amount={42} />', c["tag"]),
        ('      <FooRegistry.qux.Corge visible={true} />', c["tag"]),
    ]
    for i, (t, col) in enumerate(code):
        y = 68 + i * 24
        if i == 3:
            out.append(f'<rect x="20" y="{y - 13}" width="900" height="20" fill="{c["accentSoft"]}"/>')
        out.append(f'<text x="34" y="{y}" font-family="{MONO}" font-size="12.5" fill="{col}">{esc(t)}</text>')

    # ---- tool window
    out.append(f'<rect x="18" y="224" width="904" height="210" rx="6" fill="{c["panel"]}" '
               f'stroke="{c["line"]}"/>')
    out.append(f'<rect x="18" y="224" width="904" height="28" rx="6" fill="{c["chrome"]}"/>')
    out.append(f'<text x="32" y="243" font-family="{MONO}" font-size="10" fill="{c["faint"]}" '
               f'letter-spacing="1.2">DEMO DRIVER</text>')
    out.append(f'<circle cx="132" cy="238" r="4" fill="{c["rec"]}"/>')
    out.append(f'<text x="142" y="243" font-family="{SANS}" font-size="11" fill="{c["ink"]}">Record</text>')
    out.append(f'<rect x="186" y="230" width="112" height="17" rx="4" fill="{c["accent"]}"/>')
    out.append(f'<text x="196" y="243" font-family="{SANS}" font-size="11" fill="#ffffff">'
               f'Replay &amp; capture</text>')
    out.append(f'<text x="836" y="243" font-family="{MONO}" font-size="10" fill="{c["dim"]}">'
               f'shots.demo</text>')

    # steps
    top, rh = 266, 13.6
    out.append(f'<rect x="24" width="470" height="{rh}" rx="3" fill="{c["accentSoft"]}">'
               f'<animate attributeName="y" '
               f'{smooth([f"{top + i * rh - 10:.1f}" for i in range(n)], n, STEP_DUR)}/></rect>')
    out.append(f'<rect x="24" width="2" height="{rh}" fill="{c["accent"]}">'
               f'<animate attributeName="y" '
               f'{smooth([f"{top + i * rh - 10:.1f}" for i in range(n)], n, STEP_DUR)}/></rect>')
    for i, (label, _) in enumerate(STEPS):
        y = top + i * rh
        out.append(f'<text x="34" y="{y:.1f}" font-family="{MONO}" font-size="9.5" '
                   f'fill="{c["faint"]}">{i + 1:02d}</text>')
        out.append(f'<text x="56" y="{y:.1f}" font-family="{MONO}" font-size="10.5" '
                   f'fill="{c["ink"]}">{esc(label)}</text>')

    # inspector
    out.append(f'<line x1="512" y1="256" x2="512" y2="428" stroke="{c["soft"]}"/>')
    out.append(f'<text x="528" y="272" font-family="{MONO}" font-size="9.5" fill="{c["faint"]}" '
               f'letter-spacing="1.2">SELECTED STEP</text>')
    for row, label in enumerate(("Command", "Target", "Anchor", "Duration")):
        y = 296 + row * 24
        out.append(f'<text x="528" y="{y}" font-family="{SANS}" font-size="11" '
                   f'fill="{c["dim"]}">{label}</text>')
        out.append(f'<rect x="606" y="{y - 12}" width="300" height="17" rx="3" '
                   f'fill="{c["chrome"]}" stroke="{c["soft"]}"/>')
    fields = [
        ("Ctrl+Click", "App.tsx:39", '"Baz"', "instant"),
        ("Move caret", "App.tsx:39", '"Baz"', "instant"),
        ("Mouse glide", "App.tsx:39", '"Baz"', "800ms"),
        ("Ctrl+Click", "App.tsx:39", '"Baz"', "instant"),
        ("WaitFor", "popup", "-", "max 5s"),
        ("Select row", "AcmeBaz.ts", '"AcmeBaz"', "instant"),
        ("Sleep", "-", "-", "2600ms"),
        ("Action", "Back", "-", "instant"),
        ("Move caret", "App.tsx:39", '"qux"', "instant"),
        ("Ctrl+Click", "App.tsx:39", '"qux"', "700ms"),
        ("Key press", "Escape", "-", "instant"),
    ]
    for col in range(4):
        y = 296 + col * 24
        out.append(f'<text x="614" y="{y}" font-family="{MONO}" font-size="10.5" fill="{c["ink"]}">'
                   + "".join(
                       f'<tspan x="614" opacity="0"><animate attributeName="opacity" '
                       f'{discrete(["1" if j == i else "0" for j in range(n)], n, STEP_DUR)}/>'
                       f'{esc(fields[i][col])}</tspan>' for i in range(n))
                   + '</text>')
    out.append(f'<text x="528" y="404" font-family="{MONO}" font-size="10" fill="{c["ok"]}">'
               f'✓ resolves to x 423, y 630</text>')

    # status bar
    out.append(f'<text x="32" y="426" font-family="{MONO}" font-size="10" fill="{c["dim"]}">'
               + "".join(
                   f'<tspan x="32" opacity="0"><animate attributeName="opacity" '
                   f'{discrete(["1" if j == i else "0" for j in range(n)], n, STEP_DUR)}/>'
                   f'● recording — step {i + 1}/{n}: {STEPS[i][1]}</tspan>'
                   for i in range(n))
               + '</text>')
    out.append(f'<rect x="300" y="420" width="180" height="3" rx="1.5" fill="{c["soft"]}"/>')
    out.append(f'<rect x="300" y="420" height="3" rx="1.5" fill="{c["accent"]}">'
               f'<animate attributeName="width" '
               f'{smooth([f"{180 * (i + 1) / n:.1f}" for i in range(n)], n, STEP_DUR)}/></rect>')

    out.append('</svg>')
    return "\n".join(out)


docs = pathlib.Path('docs')
docs.mkdir(exist_ok=True)
for name, theme in (("light", LIGHT), ("dark", DARK)):
    (docs / f'framing-{name}.svg').write_text(framing_svg(theme), encoding='utf-8')
    (docs / f'demo-driver-{name}.svg').write_text(shotlist_svg(theme), encoding='utf-8')

for f in sorted(docs.glob('*.svg')):
    print(f.name, round(f.stat().st_size / 1024, 1), 'KB')
