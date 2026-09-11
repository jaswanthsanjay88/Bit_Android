# Authoring argent flows for goldie

Flows are argent YAML in the app repo's `.argent/flows`, replayed with
`argent flow run` on a booted simulator. goldie refers to them by name, so
`flow: "store-01-issues"` runs `.argent/flows/store-01-issues.yaml` and you can
run that same flow by hand from the app repo while authoring it.

Flows run with no LLM, so every step must be deterministic. goldie handles the
surrounding machinery for you: before any flow it pins the status bar and
reinstalls the app with cleared data, so every run starts from the same empty
state. A simulator that is already booted keeps running; it is only shut down
and rebooted when its keyboard and locale preferences still need pinning.

## Step vocabulary

**Rule: `executionPrerequisite` and a leading `launch:` step are mutually
exclusive.** A flow that starts with `launch:` launches and controls its own
starting state, so `flow-execute` rejects any `executionPrerequisite` on it.
Only give a flow `executionPrerequisite` when its first step is NOT `launch:`
— i.e. it assumes a state a prior flow/segment left behind (preview segments
after the first one are the normal case). Every screenshot scene flow and a
preview's first segment starts with `launch:` and therefore must NOT have
`executionPrerequisite`.

```yaml
executionPrerequisite: >-        # top-level, preview segments mostly: the state
  App on the composer with a title typed.   # this flow assumes when it starts

steps:
  - echo: Why the next step looks the way it does   # comment, printed on replay
  - launch: com.example.app                          # launch by bundle id
  - tap:
      text: New issue              # visible text selector (preferred)
  - tap:
      id: submit-button            # accessibility id selector (preferred)
  - tap:
      x: 0.413                     # normalized coordinates, last resort;
      y: 0.941                     # always pair with an echo saying what it hits
  - await:
      visible:
        text: Issue title          # wait until this text appears
  - await:
      hidden:
        text: Cancel               # wait until this text disappears
  - await:
      idle: true                   # wait for animations/layout to settle
  - wait: 600                      # fixed pause in ms, pacing only
  - tool: keyboard
    args:
      text: Sync conflicts when editing offline      # type into focused field
  - tool: gesture-swipe
    args: { fromX: 0.5, fromY: 0.72, toX: 0.5, toY: 0.42, durationMs: 700 }
```

## Conventions that keep flows replayable

- **Prefer `text:` and `id:` selectors.** Coordinates drift between device
  sizes and break silently when layout shifts. When only a coordinate works
  (icon-only tab bars, unlabeled rows), put an `echo:` right above it saying
  what it points at and where the value came from, so a future repair knows
  what to re-resolve.
- **Never hardcode a simulator udid.** `argent flow run --device` injects it.
- **`await visible` before acting on anything that appears.** Then
  `await idle` before the capture moment, so animations finish.
- **The app starts with cleared data.** If a scene needs content on screen
  (a populated list, a created item), the flow must create it, or the app must
  seed demo data on first launch. Check how the app behaves on a fresh install
  before assuming content exists.
- **Mine the flows already in `.argent/flows`.** Selectors and coordinates that
  already replay there are proven; copy them instead of rediscovering, or point
  a scene straight at an existing flow when one reaches the screen.
- **Prefix marketing flows** (`store-…`) so they stay distinct from the app's
  test flows in the same directory.

## Screenshot flows

A screenshot flow only navigates: launch, reach the screen, settle. goldie
takes the full-resolution screenshot itself after the last step, so the flow
contains no screenshot call. End every screenshot flow with `await idle`.

```yaml
steps:
  - launch: com.example.app
  - await: { visible: { text: All issues } }
  - await: { idle: true }
```

## Preview segment flows

Segments record one continuous session: goldie restarts the app once before
the first segment, then replays the segments in order, wrapping each in its
own recording. So the first segment starts from a fresh launch already settled
on the home screen, and every later segment continues exactly where the
previous one ended. State the assumption in `executionPrerequisite` so the
chain is auditable.

Pacing rules:

- A segment's clip lasts as long as the flow takes, plus its `holdSeconds`.
  Use `wait:` steps to let a screen read before and after an action; 800 to
  1500 ms reads naturally.
- The whole video must total 15 to 30 seconds. `goldie preview` refuses to
  render outside that window. With 3 or 4 segments, aim for 4 to 7 seconds
  each.
- Recordings run in real time with touch indicators off; a swipe with
  `durationMs` around 700 looks human.

## Repairing a broken flow

When capture fails, goldie prints the failed step and argent's reason, for
example `no element matched selector text="All issues"`. Repair loop:

1. Get the app to the state the failed step assumed (replay the earlier steps
   by hand over argent MCP, or use `flow run` up to that point).
2. `describe` the live screen and find what the target is actually called now.
3. Correct the YAML, keeping the echo comments truthful.
4. Re-run `capture` and confirm the flow passes.

Commit corrected flows with the app's other flows in `.argent/flows`; they are
the durable record of how to reach each marketed screen.
