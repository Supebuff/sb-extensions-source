# Mugua

## 16.4: cover compatibility for AniZen r-4821

Enable **本機縮圖相容模式** in the source settings (enabled by default), then refresh the source list. On the affected AniZen version, direct cover requests can lose source headers, and the image server rejects requests without the site's Referer.

In compatibility mode, the extension downloads covers with its own request headers and returns app-private local file paths. Downloads are limited to four at a time, reuse existing files, reject non-image responses, and cap individual images at 2 MiB. The image cache keeps approximately 64 MiB; small URL metadata records allow evicted images to be restored when source details are refreshed. Files are excluded from Android backups.

The first list load may take longer. No app replacement, third-party image proxy, or app-data reset is required. Existing library entries can be refreshed through their source details to adopt local covers.

If the app's cover loader is fixed later, turn compatibility mode off and refresh the source list to use direct image URLs again.
