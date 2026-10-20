# OwnTV layout engine

How a layout JSON becomes a home screen. For the format itself (every node and property) see [layout-spec.md](layout-spec.md); this file describes the machinery behind it.

## Pipeline

```
text ──▶ LayoutCodec.decode ──▶ LayoutDocument ──▶ StyleResolver ──▶ LayoutView ──▶ Compose UI
         extract JSON            (stored as is)     defaults+styles    RenderNode
         migrate                                    applied            per node
         StructureValidator
         decode
         LayoutValidator
```

1. **Extract**: text around a ```` ```json ```` fence (AI chat answers) is dropped; comments and trailing commas are allowed; max 1 MB.
2. **Migrate** (`codec/Migrations.kt`): `schemaVersion` below `CURRENT_SCHEMA_VERSION` (3) is lifted one step at a time. A newer version is an error. Old package lists and `hidden` flags are handed over as `DecodeResult.extractedLists`.
3. **Structure check** (`StructureValidator`): walks the raw JSON against the Kotlin serial descriptors and reports every missing field, wrong type, unknown enum value, out-of-range number (`@Range`) and bad color (`@ColorValue`) in one pass, each with a JSON path such as `$.pages[0].root.children[2]`.
4. **Decode** into `LayoutDocument`. Unknown node types become `UnknownNode` (shown as a placeholder); unknown properties are ignored.
5. **Meaning check** (`LayoutValidator`): style references and cycles, unique ids, id/page/focus-link targets, color tokens, image sources (`https://`, `http://`, `asset:`), actions, nesting depth (24), placeholders, clock patterns, focusable widgets per page, and a visible `open_launcher_settings` link (error: without it the TV could get stuck).
6. **Result**: `DecodeResult(document, issues, extractedLists)`. `decode` never throws. The document is `null` when any issue is an error; warnings and infos do not block. `issues.toAiFeedback()` produces text to paste back into an AI chat.

`LayoutCodec.encode` writes the document back (pretty-printed, defaults omitted).

## Model (`layout-model`, pure Kotlin)

- `LayoutDocument` holds `theme`, `background`, `styles`, `defaults`, `apps` (per-app overrides), `pageTabs` and `pages`. A `Page` has an `id`, `initialFocus`, optional `background` and one `root` node.
- Nodes are registered in `NodeRegistry`: containers (`column`, `row`, `grid`, `stack`), app nodes (`app_tile`, `app_row`, `app_grid`, `folder`, `shortcut`) and content (`hero`, `text`, `clock`, `image`, `spacer`). In JSON a node is flat; in Kotlin the shared properties (size, padding, background, border, focus links, ...) live in `node.common` and `NodeSerializer` splits and joins them.
- Typed values (`Background`, `Size`, `Padding`, `AppSource`, `Action`) are sealed types with custom serializers that accept shorthands such as `"#101010"`.
- Everything is annotated with `@Doc`, `@Range` and `@ColorValue`. The same descriptors drive the validator, the generated JSON Schema (`schema/owntv-layout.v3.schema.json`, `JsonSchemaGenerator`), the AI prompt and the on-TV editor forms (`edit/Fields.kt`).
- `edit/LayoutOps` are pure tree edits (return a new document or `null`); the editor never mutates state, so undo/redo work.

## Styles and defaults

`StyleResolver.resolve(doc)` runs before validation and rendering. Precedence, lowest first: `defaults[nodeType]`, then the chain of the node's `style` (a style may name another `style`, up to 8 deep), then the node's own properties. Nested objects such as `tile` merge key by key; `source`, `action` and `background` are replaced whole. Keys a node type does not have are skipped, so one style can serve several types. The stored document keeps the style references; only the rendered copy is expanded.

## Apps and lists

Apps are not part of the layout. App nodes name a source: `list` (a named list kept on the TV), `all`, `category`, `recent`. The TV stores `AppLists` (named lists, custom order of all apps, hidden apps) in `files/app-lists.json`, so layouts and looks can be swapped without losing them. `AppCatalog` (in the renderer) combines installed apps, the layout's `apps` overrides, launch history and the lists, and resolves each source to a list of apps; hidden apps are left out everywhere except the app drawer.

## Rendering (`layout-render`, Compose Multiplatform)

- `LayoutView` resolves styles, builds `OwnTheme` from `theme` (colors, tokens like `@primary`, fonts, focus style), picks the page, draws the background and optional page tabs, then `RenderNode(page.root)`.
- `RenderNode` handles what every node shares (size, padding, border, shadow, focus links, glass, image/gradient background) and then dispatches on the node class (`NodeContent`). Containers lay children out; tiles, heroes and widgets live in `Tiles.kt` and `Widgets.kt`.
- **Focus**: ids that are referenced (focus links, `initialFocus`, hero `target`) get a `FocusRequester` in a per-page `FocusRegistry`; `FocusTracker` follows what is focused for heroes. Without links, Compose's D-pad search picks the nearest widget. Long press on a tile opens its menu; `AppArranger` provides move mode.
- **Glass** (`Glass.kt`, Haze) only blurs content marked with `glassSource` (page background, heroes, images).
- **Platform seams** (`Platform.kt`): `AppInfo`, `AppImages`, `InteractiveSurfaces` (focusable tiles/tabs; the TV uses tv-material), `FontFamilies`, `assetUrl`, `RenderMetrics`. `android.*`, `androidx.tv.*` and `java.*` stay out of `commonMain` so the web build keeps working.

## Images

Anywhere an image is accepted (`background: image`, hero slides, `image` nodes, `shortcut.image`, `apps` artwork, `shortcut.backdrop`) the source is `https://…` or `asset:<path>` (a file in the APK's assets, `assetUrl`). Both go through Coil (`imageModel`).

On the TV `LayoutImageLoader` caches linked images on disk (`cache/layout-images`, 256 MB, 30 days, server cache headers ignored), so each image is downloaded once and then shows instantly and offline. The URL is the cache key: a layout changes the link to replace an image. The web preview relies on the browser's own cache. App banners and icons come from the package manager (`AppImageLoader`), not from the network.

## On the TV (`app`)

- `LayoutRepository` keeps the active layout in `files/layouts/current.json`. `apply` writes atomically and pushes the previous layout into `files/layouts/history/` (pruned); history entries can be restored.
- Layouts arrive through the phone paste server, a URL, a file, a template (`examples/*.json`), a look (`looks/*.json`) or the editor. All of them go through `LayoutCodec.decode`; only a document without errors is applied.
- `ActionExecutor` runs actions (`launch_app`, `open_url`, `intent`, `go_to_page`, `open_app_drawer`, `open_system_settings`, `open_launcher_settings`).
- "Copy AI prompt" builds the prompt with `AiPromptBuilder` from `docs/ai-prompt.md`, `docs/layout-spec.md`, the installed apps, the app lists and the current layout.

## Web preview (`web`)

The same renderer compiled to Wasm, next to a Monaco editor wired to the JSON Schema. It decodes and renders through `window.owntv` (`render`, `setPage`, `setMetrics`) and reports issues and metrics as `owntv-result` events. Apps are made-up demos with drawn banners.

## Extending

See "Adding or changing a layout property or node type" and "Editor rules" in `CLAUDE.md`: model (`@Doc`/`@Range`/`@ColorValue`, `NodeRegistry`) → render in `RenderNode.kt` → `./gradlew :layout-model:generateSchema` and update `layout-spec.md` → bump `CURRENT_SCHEMA_VERSION` and add a migration for breaking changes → keep `examples/` and `looks/` valid → keep the editor working. `./gradlew :layout-model:test :app:testDebugUnitTest :app:assembleDebug` checks it.
