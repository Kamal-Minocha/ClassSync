# ClassSync — Visual Design

## Design language: Material Design 3

ClassSync follows **Material Design 3** (Google's design system, the one behind Google Classroom — the closest
widely used learning platform). In practice that means:

| Material 3 idea | How ClassSync applies it | Where |
|---|---|---|
| Colour roles from one seed colour | Thapar red is the seed; every colour is a named token (`-cs-primary`, `-cs-primary-soft` = container, `-cs-primary-soft-text` = on-container, surfaces, outline…) | `css/light.css`, `css/dark.css` |
| Tonal surfaces | Backgrounds are warm neutrals tinted with the brand red, not pure grey | `-cs-bg`, `-cs-surface-*` |
| Shape scale | Buttons and navigation items are pills (999), cards 16 px, list rows 12 px, inputs and chips 8 px | `css/base.css` |
| Filled / outlined / text buttons | `btn-primary` (filled red), default button (outlined), `btn-ghost` (text button in brand red) | `base.css` "buttons" |
| Navigation drawer with active indicator | Deep maroon drawer, active item = red pill | `.sidebar`, `.nav-item-active` |
| State layers | Hover and pressed states darken or tint the container; focus rings use the primary colour | `:hover`, `:pressed`, `:focus-visible` |
| 8 px spacing grid | Paddings and gaps use 4 / 8 / 12 / 16 / 20 / 24 / 32 | throughout |
| Imagery in headers | Course cards and course/lecture pages carry a photo banner, like Classroom's course banners | `Ui.photoBanner` |

Light and dark themes use the same roles with different values; switch with the moon/sun button
(saved per user).

## Palette (measured from the Thapar Institute images)

The colours were measured with a small Java program that buckets every pixel of each image and lists the most
common colours (run on the files in `thapar images/`).

| Source image | Measured colour | Used as |
|---|---|---|
| Logo mark (`images.png`) | `#EA1C24` (90 % of pixels) | brand red: navigation highlight, logo, brand accents |
| 3D logo (`images (3).jpg`) | `#C6161C`, `#A91A22`, `#97070A` | primary buttons/links (darker for text contrast), hover, on-container text |
| Courtyard (`images.jpg`) | `#593438`, `#673B43` | deep maroon navigation drawer and photo overlays |
| Mesh-facade blocks (`images (1).jpg`) | `#B96449` | terracotta accent, chart colour, progress gradient |
| Main building sky (`images (2).jpg`) | `#0186C6` | info colour, links in charts |
| Main building sandstone | `#FDEBC3`, `#BAA479` | warning container, warm highlights |

Status colours (success green, warning sandstone, danger rose) are kept at hues clearly different from the brand
red so an error never looks like a normal button.

## Photographs as backgrounds

| Where | Image | Treatment |
|---|---|---|
| Login brand panel | mesh-facade buildings | cover-scaled, maroon → red → maroon vertical gradient |
| Dashboard welcome banner | courtyard | cover-scaled, maroon → red → transparent horizontal gradient |
| Course cards, course page, lecture page | one of three campus photos per course (stable by course id) | cover-scaled banner, maroon gradient, white text |
| Startup screens (database down / schema update) | main building | faded behind a light (or dark) scrim |
| Window icon, top bar, login | Thapar "ti" logo mark | rounded square |

Implementation: `view/Images` loads and caches the bundled files (`src/main/resources/images/`);
`Ui.photoLayer` paints a photo with "cover" scaling, `Ui.overlay` adds a CSS-defined gradient, and
`Ui.photoBanner` stacks photo + overlay + content and clips them to rounded corners (JavaFX does not clip
background images to `-fx-background-radius` on its own).

**Image quality:** the supplied photos are small (457–678 px wide), so large banners look soft; the dark
overlays hide most of it. Higher-resolution photos (≥ 1600 px wide) can replace the files in
`src/main/resources/images/` with the same names — no code change needed.

**Rights:** the logo and campus photographs belong to Thapar Institute of Engineering & Technology. They are
used here for a college project; get the institute's permission before distributing ClassSync publicly.
