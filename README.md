# FileZen

A tidy file manager for Android. Files from Downloads and folders you watch land
in a single **Inbox**, where you preview, rename, and file them into favourite
folders in a couple of taps — no digging through directory trees.

Built with Kotlin + Jetpack Compose (Material 3). Offline-first: no login, no
backend.

## Highlights

- **Inbox File** — new files from watched folders surface in one feed; preview →
  rename → pick a favourite folder → done. Multi-select supported, plus
  "Mark tidy" for files that are already where they belong.
- **Home** — quick search, recent files with thumbnails, favourites, jump back to
  your last folder.
- **Browse** — internal storage, SD card / USB (via SAF tree grants), file-type
  categories, list/grid, range & bulk selection, cross-folder selection basket.
- **Storage** — usage breakdown by type, largest files, identical-duplicate
  finder, trash with restore, sort rules, operation history.
- **Ops** — copy/move/rename/delete/new folder/share/open-with, ZIP
  compress/extract (Zip-Slip safe), bulk rename with preview, conflict handling
  (skip / keep both / replace).
- **Safe by design** — moves copy-then-verify before deleting the source;
  per-item failures never abort a batch; cancellation cleans partial output;
  delete goes through Trash by default.
- **Monetisation hooks** — AdMob banner (Google's test IDs) on Home + Storage
  summary only; one-time "remove ads" purchase via Play Billing.

## Requirements

- Android 8.0 (API 26) and up. Full browsing needs the **All files access**
  permission (`MANAGE_EXTERNAL_STORAGE`) — granted on first launch. Media-only
  fallback shows a limited experience.
- SD card / USB volumes appear automatically when mounted; add more locations
  via "Add watched folder" / SAF grants in Settings.

## Build

See [RELEASE.md](RELEASE.md) for signed release + Play Store steps.

```bash
export ANDROID_HOME=/path/to/sdk
./gradlew :app:assembleDebug          # APK → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # unit tests
```

## Production checklist (things to fill in)

Search the codebase for `PRODUCTION TODO`:

- `app/build.gradle.kts` — real AdMob App ID in `manifestPlaceholders.admobAppId`
- `ui/../ads/Ads.kt` — real banner ad unit ID (`Ads.BANNER_UNIT_ID`)
- `billing/BillingManager.kt` — confirm `PRODUCT_ID` matches your Play Console product
- `keystore.properties` + `release.keystore` — your upload key (see RELEASE.md)
