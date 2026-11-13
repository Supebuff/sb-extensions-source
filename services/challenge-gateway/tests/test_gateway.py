import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

from gateway.app import cancellable, create_app
from gateway.backend import ScraplingBackend
from gateway.security import GatewayError, Site, Tickets, public_addresses

KEY = "test-key-" * 8
SITE = Site("x1hub", "https://site.test/", frozenset({"site.test"}))
PAGE = "https://site.test/videos/123/example/"
IMAGE = "https://site.test/contents/videos_screenshots/1.jpg"
MEDIA = "https://site.test/get_file/1/example.mp4"


class Stream(httpx.AsyncByteStream):
    def __init__(self):
        self.closed = False

    async def __aiter__(self):
        yield b"test-mp4"

    async def aclose(self):
        self.closed = True


class FakeBackend:
    site = SITE

    def __init__(self):
        self.page_html = AsyncMock(return_value=(b"<html>example</html>", PAGE))
        self.image = AsyncMock(return_value=(b"jpeg", "image/jpeg"))
        self.close = AsyncMock()
        self.streams = asyncio.Semaphore(2)
        self.seen = []
        self.stream = Stream()
        self.transport = SimpleNamespace(aclose=AsyncMock())

    async def media(self, url, referer, range_header, method):
        await self.streams.acquire()
        self.seen.append((url, referer, range_header, method))
        return (httpx.Response(206, stream=self.stream),
                {"content-type": "video/mp4", "content-range": "bytes 0-7/100", "content-length": "8"}, self.transport)


class SecurityTests(unittest.TestCase):
    def test_ticket_tampering_expiry_and_site_isolation(self):
        now = [100]
        tickets = Tickets(KEY, now=lambda: now[0])
        value = tickets.issue("x1hub", MEDIA, "media", PAGE)
        self.assertEqual(MEDIA, tickets.verify(value, "x1hub")["u"])
        for invalid, site in ((value + "0", "x1hub"), (value, "other"), ("broken", "x1hub")):
            with self.assertRaises(GatewayError):
                tickets.verify(invalid, site)
        now[0] += 43200
        with self.assertRaises(GatewayError):
            tickets.verify(value, "x1hub")

    def test_url_scope_rejects_credentials_ports_private_urls_and_other_paths(self):
        for url in ("http://site.test/", "https://site.test:444/", "https://user:password@site.test/",
                    "https://127.0.0.1/", "https://site.test.evil/", "https://site.test/#fragment"):
            with self.subTest(url=url), self.assertRaises(GatewayError):
                SITE.validate(url)
        for url, kind in (("https://site.test/admin", "image"), (PAGE, "media")):
            with self.assertRaises(GatewayError):
                SITE.validate(url, kind)
        self.assertEqual("cdn.test", SITE.validate("https://cdn.test/a.mp4", "media", redirect=True))


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.backend = FakeBackend()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(KEY, {"x1hub": self.backend})),
                                        base_url="http://gateway.test")
        self.auth = {"Authorization": "Bearer " + KEY}

    async def asyncTearDown(self):
        await self.client.aclose()

    async def ticket(self, url, kind):
        response = await self.client.post("/v1/sites/x1hub/tickets", headers=self.auth,
                                          json={"urls": [url], "kind": kind, "referer": PAGE})
        self.assertEqual(200, response.status_code)
        value = response.json()["urls"][0]
        self.assertNotIn(KEY, value)
        return value

    async def test_pages_require_key_and_preserve_source_url_without_cookies(self):
        denied = await self.client.get("/v1/sites/x1hub/page", params={"url": PAGE})
        self.assertEqual(401, denied.status_code)
        self.backend.page_html.assert_not_called()
        page = await self.client.get("/v1/sites/x1hub/page", params={"url": PAGE}, headers=self.auth)
        self.assertEqual(200, page.status_code)
        self.assertEqual(PAGE, page.headers["x-source-url"])
        self.assertNotIn("set-cookie", page.headers)
        self.assertEqual("private, no-store", page.headers["cache-control"])

    async def test_image_ticket_works_without_api_key_and_rejects_tampering(self):
        path = await self.ticket(IMAGE, "image")
        image = await self.client.get(path)
        self.assertEqual(b"jpeg", image.content)
        self.assertEqual("image/jpeg", image.headers["content-type"])
        self.assertEqual(401, (await self.client.get(path + "x")).status_code)

    async def test_range_streaming_preserves_headers_and_releases_resources(self):
        path = await self.ticket(MEDIA, "media")
        response = await self.client.get(path, headers={"Range": "bytes=0-7", "Cookie": "phone=secret"})
        self.assertEqual(206, response.status_code)
        self.assertEqual(b"test-mp4", response.content)
        self.assertEqual("bytes 0-7/100", response.headers["content-range"])
        self.assertEqual([(MEDIA, PAGE, "bytes=0-7", "GET")], self.backend.seen)
        self.assertTrue(self.backend.stream.closed)
        self.backend.transport.aclose.assert_awaited_once()
        self.assertEqual(2, self.backend.streams._value)

    async def test_head_and_invalid_range(self):
        path = await self.ticket(MEDIA, "media")
        response = await self.client.head(path)
        self.assertEqual(b"", response.content)
        self.assertEqual("8", response.headers["content-length"])
        self.assertEqual(2, self.backend.streams._value)
        response = await self.client.get(path, headers={"Range": "bytes=0-1,4-5"})
        self.assertEqual(416, response.status_code)

    async def test_errors_are_explicit_and_do_not_expose_browser_details(self):
        self.backend.page_html.side_effect = RuntimeError("private-cookie-and-url")
        response = await self.client.get("/v1/sites/x1hub/page", params={"url": PAGE}, headers=self.auth)
        self.assertEqual(502, response.status_code)
        self.assertNotIn("private-cookie", response.text)
        self.backend.page_html.side_effect = GatewayError(429, "cooldown", 60)
        response = await self.client.get("/v1/sites/x1hub/page", params={"url": PAGE}, headers=self.auth)
        self.assertEqual(429, response.status_code)
        self.assertEqual("60", response.headers["retry-after"])

    async def test_forbidden_urls_are_rejected_before_backend_access(self):
        response = await self.client.get("/v1/sites/x1hub/page", params={"url": "https://127.0.0.1/"}, headers=self.auth)
        self.assertEqual(400, response.status_code)
        self.backend.page_html.assert_not_called()
        response = await self.client.post("/v1/sites/x1hub/tickets", headers=self.auth,
                                          json={"urls": [MEDIA] * 101, "kind": "media", "referer": PAGE})
        self.assertEqual(422, response.status_code)


class BackendTests(unittest.IsolatedAsyncioTestCase):
    async def test_dns_rejects_loopback_ipv6_and_mixed_answers(self):
        for addresses in (["127.0.0.1"], ["::1"], ["::ffff:127.0.0.1"], ["1.1.1.1", "192.168.1.2"]):
            answer = [(None, None, None, None, (value, 443)) for value in addresses]
            with patch.object(asyncio.get_running_loop(), "getaddrinfo", AsyncMock(return_value=answer)):
                with self.assertRaises(GatewayError):
                    await public_addresses("cdn.test")

    async def test_cooldown_prevents_retry_and_respects_long_retry_after(self):
        backend = ScraplingBackend(SITE)
        with self.assertRaises(GatewayError):
            backend.status("site.test", 429, "3600")
        with self.assertRaises(GatewayError) as caught:
            await backend.wait("site.test")
        self.assertGreater(caught.exception.retry_after, 3500)

    async def test_disconnect_cancels_inflight_operation(self):
        cancelled = asyncio.Event()

        async def operation():
            try:
                await asyncio.sleep(60)
            finally:
                cancelled.set()
        request = SimpleNamespace(is_disconnected=AsyncMock(return_value=True))
        with self.assertRaises(GatewayError):
            await cancellable(request, operation())
        self.assertTrue(cancelled.is_set())

    async def test_media_redirect_dns_and_cookie_isolation(self):
        backend = ScraplingBackend(SITE)
        backend.page = SimpleNamespace(is_closed=lambda: False)
        context = SimpleNamespace(cookies=AsyncMock(side_effect=[[{"name": "clearance", "value": "private"}], []]))
        backend.session = SimpleNamespace(context=context)
        backend.user_agent = "browser-UA"
        seen = []
        stream = Stream()

        async def handle(request):
            seen.append(request)
            if len(seen) == 1:
                return httpx.Response(302, headers={"location": "https://cdn.test/video.mp4", "set-cookie": "leak=never"})
            return httpx.Response(206, headers={"content-type": "video/mp4", "content-range": "bytes 0-7/100"}, stream=stream)

        transport = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        with patch("gateway.backend.httpx.AsyncClient", return_value=transport), \
                patch("gateway.backend.public_addresses", AsyncMock(return_value=["1.1.1.1"])):
            response, headers, opened = await backend.media(MEDIA, PAGE, "bytes=0-7")
            self.assertEqual(b"test-mp4", b"".join([chunk async for chunk in response.aiter_raw()]))
            await response.aclose()
            await opened.aclose()
            backend.streams.release()
        self.assertEqual("clearance=private", seen[0].headers["cookie"])
        self.assertNotIn("cookie", seen[1].headers)
        self.assertNotIn("authorization", seen[1].headers)
        self.assertEqual("cdn.test", seen[1].headers["host"])
        self.assertEqual("cdn.test", seen[1].extensions["sni_hostname"])
        self.assertEqual("1.1.1.1", seen[1].url.host)
        self.assertEqual("bytes=0-7", seen[1].headers["range"])

    async def test_media_open_failure_releases_stream_slot(self):
        backend = ScraplingBackend(SITE)
        backend.page = SimpleNamespace(is_closed=lambda: False)
        backend.session = SimpleNamespace(context=SimpleNamespace(cookies=AsyncMock(return_value=[])))
        with patch("gateway.backend.public_addresses", AsyncMock(side_effect=GatewayError(400, "private"))):
            with self.assertRaises(GatewayError):
                await backend.media(MEDIA, PAGE, None)
        self.assertEqual(2, backend.streams._value)


if __name__ == "__main__":
    unittest.main()
