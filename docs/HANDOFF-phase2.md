# Phase 2: on-TV layout editor

Completed from Claude's handoff on 2026-10-18. The user prefers conventional commits.

## Implemented

- Pure layout operations: insert, move, remove, duplicate, rename and replace, plus valid palette templates and descriptor-based fields.
- Editor with outline selection, a scaled preview, automatic scrolling and a pink selection border.
- Node, page and layout forms; nested objects, sealed types, string lists and object lists.
- Property dialogs for text, colors and theme tokens, installed apps, references, enums, numbers, size, padding and backgrounds.
- Settings > Edit layout and a tile context-menu entry that selects the corresponding layout node.
- Undo/redo, validation feedback, save/discard handling and persistence through the existing layout repository and history.
- Invalid documents pause the preview and cannot be saved. File write failures keep the editor open with an error.
- Renaming preserves references inside the changed subtree. Duplicates retarget their internal links while retaining links outside the copy.
- Undo closes forms whose nodes or list items no longer exist. Disabled toolbar controls are skipped by remote focus.

## Validation

`./gradlew :layout-model:test :app:testDebugUnitTest :app:assembleDebug` passes: 55 model tests and 7 editor-state tests.

On the `owntv_gtv` emulator, the editing walkthrough added an app row, changed its tile shape to card, moved it earlier, saved it, and verified both the stored JSON and Home. Settings and context-menu entry points, nested gradient/color forms and numeric controls were also inspected. Undo/redo and discard were checked with remote input. The final debug build is installed; the emulator's original layout was restored after testing.

## Deliberate boundaries

Selection happens through the outline, not through the preview. Maps such as custom theme colors, styles and app overrides still require editing the JSON. The editor does not change the layout format or schema version.

Commit title: `feat(editor): add on-TV layout editing`.
