# Changelog

Per-plugin, newest first. A release tag collects whatever versions the plugins are at; each plugin
moves at its own pace, so its version is what to read here rather than the tag.

## Demo Driver

### 0.4.0

Recording got the details wrong in ways that made replay fail rather than merely look off.

- Record the file every step happens in. A `Caret` applies to whatever the replay has open, and the
  file change caused by navigating was being swallowed, so a recording that ended in a different
  file than it started aimed every later step at the wrong document and did not replay at all.
- Ctrl and click is recorded as ctrl and click. The editor's own mouse listener reports modifier
  state after it has moved on, so the modifier was lost and the step came out as a plain click;
  modifiers are now read from the raw AWT press, the only moment they are reliable.
- Prefer the action over the gesture that invoked it. Ctrl and click on a symbol used to produce a
  glide, a ctrl-click and an action, so a replay navigated twice; the action now replaces the
  gesture, which also means the tape replays on a keymap that binds it differently.
- Edit steps in the tool window. Duration, line, anchor, label, action id and key are editable, with
  move, duplicate and delete. Every edit is written to the tape as source, so the file stays the
  single source of truth.
- Keystrokes worth replaying are recorded; typing is not, which is a different feature.

### 0.3.0

- Record popup picks. There is no IDE event for taking a row from a Choose Declaration popup, so a
  global input listener reads the row's rendered label on click and on Enter. Rows are read through
  the cell renderer, since the values are PSI whose `toString` is a debug description, and the label
  is trimmed to its leading name so a tape survives a file rename.
- The row reader is shared with the runner. A recorder that wrote labels the runner could not match
  would produce tapes that always failed at the popup step.
- Ignore the `Open` and `Caret` events a navigation causes, briefly. Recording them made a replay
  click at the destination it had just been sent to.

### 0.2.0

- Record mode: press Record, use the IDE, press Stop, and get a tape. Carets are written as the text
  under them rather than as offsets, so a recording survives later edits to the file.
- A tool window with the tape list, parsed steps and the framing controls.
- ffmpeg is found or fetched rather than assumed on PATH. The download asks first and shows the URL,
  since it is an executable from the internet, and the binary is run once before being accepted.
  Resolution happens before the take: discovering there is no encoder afterwards wastes the
  performance.

### 0.1.0

- Replay a tape and capture it. Targets are named as code and resolved through `editor.offsetToXY`,
  so a tape survives font, theme, zoom and window changes; `java.awt.Robot` moves the real pointer
  along an eased path, so takes look human.
- Seven framing modes: `window`, `editor`, `component`, `region`, `fit`, `follow mouse`,
  `follow caret`, plus padding, cursor, redaction and snapping.
- ffmpeg does the capture and is asked to quit rather than killed, since a killed encoder leaves an
  mp4 with no moov atom.

## Registry Navigator

### 0.8.0

- Each segment of a registry path is its own target: the registry name answers with the registry
  classes, a slot with the slot getters, and a nested key with the components.
- Popup rows are labelled with the registry that supplies them, which is the only thing telling
  near-identical component names apart.
- Slots that return an object literal contribute their getter, which is what makes a middle segment
  answerable at all.

### 0.6.0

- Resolve registry slots written as JSX tags. Every real usage site is a JSX tag, whose name is one
  XML token with no expression in it, so the resolver had been bailing at the first step and doing
  nothing at all.
- Nested slots resolve through the object literal a getter returns, including keys overridden via a
  spread.

### 0.5.0

- Find overriding implementations by superclass name rather than by PSI inheritance. An override
  imports its base from a package, so the `extends` clause can resolve to a bundled declaration
  while the consumer's type points at the source class, and a search across the two finds nothing.
- A Diagnose action reporting what the resolver saw, because a silent miss is indistinguishable from
  the plugin not being loaded.
