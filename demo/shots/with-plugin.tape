# The Registry Navigator demo, as a tape.
#
# Targets are text, not pixels: "the anchor Baz on line 39". The plugin asks the editor where
# that lands, so this file keeps working after a font, theme, zoom or window change - which is
# the whole reason it exists.

Output docs/with-plugin.gif
Output docs/with-plugin.mp4
Set Framerate 10
Set Width 940
Set Crop window

Open demo/src/App.tsx
Sleep 1s

# The payoff: one click on a nested slot lists every implementation at once.
Caret 39 "Baz"
Glide 800ms
CtrlClick
Sleep 2s

# Pick the override by name. Never by row number - that breaks the moment a
# new implementation is added and the row order shifts.
Popup "AcmeBaz"
Sleep 2600ms

Action Back
Sleep 1600ms

# The middle segment answers a different question: the slot getters.
Caret 39 "qux"
Glide 700ms
CtrlClick
Sleep 2200ms
Key Escape
Sleep 900ms

# And the leading segment: the registry classes themselves.
Caret 39 "FooRegistry"
Glide 600ms
CtrlClick
Sleep 2400ms
Key Escape
