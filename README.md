# Halo Count

Point your phone at a tray of similar objects (capsules, tablets, screws, beads...) and it counts them,
live, with glowing rings around everything it has counted. Works fully offline. No OpenCV, no ML model:
a small pure-Kotlin detector, so the APK is tiny and detection takes a few milliseconds per frame.

made by Unmesh Khare

## Get the APK (GitHub does the build)

1. Create a new repository on github.com (private is fine).
2. Upload the contents of this folder to it (drag the files in, keeping the folder structure, including the
   hidden-looking `.github` folder). If `.github/workflows/build.yml` does not arrive, use
   *Add file > Create new file*, type `.github/workflows/build.yml` as the name and paste the file's content.
3. Open the **Actions** tab. The *Build APK* workflow runs on every push (or press *Run workflow*).
   It takes about 4-6 minutes the first time.
4. Open the finished run, scroll to **Artifacts**, download **HaloCount-apk**, unzip it, and copy
   `app-release.apk` to your phone. Open it and allow "install unknown apps" when asked.

## Using it

- Put the phone on a stand or a stack of books, pointing straight down at the objects on a plain,
  contrasting background (white paper under dark capsules, or the opposite). Even light, no glare.
- **Live mode** (default): the big number is the stabilised count; it turns green when it has held steady.
- **SNAP**: freezes a higher-resolution photo and counts it. Rings appear one by one and are numbered.
  - tap an empty spot: add an object the app missed
  - tap a ring: remove a false one
  - **LIVE** (same button) goes back to the camera
- **Hold an object** (live or snapped): tells the app "this is one object". It uses that size to split
  clumps and ignore dust. **AUTO SIZE** returns to automatic sizing.
- Tap anywhere in live mode to focus. **TORCH** toggles the flash.

## How the counting works

Otsu threshold -> background/object polarity from the frame border -> 3x3 clean-up -> distance transform ->
h-maxima watershed (splits touching objects at their necks) -> dust filter -> size-based split of blobs
that are clearly two or more objects (side-by-side capsules, for example).

Tested on synthetic scenes (touching discs and capsules, dust, dark and light backgrounds, uneven light,
sensor noise): within 0-2 of the true count on 40-75 objects.

## Known limits

- Heavy overlap (objects lying on top of each other), shiny glare and patterned backgrounds reduce accuracy.
- Objects smaller than about 2% of the frame width are ignored as dust; move the phone closer.
- Portrait only for now.

## Tuning (in `Counter.kt`)

`HMIN`/`HREL` control how eagerly touching objects are split, `MIN_PEAK` is the dust size,
`SPLIT_RATIO` is how big a blob must be (relative to one object) before it is counted as several,
and `MIN_CONTRAST` is the "low contrast" warning level. Live grid width is `LIVE_W`, photo is `SNAP_W`
in `MainActivity.kt`.
