# Touchscreen behavior and porting contract

## Keep the start screen quiet

Only source selection and media-URL entry are primary. Do not display an empty timeline, encoding matrix, quality slider or queue before a source is selected. Sources enter one shared Video / Audio / Edit workspace. The shelf opens tools for that same draft; navigating does not apply a preset or clear edits.

## Interaction sizes and layout

The HTML workbench uses 48 x 48 CSS-pixel minimum targets; primary export actions are at least 52 pixels tall. In the native theme, Material controls reserve 52 dp. CSS pixels and Android dp are separate units: these are implementation targets, not a claim about identical physical size on every device.

Keep useful content compact, but do not shrink hit areas to make a screen fit. Use native selects, readable 16-pixel form controls on phones, full-row switch labels, visible pressed/focus feedback, large slider thumbs and wrapping action groups. Do not disable pinch zoom. Respect reduced motion.

The phone shelf is modal, scrolls independently, makes the workspace inert, locks background scrolling, and restores focus to its trigger. A desktop shelf can stay open alongside the workspace. Essential commands do not depend on swipes, hovering or long presses.

The export footer is thumb-reachable and respects bottom safe areas. Reserve its measured height rather than a fixed guessed padding so content can scroll above it. Keyboard viewport handling is progressive enhancement; real Android keyboards, display scaling, gesture navigation and TalkBack still require device qualification.

## Editing without precise dragging

Tap a kept clip to select it. Back/Forward one second moves the source playhead. Set start/end here uses that playhead; +/- changes an endpoint by 0.1 seconds. Exact numeric input and range sliders remain available. Enforce ordered boundaries with at least 0.04 seconds retained. Reorder clips with Move earlier/later; this is a tap alternative, not a drag-only timeline.

A trim slider previews the source time during movement, but commits settings on its native change event. All committed edits pass through the same history/planning path. Undo/redo, switching output modes and adding a queue entry must retain the intended edit state. Queued jobs are independent snapshots.

## Native boundary

This change versions the working desktop reference and increases the native Material interaction minimum. The advanced native editor and URL ingestion are not implemented by changing the theme. Port source-first navigation, the draft/segments model, commands and touch behavior deliberately into Compose and the existing Kotlin planner. Do not introduce a WebView or duplicate native queue state to claim parity.

## Verification

Local revision validation: 89 pytest cases, including 24 Chromium touch checks and 14 direct FFmpeg encode checks; 43 additional browser workflow checks using the explicit loopback test transport. Ordinary browser navigation was blocked by this environment. GitHub CI runs the ordinary network path without that transport. See workflow results for the exact committed revision rather than treating local results as CI results.

The added native instrumentation test checks the layout size of Material actions; compilation and device execution are separate gates. Physical touch ergonomics, TalkBack, soft-keyboard behavior, Android native encoding and thermal/background behavior are not proven by browser tests.

## Design references

- Android touch target guidance: https://developer.android.com/guide/topics/ui/accessibility/apps.html
- Tap alternatives to dragging: https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements

These inform the interaction contract; this is not a blanket accessibility-conformance claim.
