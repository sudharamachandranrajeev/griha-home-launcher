# Theme engine: separating themes from the app

Status: proposal, November 2026. Decisions taken with the owner are marked **Decided**.

## The goal

Griha becomes the **engine**: everything a theme can use (colours, depth and glow, corners, page
backgrounds, wallpapers with touch motion, fonts, lock-screen styles). A **theme** becomes a small
package of data that the engine reads. Themes can be downloaded, the built-in ones can be hidden,
and a theme that needs something new says so, so the app and the theme stay in step.

## Is it possible? Yes, with one rule: themes are data, never code

| Option | Verdict |
| --- | --- |
| **Data packages** (JSON plus pictures and fonts in a ZIP) read by the engine | **Recommended.** Safe, small, easy to check and to make. Every theme feature today is already a set of numbers and colours: see `Depth.spec`, `settingsPalette`, `FinishBackdrop` and `WallpaperDrawable`. |
| Code plugins (downloaded DEX loaded with `DexClassLoader`) | **Rejected.** A theme could do anything the launcher can. Android 14 and later also require dynamically loaded code to be read-only and treat it as a common malware route. |
| Theme APKs (one app per theme, like Nova/ADW icon packs) | Possible later for third-party themes: Android installs, signs and removes them, so deleting frees space. Heavier to make and needs the "install unknown apps" step for each. |

**Decided:** themes are data only and come from a **griha-themes** GitHub repository.

## Built-in themes

An app cannot delete files from its own APK, so a built-in theme can be **hidden**, not removed.
That costs little: all eight layouts, six finishes and their wallpapers are drawn by code, and the
three bundled fonts are about 300 KB together. Only downloaded parts (pictures, fonts, whole themes)
take space that can be freed.

**Decided:** defaults ship in the app as theme files and can be hidden; downloads can be deleted.

## The theme package

A theme is a ZIP named `<id>-<version>.griha-theme`:

```text
theme.json          what the theme is and what it needs
wallpapers/*.webp   optional pictures (size-capped)
fonts/*.ttf         optional fonts (size-capped)
preview.webp        a picture for the catalogue
```

`theme.json` (shape, not final):

```json
{
  "id": "glass-aurora",
  "name": "Glass Aurora",
  "version": 3,
  "engine": 2,
  "requires": ["depth.v1", "backdrop.haze", "wall.ripple"],
  "prefers": ["wall.parallax"],
  "palette": { "light": { "background": "#E9EAF1", "surface": "#FFFFFF", "accent": "#5E45D6", "...": "..." },
               "dark":  { "...": "..." } },
  "depth": { "corners": 1.2, "card": { "fill": "glass", "rim": 1.5, "shadows": [[0, 8, 22, "tint@0.26"]] },
             "key": { "...": "..." }, "field": { "...": "..." }, "primary": { "...": "..." } },
  "headings": { "caps": false, "spacing": 0.02 },
  "backdrop": { "kind": "haze", "tints": ["#7FD8BE", "#A18CFF", "#FFAE8A"] },
  "touch": { "pulse": "ripple", "tints": "cycle" },
  "wallpapers": [{ "file": "wallpapers/aurora.webp", "dim": 0.3 }],
  "fonts": { "headings": "fonts/Inter-SemiBold.ttf" }
}
```

## Keeping the app and themes in step

1. **The engine version and feature list.** Griha declares an `engine` number and the feature names it
   supports, such as `depth.v1`, `backdrop.grid`, `backdrop.haze`, `wall.ripple`, `wall.embers` and
   `lock.blur.bokeh`. Each release exports this list as `features.json`.
2. **A theme names what it `requires` and what it `prefers`.** Griha refuses a theme it cannot fully
   draw ("Needs Griha 0.7 or later. Update?") and quietly drops `prefers` features it lacks.
3. **Checked in both repositories.** The griha-themes CI validates every theme against the JSON schema
   and the `features.json` of the latest Griha release. A theme that needs a new feature cannot be
   published until a Griha release supports it. Griha's own tests load every built-in and
   catalogue theme. A new feature is then a two-step change: add it to the engine, release, then
   publish the theme.

## The lock screen (Griha 0.7.0)

A theme may add a `lock` section: `subject` (wash, duotone, rim or halo), `amount` (0 to 1, kept subtle),
`tint` and `text` colour tokens, and `font`. Features `lock.theme` and `lock.subject` are listed under
`prefers`, so Griha 0.6.0 still installs the theme and simply ignores the section. The user's lock photo
is never changed; only its cut-out subject is styled, and the user can pick another look or none.

## Downloading and safety

- **Catalogue:** `index.json` from a griha-themes release lists each theme's id, version, engine,
  requires, size, preview and **SHA-256**. Griha fetches it only when you open "Get more themes".
- **Download:** a theme is accepted only if its SHA-256 matches the index, it unpacks below a size
  cap, and every ZIP entry stays inside its own folder. Java's ZIP reader does not stop `../` entry
  names (Zip Slip), so each target path is canonicalised and checked first. Pictures are decoded with
  size bounds. JSON is validated against the schema; unknown keys are ignored.
- **Storage:** each theme gets `files/themes/<id>/<version>/`. Delete removes the folder. Settings
  shows the size of each downloaded theme.

## Plan

| Phase | What ships | Visible change |
| --- | --- | --- |
| 1. Engine | Today's finishes and palettes become `theme.json` files read by one engine (`ThemeSpec`), replacing the per-finish code in `Depth.spec`. Feature list and engine number added. | None. Same looks, now data-driven. |
| 2. Theme store | Settings, Themes: built-in and downloaded themes, hide, delete, sizes, import a `.griha-theme` file. | Themes page. |
| 3. griha-themes | New repo with schema, CI validation against Griha's `features.json`, a release workflow that builds `index.json` with SHA-256. | First downloadable themes. |
| 4. Catalogue | "Get more themes" in Griha: previews, download, update and delete. | Downloads in the app. |

Phase 1 is the largest change in Griha; phases 2 to 4 build on it. The owner creates the
griha-themes repository; this session can't create repositories.

## Sources

- [Android – Zip Path Traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)
- [Android 14 preview: read-only dynamic code loading (InfoQ)](https://www.infoq.com/news/2023/02/android-14-preview/)
- [Termux: Android 14 read-only DEX requirement in practice](https://github.com/termux/termux-packages/issues/16255)
