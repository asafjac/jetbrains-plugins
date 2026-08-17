"""Freezes an animated SVG at one phase, producing a still.

Done by rewriting the XML rather than by a second drawing routine: every <animate> already carries
the value for each phase, so the still is the animated file with those values promoted onto their
parents. One drawing routine means a still can never drift from the animation it came from.

Stills exist because a README cannot offer a click inside an SVG. An <img> never receives pointer
events, and GitHub strips inline <svg> from markdown, so <details> plus a still per state is the
only genuinely clickable form available.
"""
import copy
import pathlib
import xml.etree.ElementTree as ET

SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG)

MODES = [
    ("window", "Set Crop window", "The whole IDE frame. Nothing to configure, nothing to break."),
    ("editor", "Set Crop editor", "Just the editor component, resolved at run time."),
    ("component", 'Set Crop component "EditorTabs"', "Any named IDE part, so it survives a different layout."),
    ("region", "Set Crop region 360,160 1030x600", "Exact pixels, and the one mode that breaks when anything moves."),
    ("fit", "Set Crop fit", "The bounding box of the tape's own targets, plus padding."),
    ("follow-mouse", "Set Crop follow mouse 840x390", "A viewport that tracks the pointer during the take."),
    ("follow-caret", "Set Crop follow caret 950x415", "The same, tracking the caret; steadier than the pointer."),
]


def freeze(tree, phase):
    """Promotes each animation's value for `phase` onto its parent, then drops the animation."""
    root = copy.deepcopy(tree.getroot())
    for parent in root.iter():
        for child in list(parent):
            tag = child.tag.split('}')[-1]
            if tag not in ("animate", "animateTransform"):
                continue
            values = [v.strip() for v in child.get("values", "").split(";") if v.strip()]
            if values:
                # values carries one entry per phase plus a repeat of the first, so a phase index
                # lands on its own value and never on the wrap-around.
                value = values[min(phase, len(values) - 1)]
                if tag == "animate":
                    parent.set(child.get("attributeName"), value)
                else:
                    parent.set("transform", f'{child.get("type", "translate")}({value})')
            # The label sits after the <animate>, which makes it that element's tail rather than
            # the parent's text. Removing the element without carrying the tail across drops every
            # label in the file, and the still renders with empty rows.
            tail = child.tail or ""
            index = list(parent).index(child)
            if index == 0:
                parent.text = (parent.text or "") + tail
            else:
                previous = list(parent)[index - 1]
                previous.tail = (previous.tail or "") + tail
            parent.remove(child)
    return root


docs = pathlib.Path('docs')
made = []
for theme in ("light", "dark"):
    tree = ET.parse(docs / f'framing-{theme}.svg')
    for i, (slug, _, _) in enumerate(MODES):
        out = docs / f'crop-{slug}-{theme}.svg'
        ET.ElementTree(freeze(tree, i)).write(out, encoding='utf-8', xml_declaration=False)
        made.append(out)

total = sum(f.stat().st_size for f in made)
print(f'{len(made)} stills, {total / 1024:.0f} KB total, '
      f'{total / len(made) / 1024:.1f} KB each')
