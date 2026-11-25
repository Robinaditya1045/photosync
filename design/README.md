# Photo Sync design mockups

Static HTML mockups (inline styles and SVG, no JavaScript) of the Photo Sync home-screen widgets and app screens. Fonts are vendored, so everything renders offline.

## View

Open `design/index.html` in a browser. It shows every board at its real size; each caption links to the board file.

## Files

- `index.html` - overview of all boards
- `widget-week.html` - Week widget, 4x2 (364x172)
- `widget-progress.html` - Progress widget, 2x2 (172x172)
- `widget-count.html` - Count widget, 2x2
- `widget-all-safe.html` - All safe widget, 2x2
- `widget-states.html` - Other widget states: waiting, can't find Pixel, paused, Pixel full (800x236)
- `widget-hub.html` - Hub widget, 4x2, on the Pixel
- `app-1-role.html` - app step 1, choose a role (390x844)
- `app-2-connect.html` - app step 2, connect to the hub
- `app-3-albums.html` - app step 3, choose albums
- `app-home-phone.html` - home on a sending phone
- `app-home-hub.html` - home on the hub (Pixel)
- `fonts.css`, `fonts/` - Bricolage Grotesque and Inter variable TTFs with their SIL OFL licences
- `screenshots/` - 2x PNG renders of each board and of `index.html`

## Regenerate screenshots

Per board (example, week widget at 364x172):

```
/usr/bin/google-chrome --headless=new --no-sandbox --screenshot=screenshots/widget-week.png --window-size=364,172 --hide-scrollbars --force-device-scale-factor=2 file://$PWD/widget-week.html
```

Use `--window-size=390,844` for app screens, `800,236` for widget-states, and `2400,1450` for `index.html` (-> `overview.png`).

## Colour tokens

| Token | Value |
|---|---|
| Widget ground | #16171B |
| App background | #0E0F12 |
| Raised surface | #24262C |
| Text | #F4F5F7 |
| Secondary text | #A9ADB8 |
| Progress | #8C9BFF |
| Safe | #3DDC97 |
| Attention | #FF9A4D, #FFC24D |
