# Sift UI Spec — Material 3 Expressive, violet seed

Approved by the user on 2026-10-07. The clickable mock lives in `docs/design/mock/` (one `.dc.html` per screen; open the markup to read exact sizes and colors — the `<script>` blocks hold the interaction logic). This document is the source of truth; where it and the mock differ, follow this document.

Visual reference the user likes: the Koda app (github.com/Ivorisnoob/Koda). Koda is GPL-3.0 — borrow ideas only, never copy its code.

## Foundations

### Library versions
- `androidx.compose.material3:material3:1.5.0-beta01` (1.4.0 stable keeps `MaterialExpressiveTheme` internal). In 1.5.0-beta01 `MaterialExpressiveTheme`, `ButtonGroup`, `FloatingActionButtonMenu`, `ShortNavigationBar`, `ToggleButton` are public; `LoadingIndicator`/`ContainedLoadingIndicator` need `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.

### Color (light scheme; seed violet #6B3FD4)
| Role | Hex |
|---|---|
| primary | #6B3FD4 |
| onPrimary | #FFFFFF |
| primaryContainer | #E5D8FF |
| onPrimaryContainer | #23005C |
| background / surface | #FBF7FF |
| surfaceContainer | #F3ECFF |
| surfaceContainerHigh | #ECE3FA |
| surfaceContainerHighest | #E9DEF8 |
| onSurface (body ink) | #1E1530 |
| display ink (titles) | #2A1450 |
| onSurfaceVariant | #5B5470 |
| keep accent (tertiary) | #C6F24E, on: #1E2B00 |
| remove / delete accent | #FF6B57, on: #3A0A00 |
| inverse / strong dark surface | #2A1450, on: #FFFFFF |

Per-collection tonal palette (container / ink), assigned round-robin when a collection is created and stored in `CollectionEntity.colorHex`: pink #FFD8EC/#3E0A2A, lilac #E5D8FF/#23005C, sky #CFE5FF/#00264D, lime #E4F7B5/#1E2B00, peach #FFDCC8/#3A1500, mint #C9F2DE/#00311F. A collection's page uses its container color as accents and a very light tint as background (Memes: #FFF4F9).

Dark scheme: derive with the same roles (deep violet #140B24 background, #E5D8FF titles); dynamic color is OFF by default (the brand look matters), optional in settings later.

### Type
- Display/headline: **Bricolage Grotesque** (variable, `res/font/bricolage_grotesque.ttf`), weight 800, tight negative letter-spacing.
- Body/labels: **DM Sans** (variable, `res/font/dm_sans.ttf`), 400/500/700.
- Screen titles: 58–72 sp, line-height ~0.95, letter-spacing ≈ −2.5 sp. Section headers: 28 sp/800. Card titles: 34 sp/800. Body 15–16 sp, captions 13–14 sp.
- Never use `FontFamily.Default` (on the user's vivo phone it renders an OEM font).

### Shape
- Fully round (pill/circle) for buttons, chips, search bar, nav, icon buttons.
- Cards 28–40 dp corners; thumbnails 18 dp; review cards 36 dp outer / 26 dp image.
- Expressive shape play: collection mosaics mix a circle, a rounded square and an arch; a selected filter button morphs from pill to 14 dp corners.

### Motion
- `MotionScheme.expressive()` via `MaterialExpressiveTheme`.
- Spring-y overshoot on card snap-back and stack advance; background color floods animate ~220 ms.

## Navigation
Floating pill navigation (not a full-width bar): container #E9DEF8, 6 dp padding, shadow, centered 22 dp above the bottom. Active item expands into a primary pill with icon + label; inactive items are 52 dp icon-only circles. Destinations: **Library · Collections · Review**. Settings is a circular icon button in the Library header.

## Screens

### Library (`mock/Main.dc.html`)
- Title "Library" + "959 screenshots" caption; settings icon button (52 dp circle, #ECE3FA).
- Pill search bar (58 dp, #ECE3FA, search icon, primary mic circle). Typing runs hybrid search (`ScreenshotRepository.hybridSearch`) and replaces the grid with results.
- Indexing card (primaryContainer, 28 dp corners): "Indexing N of M", "Keep Sift open · about X left", thick 12 dp progress bar. Shows only while work remains; tapping it starts indexing now.
- Grid: 3 columns, 6 dp gaps, 18 dp corners, no filenames, no ML Kit label chips. Section headers by date ("Today", "Last week", "August"...).

### Collections (`mock/Collections.dc.html`)
- Title "Collections" + caption. One tonal card per collection: big name, “description” in quotes, mosaic of 3 thumbnails in mixed shapes, big count, dark "N to review" badge with lime dot when there are unreviewed AUTO members.
- Lime 64 dp rounded-square FAB "New collection" → sheet with Name + Description ("what should it fill with?").
- No default collections. Empty state invites creating the first one.

### Collection page (`mock/Collection.dc.html`)
- Back + overflow circles; 72 sp title; tappable chip `Fills with “<description>”` (edit description → re-run auto-fill); "N screenshots · X MB".
- Big dark pill "Review N unsure" → Review screen in collection mode.
- Filter: All / Auto / Kept (ButtonGroup style; selected morphs to 14 dp corners). Kept = MANUAL members, Auto = AUTO members.
- Grid like Library; MANUAL ("kept") tiles show a small check badge.

### Review (`mock/Review.dc.html`) — two modes, same screen
Header: back circle + search pill "Find screenshots to clean up" with suggestion chips (only in collection mode / empty search). Progress label + thick progress bar.

**Collection mode** (opened from a collection): stack = `getReviewQueue(collectionId)` (least confident first).
- Swipe right / lime button = keep → `keepInCollection` (MANUAL).
- Swipe left / coral ✕ button = remove from collection → `removeFromCollection` (EXCLUDED, remembered).
- Separate outlined "Delete file" button = delete from storage (system delete request), never a swipe.
- Done card: "Memes sorted!" with kept/removed/deleted counts.

**Search mode** (user typed a prompt): stack = hybrid search results across the whole library, least confident first.
- Swipe right / lime = keep (do nothing). Swipe left / coral trash = MARK for deletion (no file is deleted yet).
- After the uncertain ones are reviewed and only strong matches remain, show "Mark remaining N strong matches".
- End: summary card "Delete N files?" + freed size + one coral "Delete N files · X MB" button → a single Android `createDeleteRequest` for all marked files → "X MB freed!" card. "Review again" restarts.

Shared review behavior: top card drags with finger, rotates (dx/16 deg), KEEP / REMOVE (or DELETE) stamp fades in with drag distance, whole background floods lime/coral proportionally; 110 dp threshold to commit, otherwise spring back; two cards peek behind (scale 0.95/0.90, offset 18/36 dp, slight rotation); undo snackbar after every action; haptic tick on commit.

## Accessibility
- Every icon-only button has a content description. Swipe actions always have button equivalents.
- Text contrast ≥ 4.5:1 (the palette above is chosen for this). Keep vs remove differ in lightness, not only hue.
- Touch targets ≥ 48 dp.
