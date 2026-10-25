# Application localization

Bridge 0.2.377 and Companion 1.0.8 use English as the application language.
Companion also ships a Simplified Chinese catalog; a phone configured for any
other language falls back to English. This changes application presentation; it
does not send a language or region setting to the Watch.

## Android Bridge

The English catalog is `apple-watch-bridge/app/src/main/res/values/strings.xml`.
Activities, foreground-service notifications, permission explanations, pairing
dialogs, and translated in-app diagnostic messages resolve strings with
`getString(R.string.<key>)`.

Add another language in `app/src/main/res/values-<language>/strings.xml`, using
the same resource names. Android selects the matching locale automatically;
missing entries use the default English catalog. Preserve diagnostic fragments'
quoted whitespace and escaped newlines. Wire identifiers and machine-readable
transport logs are intentionally stable across languages.

The pure Java notification codec accepts a separate text-input button label.
Android supplies the localized Reply and Send labels through the dispatcher;
the codec's independent default is English. It does not localize action IDs.

## Flutter Companion

The source catalog is `apple-watch-companion/lib/l10n/app_en.arb`. Messages have
stable keys, with placeholders for dynamic values and ICU plural messages for
the watch face collection count and the photo selection count. Date formatting
uses the application's resolved locale instead of the phone's unsupported
locale.

`app_zh.arb` is the Simplified Chinese catalog. Apple's own Simplified Chinese
terminology is used for watch features and hardware: 数码表冠 (Digital Crown),
互点两下 (Double Tap), 体能训练 (Workout), 复杂功能 (complications), 表盘
(watch face), 航点 (waypoint), and 回溯 (Backtrack). Apple Watch face names are
translated (`Wayfinder` → 寻路者, `Modular` → 模块, `Infograph` → 图文), while
product and service names stay unchanged (`Apple Watch`, `Ultra`, watchOS
version numbers, `Nike Bounce Volt`, `Wi-Fi`, `Bridge`, `Companion`).
`nativePhotoLeading` and `nativePhotoTrailing` describe where the time sits on
a photo face and read 靠左 / 靠右.

`lib/l10n/strings.dart` exposes the locale loaded by the app delegate, including
an English fallback for presentation models constructed before widget startup.
Preset catalogs use getters so their labels are resolved when constructed.
The application registers Cupertino, Material, and Widgets localization
delegates; standard framework labels follow the same resolved language.

To add a language:

1. Copy `lib/l10n/app_en.arb` to `lib/l10n/app_<language>.arb`.
2. Change `@@locale` and translate message values. Keep message keys,
   placeholder names/types, and ICU syntax. Proper names may stay unchanged.
3. Run `flutter gen-l10n`, `flutter analyze`, and `flutter test` in Companion.
4. Build and relaunch the app with that phone locale. Generated
   `supportedLocales` automatically includes the new catalog.

Generated Dart files in `lib/l10n/generated/` are checked in for inspectability;
edit ARB files, then regenerate, rather than editing generated Dart.
The Android launcher label has its own `android/app/src/main/res/values/strings.xml`;
add the corresponding Android `values-<language>` label if needed.

Stable app IDs, native enum wire names, package names, saved device names,
imported watch face titles, notification contents, and pairing keys are not
translation keys. The Action Button's local target stores its enum name rather
than a translated display label. Imported/user-authored Cyrillic data remains
valid; localization must not rewrite it.

## Validation of this release

- Bridge: 992 JVM tests, 165 suites; no failures, errors, or skipped tests.
  Android lint and debug APK assembly passed.
- Companion: 36 tests passed; Flutter analysis reported no issues. The navigation
  test runs with `ru_UA` as the phone locale and checks English tabs, dialogs,
  and the resolved `en` application locale. The watch face codec round-trip
  still preserves a user-authored Cyrillic title.
- Both APKs were installed over the existing apps on the connected OnePlus.
  Screenshots show English Bridge and Companion screens on a `ru-UA` phone.
  IDS reconnected the same activated pair with setup replay disabled.
- Bond, IDS protection material, and the persistent phone identifier hashes
  remained unchanged. The pairing-session ciphertext changed during the normal
  reconnect checkpoint; this is not byte-for-byte session preservation.

Artifacts and build logs: `docs/live-20261006-localization/`.
