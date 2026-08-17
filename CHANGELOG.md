# Changelog

Per-plugin, newest first. A release tag collects whatever versions the plugins are at; each plugin
moves at its own pace, so its version is what to read here rather than the tag.

## Demo Driver

### 0.5.0

An edge-case pass over recording and replay. Each of these made a tape replay differently from the
take it came from.

- Anchors carry their occurrence. `caret()` took the first match of the anchor on the line, so
  clicking the second `Baz` in `FooRegistry.qux.Baz` replayed as the first; a recorded step now says
  `nth 2` where it means it.
- Popup labels match exactly first, then on the leading name, then as a prefix, and only then as a
  substring. Plain `contains` meant the label `Bar` also matched a row reading `AcmeBar`, so a tape
  asking for the base implementation silently took an override.
- The line in a `Caret` is a hint, not a requirement: the line is searched first and the whole file
  second, so inserting a line above a target no longer breaks the tape. The old behaviour contradicted
  the comment claiming anchors survive edits.
- Files outside the project root resolve. An absolute path was being looked up as
  `$projectRoot/$absolutePath`, so stepping into a library during a recording produced an `Open` that
  could never work.
- The popup to act on is chosen focused-window-first. Any showing list anywhere was taken, so a
  notification balloon or a stale popup could be picked over the one the step meant.
- Scrolling is recorded and replayed, coalesced so a wheel flick is one `Scroll` step rather than
  thirty. It had been filtered out as noise, so a demo that scrolled to reveal code replayed without
  the reveal.
- Selections are recorded as `Select`, multi-line drags included. A range names both of its ends
  (`Select 39 "Baz" to 42 "Corge"`) so it survives edits the way every other target does, and falls
  back to `Select lines 39 42` when an end does not sit on an identifier and there is nothing to
  anchor to. A drag fires continuously, so the growing selection replaces itself rather than
  recording each intermediate state.
- Waits are adaptive. `Popup` waits for the popup to exist, `Open` waits for the editor to be laid
  out, and a new `WaitFor popup|editor` step makes it explicit. A fixed sleep has to be long enough
  for the slowest machine and still loses to an indexing pass.
- Long pauses survive. The cap was four seconds, which silently shortened any deliberate beat; it is
  twenty now, high enough to be generous and low enough that walking away does not leave dead air.
- The post-navigation suppression counts rather than times out. A blanket window dropped anything
  genuinely done in the moment after navigating; a navigation moves the caret once, so exactly one
  event is now allowed for.
- `Set Tooltips off`, with a checkbox in the panel, stops the replay's own pointer motion raising
  quick documentation that was never in the recording. The previous setting is restored afterwards,
  including when the take fails.
- Actions run against the editor's data context and are checked for being enabled first. An action
  recorded while a popup had focus resolved against whatever held focus at replay time and silently
  did nothing, leaving the take looking fine and missing the step that mattered.
- The step editor can change the command itself, not only its arguments, and add new steps. A
  conversion drops fields the new command does not use rather than carrying invisible state the tape
  cannot express.

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
