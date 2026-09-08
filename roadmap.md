# DHD Virtual Display Density Comparison Roadmap

Status: deferred. The current density implementation is preserved in commit
`a289785` (`Add per-display app density override`).

## Goal

Compare the current app-visible **320 dpi** setup with the previous
app-visible **420 dpi** setup while keeping the virtual display at the same
`720x1560` pixel buffer. This isolates density and layout from resolution.

| Variant | Display buffer | Base density | App-visible density | Approximate app viewport |
| --- | --- | ---: | ---: | ---: |
| Current | `720x1560` | 420 dpi | 320 dpi | `360x780 dp` |
| Previous | `720x1560` | 420 dpi | 420 dpi | `274x594 dp` |

The screenshots remain `720x1560` in both variants. Changing density alone
does not reduce screenshot-token cost; it changes the app's dp layout and
therefore text wrapping, breakpoints, and target sizes.

## Phase 1: controlled comparison

1. Keep the same DHD build, phone, app package, task prompts, model, reasoning
   level, and starting app state.
2. Create a fresh task display for every run. Change only
   `appDensityDpi`: `320` for the current variant and `420` for the previous
   variant. Keep the base display density at `420`.
3. Alternate the variant order or randomize it so one density is not always
   tested first. Start with four representative tasks and one run per task per
   variant to limit model usage. Repeat only when the first results are close.
4. Verify each run with `wm size`, `wm density -d <displayId>`, display dumps,
   and the returned screenshot dimensions before judging the result.

Useful task coverage:

- small visible targets and dense grids;
- scrolling to a target;
- text entry or keyboard interaction;
- a real app flow with changing screens.

## Metrics to record

- first-action and total task success;
- first-tap success, wrong taps, and retries;
- task duration and model-turn count;
- explicit `dhd_observe` calls;
- `dhd_open_app`, `dhd_execute`, and `dhd_execute_sequence` calls;
- screenshot dimensions/payload sizes;
- stale-observation and `POST_OBSERVATION_FAILED` errors.

## Observation-call audit

Count standalone `dhd_observe` calls separately from the fresh observations
returned inside successful `dhd_open_app`, `dhd_execute`, and
`dhd_execute_sequence` responses.

For a stable task, the expected path is to reuse the observation returned by
each successful action. A standalone `dhd_observe` is justified when there is
no usable observation, an observation-related failure occurred, or the screen
changed independently. A call immediately after a successful open or action
should be marked as redundant rather than attributed to density.

Track an extra-observe rate such as:

```text
standalone dhd_observe calls / (dhd_open_app calls + dhd_execute calls)
```

Do not relax stale-observation or post-action verification rules to lower this
number. If 320 dpi produces more redundant observes, inspect model/tool reuse
first; if it produces more observes only after genuine failures or dynamic
screen changes, keep those calls in the reliability measurement.

## Decision rule

Keep 320 dpi if coordinate and task success are comparable to 420 dpi while
the layout is more usable. Prefer 420 dpi if it produces a material accuracy
or reliability improvement. If accuracy is tied, reduce cost by removing
redundant observations or reducing image payloads; density by itself does not
change the screenshot pixel count.

Before running the comparison, add lightweight per-run trace output if the
existing companion logs do not expose the tool-call sequence and observation
IDs clearly.
