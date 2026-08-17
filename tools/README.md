# tools

Regenerates the animated demos in `docs/`. Run from the repo root:

```bash
python tools/gen-demo-svg.py      # the two looping demos, light and dark
python tools/gen-demo-stills.py   # one still per crop mode, for the details blocks
```

The demos are generated rather than drawn, so a UI change is an edit here and a rerun, not a
redraw. The stills come from freezing the animated files at each phase, so a still can never
drift from the animation it came from.

Both are stdlib-only.
