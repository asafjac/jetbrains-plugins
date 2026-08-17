# The Registry Navigator demo, as a tape.
#
# Targets are text, not pixels: "the anchor Baz on line 39". The plugin asks the editor where that
# lands, so this keeps working after a font, theme, zoom or window change. The line is a hint, so it
# also survives lines being inserted above the target.

Output docs/with-plugin.gif
Output docs/with-plugin.mp4

# fit reads the Caret targets below and frames their bounding box, so the shot contains the demo and
# nothing else. Swap for: window | editor | component "Project"
#   | region 360,160 1030x600 | follow caret 950x415 ease 350ms
Set Crop fit
Set Padding 24
Set Cursor on
# The pointer travels across code, which would otherwise raise quick documentation over the thing
# being demonstrated.
Set Tooltips off
Set Framerate 10
Set Width 940

Open demo/src/App.tsx
WaitFor editor 5s
Sleep 1s

# The payoff: one click on a nested slot lists every implementation at once.
Caret 39 "Baz"
Glide 800ms
CtrlClick
WaitFor popup 5s
Sleep 1.5s

# Pick the override by name. Matched exactly before loosely, so this cannot take AcmeBaz when it
# asked for Baz, or the reverse.
Popup "AcmeBaz"
Sleep 2.6s

Action Back
Sleep 1.6s

# The middle segment answers a different question: the slot getters.
Caret 39 "qux"
Glide 700ms
CtrlClick
WaitFor popup 5s
Sleep 1.8s
Key Escape
Sleep 0.9s

# And the leading segment: the registry classes themselves.
Caret 39 "FooRegistry"
Glide 600ms
CtrlClick
WaitFor popup 5s
Sleep 2s
Key Escape
