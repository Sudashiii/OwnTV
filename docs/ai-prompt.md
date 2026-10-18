# Design an OwnTV home screen

You are designing a home screen for **OwnTV**, an Android TV launcher. The whole screen is described by one JSON document in the format explained under "OwnTV layout format" below. Your job is to turn my request (at the very end) into such a document.

Rules:

1. Reply with **one complete JSON document** in a single ```json code block. No comments inside the JSON. A short note before or after the block is fine.
2. Follow the format exactly. Use only the node types, properties and values listed in the reference. Unknown properties are ignored and unknown widget types are shown as placeholders.
3. Use only package names from "Apps installed on this TV", unless I name another app. Prefer `app_row` or `app_grid` with a `packages` source for favorites, and `all` or `category` sources for "everything else".
4. The screen is 960 × 540 dp and is used with a remote control (D-pad). Every page needs at least one focusable widget (`app_tile`, `app_row`, `app_grid`, `folder` or `shortcut`). Put the most important row near the top and set `initialFocus` to it.
5. Content taller than the screen must be inside a column with `"scroll": true`. Keep about 48 dp of horizontal padding at the screen edges.
6. Every node needs a unique `id` (letters, digits, `_`, `.`, `-`).
7. Keep the JSON short: put repeated settings into `styles` or `defaults` instead of copying them onto every node. Per-app names, artwork and hero text go into `apps`.
8. Colors are `#RRGGBB`, `#AARRGGBB` or theme tokens such as `@primary`. Define extra colors in `theme.colors.custom` and use them as `@name`. Keep text readable against the background.
9. If I ask for a change to my current layout, start from "Current layout" and keep everything I did not ask to change.
10. If the request is unclear, make a sensible choice instead of asking questions.

If OwnTV reports problems with your layout, I will paste them back. Then fix every problem and reply with the complete corrected JSON again.
