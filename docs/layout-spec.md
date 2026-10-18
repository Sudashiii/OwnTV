# OwnTV layout format (version 1)

An OwnTV layout is one JSON document. The launcher renders it natively; nothing in it is code. The machine-readable definition is `schema/owntv-layout.v1.schema.json` (JSON Schema 2020-12), generated from the app's Kotlin model.

## Screen and units

- The screen is **960 × 540 dp** (a 1080p TV). All sizes, paddings and gaps are in dp. Text sizes come from presets.
- People use a remote: Up/Down/Left/Right move focus, OK clicks, a long press on OK opens a menu (Open, App info, Launcher settings), Back goes back.
- Only focusable widgets react to the remote: `app_tile`, `app_row`, `app_grid`, `shortcut`. Focus moves to the nearest focusable widget in the pressed direction. A focused tile grows and gets a border and glow, as set in `theme.focus`.

## Document

```json
{
  "schemaVersion": 1,
  "name": "Living room",
  "theme": { "colors": { "primary": "#7AA2F7" } },
  "background": { "type": "gradient", "colors": ["#0E0F13", "#16203A"], "angle": 160 },
  "pageTabs": false,
  "pages": [ { "id": "home", "initialFocus": "favorites", "root": { "type": "column", "id": "root", "children": [] } } ]
}
```

| Property | Required | Meaning |
|---|---|---|
| `schemaVersion` | yes | Always `1`. |
| `name` | | Shown in settings and history. |
| `theme` | | See Theme. |
| `background` | | Background of all pages. Default: theme background color. |
| `pageTabs` | | `true` shows page titles as tabs at the top (only with 2+ pages). Default `false`. |
| `pages` | yes | List of pages. The first page is the home page; Back returns to it. |

**Page**: `id` (required, unique), `title` (tab label), `initialFocus` (id of the node focused when the page opens; default: first focusable widget), `background` (overrides the layout background), `root` (required node; it fills the screen).

## Theme

```json
"theme": {
  "colors": {
    "background": "#0E0F13", "surface": "#1F2229", "primary": "#7AA2F7",
    "text": "#ECEDEF", "textMuted": "#9AA0AA", "focus": "#FFFFFF",
    "custom": { "accent2": "#FF8800" }
  },
  "cornerRadius": 12,
  "fontScale": 1.0,
  "focus": { "scale": 1.08, "borderWidth": 3, "borderColor": "@focus", "glow": true }
}
```

- Token colors: `@background`, `@surface` (tiles), `@primary`, `@text`, `@textMuted`, `@focus`, plus every key in `custom` (`@accent2`). Theme colors themselves must be hex values.
- `cornerRadius` 0–64 (tiles), `fontScale` 0.5–2, `focus.scale` 1.0–1.5, `focus.borderWidth` 0–12.

## Backgrounds

- `{ "type": "color", "color": "#101010" }`
- `{ "type": "gradient", "colors": ["#000000", "#203040"], "angle": 180 }` — 2+ colors; angle like CSS: 0 = bottom→top, 90 = left→right, 180 = top→bottom (default).
- `{ "type": "image", "src": "https://…/bg.jpg", "dim": 0.3, "fit": "cover" }` — `dim` 0–1 darkens the image; `fit` is `cover`, `contain` or `fill`.

## Common node properties

Every node has `type` and `id` (both required) and may use:

| Property | Values |
|---|---|
| `width`, `height` | `"fill"`, `"wrap"` or a number (dp). Default depends on the widget. |
| `weight` | Share of the remaining space in a row or column, like flex-grow. `{ "type": "spacer", "id": "gap", "weight": 1 }` pushes neighbours apart. |
| `padding` | `16`, `[horizontal, vertical]` or `[start, top, end, bottom]`. |
| `background` | Color behind the node. |
| `cornerRadius` | Radius of that background, 0–200. |
| `align` | `top_start`, `top`, `top_end`, `start`, `center`, `end`, `bottom_start`, `bottom`, `bottom_end`. In a stack all apply; in a column only the horizontal part; in a row only the vertical part. |
| `span` | Inside a `grid`: how many columns the node covers (default 1). |
| `visible` | `false` hides the node. |

## Containers

- **`column`**: children top to bottom. `children`, `spacing` (gap, default 0), `contentAlign` (`start` / `center` / `end`, horizontal), `arrange` (`start`, `center`, `end`, `space_between`, `space_around`, `space_evenly`, vertical), `scroll` (`true` scrolls vertically; needed when content is taller than the screen).
- **`row`**: children left to right. `children`, `spacing`, `contentAlign` (`top` / `center` / `bottom`, default `center`), `arrange` (horizontal), `scroll` (horizontal).
- **`grid`**: `columns` (required, 1–12), `children` (filled row by row), `spacing` (default 16). Children stretch to their cell width; tiles keep 16:9 unless they set `height`.
- **`stack`**: layers children; later ones on top. `contentAlign` (9 positions, default `top_start`) and each child's `align`.

The page `root` fills the screen. Other containers wrap their content unless you set `width`/`height` (`"fill"` is common for rows that hold a spacer).

## App widgets (focusable)

**Tile style** (`tile` property): `{ "style": "banner" | "icon" | "card", "size": "small" | "medium" | "large", "showLabel": "always" | "focused" | "never" }`

- `banner`: the app's 16:9 TV banner; widths 128 / 160 / 208 dp. Label shown on focus by default.
- `icon`: square app icon (56 / 72 / 96 dp) with the label below.
- `card`: banner with the label always below.

**App sources** (`source` property):

- `{ "type": "packages", "packages": ["com.netflix.ninja", "com.google.android.youtube.tv"] }` — exactly these apps, in this order.
- `{ "type": "all", "sort": "alphabetical" | "recently_installed", "exclude": ["…"], "includeNonTvApps": false }` — every TV app.
- `{ "type": "category", "category": "games" | "apps", "sort": "alphabetical", "exclude": [] }` — games, or everything that is not a game.

Widgets:

- **`app_row`**: horizontally scrolling tiles. `source` (required), `title`, `tile`, `spacing` (16), `limit`, `missing` (`hide` default, or `placeholder` for packages that are not installed). Width fills by default. A row with no apps is hidden.
- **`app_grid`**: tiles in `columns` (default 5) that wrap onto new rows. Same properties as `app_row`. Put it in a scrolling column if it may be taller than the screen.
- **`app_tile`**: one app. `package` (required), `label` (override), `tile`, `missing` (`placeholder` default, or `hide`).
- **`shortcut`**: a custom tile. `label` and `action` (both required), `icon`, `image` (URL or `asset:`), `color` (tile color), `tile` (default `card`). Icons: `settings`, `home`, `search`, `star`, `info`, `play`, `favorite`, `apps`, `add`, `refresh`, `notifications`, `person`, `lock`, `menu`, or any emoji such as `"🎬"`. `card`/`banner` tiles show icon and label inside; `icon` tiles show the label below.

## Content widgets (not focusable)

- **`text`**: `text` (required), `style` (`display` 56, `headline` 34, `title` 22, `body` 16, `caption` 12), `color`, `bold`, `textAlign` (`start`/`center`/`end`), `maxLines`.
- **`clock`**: `format` (java.time pattern; `"HH:mm"` default, `"h:mm a"`, `"EEEE, d MMMM"` for a date), `style` (default `display`), `color`, `bold`, `textAlign`.
- **`image`**: `src` (required; `https://…` or `asset:<path>`), `fit` (`contain` default, `cover`, `fill`), `alpha` 0–1. Give it a `width` and `height`.
- **`spacer`**: empty space; use `width`/`height` or `weight`.

## Actions (for `shortcut.action`)

- `{ "type": "launch_app", "package": "com.netflix.ninja" }`
- `{ "type": "open_url", "url": "https://example.com" }` (also deep links)
- `{ "type": "intent", "action": "android.intent.action.VIEW", "data": "…", "package": "…", "component": "pkg/.Activity", "categories": [], "extras": { "key": "value" } }` — needs at least one of action, package, component.
- `{ "type": "go_to_page", "page": "apps" }`
- `{ "type": "open_app_drawer" }` — built-in list of all apps.
- `{ "type": "open_system_settings", "screen": "main" | "network" | "bluetooth" | "display" | "sound" | "apps" | "date_time" | "accessibility" }`
- `{ "type": "open_launcher_settings" }` — OwnTV's import, templates and history.

## Complete example

```json
{
  "schemaVersion": 1,
  "name": "Movie night",
  "theme": { "colors": { "primary": "#E50914", "custom": { "hero": "#2A0E12" } } },
  "background": { "type": "gradient", "colors": ["#2A0E12", "#0B0D12"], "angle": 180 },
  "pages": [
    {
      "id": "home",
      "initialFocus": "streaming",
      "root": {
        "type": "column", "id": "root", "padding": [48, 28], "spacing": 20, "scroll": true,
        "children": [
          {
            "type": "row", "id": "header", "width": "fill",
            "children": [
              { "type": "text", "id": "title", "text": "Movie night", "style": "headline", "bold": true },
              { "type": "spacer", "id": "gap", "weight": 1 },
              { "type": "clock", "id": "clock", "format": "HH:mm", "style": "title" }
            ]
          },
          {
            "type": "app_row", "id": "streaming", "title": "Streaming",
            "source": { "type": "packages", "packages": ["com.netflix.ninja", "com.disney.disneyplus"] },
            "tile": { "style": "card", "size": "large" }
          },
          {
            "type": "row", "id": "actions", "spacing": 16,
            "children": [
              { "type": "shortcut", "id": "all", "label": "All apps", "icon": "apps", "action": { "type": "open_app_drawer" }, "tile": { "style": "icon", "size": "small" } },
              { "type": "shortcut", "id": "settings", "label": "Settings", "icon": "settings", "action": { "type": "open_system_settings" }, "tile": { "style": "icon", "size": "small" } }
            ]
          },
          { "type": "app_grid", "id": "everything", "title": "All apps", "columns": 6, "source": { "type": "all" }, "tile": { "style": "banner", "size": "small" } }
        ]
      }
    }
  ]
}
```

## Validation

OwnTV checks every layout before it can be applied and reports problems with a JSON path, for example `ERROR at $.pages[0].root.children[1].source: Missing required property "packages".` Errors block the layout; warnings (unknown properties, apps that are not installed, pages with nothing to focus) do not. Text around a ```json code block, comments and trailing commas are tolerated.
