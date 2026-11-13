"""Scrapling owns each site's browser state. No browser cookies leave this process."""
import asyncio
import base64
import logging
import time
import httpx
import anyio
from collections import OrderedDict
from contextlib import suppress
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import urljoin, urlsplit

from .security import GatewayError, public_addresses

IMAGE_JS = """async url => {
  const response = await fetch(url, {credentials: 'include', redirect: 'error'});
  const type = response.headers.get('content-type') || '';
  if (!response.ok || !type.startsWith('image/'))
    return {status: response.status, type, retry: response.headers.get('retry-after')};
  const reader = response.body.getReader(); const chunks = []; let size = 0;
  try {
    while (true) {
      const {done, value} = await reader.read(); if (done) break;
      size += value.length; if (size > 8388608) throw Error('Image too large');
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let binary = '';
  for (let i = 0; i < bytes.length; i += 32768)
    binary += String.fromCharCode(...bytes.subarray(i, i + 32768));
  return {status: response.status, type, data: btoa(binary)};
}"""


class ScraplingBackend:
    def __init__(self, site, real_chrome=False):
        self.site, self.real_chrome = site, real_chrome
        self.lock = asyncio.Lock()
        self.streams = asyncio.Semaphore(2)
        self.session = None
        self.page = None
        self.user_agent = ""
        self.next_request = 0
        self.cooldowns = {}
        self.failures = {}
        self.images = OrderedDict()
        self.image_bytes = 0

    async def _session(self):
        if self.session is None:
            from scrapling.fetchers import AsyncStealthySession
            # Avoid library URL/debug logs containing search terms or media capabilities.
            logging.getLogger("scrapling").setLevel(logging.CRITICAL)
            self.session = AsyncStealthySession(
                headless=True, real_chrome=self.real_chrome, max_pages=1,
                solve_cloudflare=True, google_search=False, timeout=60000, retries=1,
            )
            try:
                await self.session.start()
            except BaseException:
                await self.close()
                raise
        return self.session

    async def _guard(self, route):
        url = route.request.url
        parsed = urlsplit(url)
        if parsed.scheme in ("about", "data", "blob"):
            return await route.continue_()
        try:
            host = parsed.hostname
            if parsed.scheme != "https" or parsed.port not in (None, 443) or parsed.username or parsed.password:
                raise GatewayError(400, "封鎖非 HTTPS 瀏覽器請求。")
            if host not in self.site.hosts | {"challenges.cloudflare.com"}:
                raise GatewayError(400, "封鎖非來源的瀏覽器請求。")
            self.site.validate(url) if host in self.site.hosts else None
            if host in self.site.hosts and route.request.resource_type in ("image", "media", "font"):
                return await route.abort()
            await public_addresses(host)
            await route.continue_()
        except GatewayError:
            await route.abort()

    def status(self, host, status, retry=None):
        now = time.monotonic()
        if status == 429 or (status == 503 and retry):
            try:
                seconds = int(retry)
            except (ValueError, TypeError):
                try:
                    seconds = (parsedate_to_datetime(retry) - datetime.now(timezone.utc)).total_seconds()
                except (ValueError, TypeError, OverflowError):
                    count = self.failures.get(host, 0)
                    seconds = min(900, 30 * 2 ** min(count, 5))
            self.failures[host] = self.failures.get(host, 0) + 1
            self.cooldowns[host] = max(self.cooldowns.get(host, 0), now + max(1, seconds))
            raise GatewayError(429, "來源限流，請稍後再試。", max(1, int(seconds)))
        if status >= 400:
            raise GatewayError(502, f"來源回應 HTTP {status}；驗證未完成或資源已失效。")

    async def wait(self, host):
        remaining = self.cooldowns.get(host, 0) - time.monotonic()
        if remaining > 0:
            raise GatewayError(429, "來源仍在冷卻，請稍後再試。", max(1, int(remaining + 1)))
        await asyncio.sleep(max(0, self.next_request - time.monotonic()))
        self.next_request = time.monotonic() + 0.5

    async def _page(self, url):
        host = self.site.validate(url)
        await public_addresses(host)
        await self.wait(host)
        session = await self._session()

        guarded = False
        async def setup(page):
            nonlocal guarded
            await page.route("**/*", self._guard)
            await page.route_web_socket("**/*", lambda socket: socket.close())
            guarded = True

        async def capture(page):
            self.page = page
            self.user_agent = await page.evaluate("navigator.userAgent")

        response = await session.fetch(url, page_setup=setup, page_action=capture)
        if not guarded or self.page is None:
            raise GatewayError(502, "瀏覽器會話未完成初始化。")
        self.site.validate(str(response.url))
        headers = {k.lower(): v for k, v in response.headers.items()}
        self.status(host, response.status, headers.get("retry-after"))
        body = bytes(response.body)
        if headers.get("cf-mitigated") == "challenge" or b"<title>Just a moment" in body:
            raise GatewayError(503, "網站驗證尚未完成，請稍後重試。")
        if len(body) > 8 * 1024 * 1024:
            raise GatewayError(502, "來源頁面超過大小限制。")
        return body, str(response.url)

    async def page_html(self, url):
        async with self.lock:
            try:
                return await self._page(url)
            except asyncio.CancelledError:
                await self.close()
                raise

    async def image(self, url):
        host = self.site.validate(url, "image")
        async with self.lock:
            cached = self.images.get(url)
            if cached and cached[0] > time.monotonic():
                self.images.move_to_end(url)
                return cached[1:]
            await public_addresses(host)
            await self.wait(host)
            try:
                if self.page is None or self.page.is_closed():
                    await self._page(self.site.home)
                result = await self.page.evaluate(IMAGE_JS, url)
                self.status(host, result["status"], result.get("retry"))
                if not result.get("data") or not result["type"].startswith("image/"):
                    raise GatewayError(503, "縮圖驗證尚未完成。")
                data = base64.b64decode(result["data"])
                old = self.images.pop(url, None)
                if old:
                    self.image_bytes -= len(old[1])
                self.images[url] = (time.monotonic() + 600, data, result["type"])
                self.image_bytes += len(data)
                while len(self.images) > 128 or self.image_bytes > 64 * 1024 * 1024:
                    self.image_bytes -= len(self.images.popitem(last=False)[1][1])
                return data, result["type"]
            except asyncio.CancelledError:
                await self.close()
                raise

    async def media(self, url, referer, range_header, method="GET"):
        """Stream bytes with browser UA/cookies on this machine; never buffer a movie."""
        self.site.validate(url, "media")
        await self.streams.acquire()
        transport = None
        response = None
        try:
            async with self.lock:
                if self.page is None or self.page.is_closed():
                    await self._page(referer)
                context = self.session.context
                ua = self.user_agent
            # HTTPX pulls chunks on demand, so a slow player cannot buffer an entire movie.
            transport = httpx.AsyncClient(timeout=httpx.Timeout(60, connect=15), trust_env=False)
            for redirects in range(6):
                host = self.site.validate(url, "media", redirect=redirects > 0)
                addresses = await public_addresses(host)
                async with self.lock:
                    await self.wait(host)
                    cookies = await context.cookies([url])
                headers = {"User-Agent": ua, "Referer": referer, "Accept-Encoding": "identity"}
                if cookies:
                    headers["Cookie"] = "; ".join(f"{c['name']}={c['value']}" for c in cookies)
                if range_header:
                    headers["Range"] = range_header
                # Pin the checked address while retaining the source's TLS SNI and Host.
                # A redirect receives fresh DNS validation and host-specific cookies.
                headers["Host"] = host
                transport.cookies.clear()
                target = httpx.URL(url).copy_with(host=addresses[0])
                outgoing = transport.build_request(method, target, headers=headers,
                                                    extensions={"sni_hostname": host})
                response = await transport.send(outgoing, stream=True, follow_redirects=False)
                if response.status_code in (301, 302, 303, 307, 308):
                    location = response.headers.get("location")
                    await response.aclose()
                    if not location or redirects == 5:
                        raise GatewayError(502, "影片重新導向無效或次數過多。")
                    url = urljoin(url, location)
                    continue
                if response.status_code != 416:
                    self.status(host, response.status_code, response.headers.get("retry-after"))
                    mime = response.headers.get("content-type", "").lower()
                    if not (mime.startswith("video/") or mime.startswith("application/octet-stream")):
                        raise GatewayError(502, "來源沒有回傳可播放影片，請重新開播。")
                forwarded = {k: v for k, v in response.headers.items() if k.lower() in {
                    "content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified",
                }}
                return response, forwarded, transport
            raise GatewayError(502, "影片重新導向次數過多。")
        except BaseException:
            with anyio.CancelScope(shield=True):
                try:
                    if response is not None:
                        await response.aclose()
                finally:
                    try:
                        if transport is not None:
                            await transport.aclose()
                    finally:
                        self.streams.release()
            raise

    async def close(self):
        session, self.session, self.page = self.session, None, None
        if session is not None:
            with anyio.CancelScope(shield=True):
                with suppress(Exception):
                    await session.close()
