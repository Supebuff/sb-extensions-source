# Mugua

Current module: `src/zh/mugua`, version **16.6**, targeting AniZen 0.5.220. For implementation differences, shared development checks, and troubleshooting evidence, see [Extension technical notes](../../../docs/EXTENSION_NOTES.md).

## 16.6: dedicated launcher icon

Mugua now uses the red-and-white icon supplied by the repository owner as a 32 × 32 PNG data URL. It is resized with Lanczos interpolation to the five Android launcher densities (48, 72, 96, 144, and 192 px), preserving its design and alpha channel. Upscaling does not add source detail. The module's `res/mipmap-*/ic_launcher.png` resources override the shared core icon through Android resource merging. The shared icon and other extensions' icons are unchanged.

This is an icon and version update. Source/package identity, catalogue URLs, network behavior, and the fixed signing key are preserved. APK resource contents and CI results are checked separately from the icon's appearance on a physical device, which remains pending verification.

## 16.5: request protection and feature list

The source settings now show a read-only **功能列表**, protection scope, and a **同一主機請求間隔** selector (0.5 seconds by default; 1 or 2 seconds optional). Requests to each hostname are spaced independently, including redirect destinations. The interval applies to listings, search, details, playback-page resolution, and covers downloaded in local compatibility mode.

HTTP 429 and HTTP 503 with `Retry-After` pause new requests to that host. Both delay-seconds and HTTP-date values are accepted; a longer server deadline is preserved. Missing or invalid 429 delays start at 30 seconds and increase on repeated blocks, up to 15 minutes. There is no automatic HTTP-status retry loop: page requests report the remaining wait immediately, while cover requests leave the cover blank. Waits for normal request spacing are cancellable.

Local cover downloads are limited to two at a time. Network failures, HTTP errors, and invalid images pause retries for that URL for five minutes. Concurrent loads of the same URL share the cache/failure decision, and cached images remain usable during cooldowns. Failed covers return no remote URL, preventing the app from immediately retrying outside this policy. Refresh the list after the cooldown to try missing covers again. Cooldown and failed-cover records are in memory and reset when the source is recreated; image files remain persistent.

These protections reduce request bursts; they do not guarantee that a website will allow access. Remote images with local compatibility disabled, player buffering, media segments, and app-managed downloads remain under the app's control. The source ID, catalogue URLs, and existing library entries are unchanged. AniZen 0.5.220 is the compatibility target; Android device playback and settings rendering still require device verification.

## 16.4: cover compatibility for AniZen r-4821

Enable **本機縮圖相容模式** in the source settings (enabled by default), then refresh the source list. On the affected AniZen version, direct cover requests can lose source headers, and the image server rejects requests without the site's Referer.

In compatibility mode, the extension downloads covers with its own request headers and returns app-private local file paths. The original 16.4 implementation allowed four concurrent downloads; 16.5 and later limit this to two. Downloads reuse existing files, reject non-image responses, and cap individual images at 2 MiB. The image cache keeps approximately 64 MiB; small URL metadata records allow evicted images to be restored when source details are refreshed. Files are excluded from Android backups.

The first list load may take longer. No app replacement, third-party image proxy, or app-data reset is required. Existing library entries can be refreshed through their source details to adopt local covers.

If the app's cover loader is fixed later, turn compatibility mode off and refresh the source list to use direct image URLs again.

## Development checks

JVM tests are in the module's `test/` directory and cover parsing, pagination, request cooldowns, cancellation, and the local cover cache. Run:

```sh
./gradlew :src:zh:mugua:testDebugUnitTest :core:testDebugUnitTest
./gradlew :src:zh:mugua:assembleRelease -PunsignedRelease=true
```

This documentation review did not repeat Mugua device tests. Device results for X1hub do not establish Mugua playback, settings, or download compatibility. Shared CI and signing operations are documented in [docs/CI.md](../../../docs/CI.md).

Documentation-only edits to this README or recognized files in the module's `docs/` directory skip APK building and signing in normal `changed` scope, while workflow lint and CI regression tests still run. Mixed code/resource/build changes remain eligible for building, and manual `added` scope can still build all fork-added extensions. See [documentation selection rules](../../../docs/CI.md#documentation-only-changes).
