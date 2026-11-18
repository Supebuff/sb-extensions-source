import asyncio
from io import StringIO
import json
import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

from gateway.app import cancellable, create_app
from gateway.backend import ABORT_IMAGE_JS, ScraplingBackend
from gateway.security import GatewayError, Site, Tickets, public_addresses
from gateway.timing import TimingLog

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


class TimingTests(unittest.IsolatedAsyncioTestCase):
    async def test_close_after_declared_body_differs_from_midstream_disconnect(self):
        from gateway.timing import tag

        for sent, outcome in [(b"12345678", "complete"), (b"1234", "disconnected")]:
            async def stream(scope, receive, send):
                tag("kind", "media")
                await send({"type": "http.response.start", "status": 206,
                            "headers": [(b"content-length", b"8")]})
                await send({"type": "http.response.body", "body": sent, "more_body": True})
                await receive()
                await send({"type": "http.response.body", "body": b""})

            output = StringIO()
            await TimingLog(stream, output)(
                {"type": "http", "method": "GET"},
                AsyncMock(return_value={"type": "http.disconnect"}),
                AsyncMock(),
            )
            self.assertEqual(outcome, json.loads(output.getvalue())["outcome"])


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.backend = FakeBackend()
        self.app = create_app(KEY, {"x1hub": self.backend})
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app),
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

    async def test_opt_in_timing_records_stream_delivery_without_request_data(self):
        path = await self.ticket(MEDIA, "media")
        output = StringIO()
        transport = httpx.ASGITransport(app=TimingLog(self.app, output))
        async with httpx.AsyncClient(transport=transport, base_url="http://gateway.test") as client:
            self.assertEqual(200, (await client.get("/healthz")).status_code)
            response = await client.get(path, headers={"Range": "bytes=0-7", "Cookie": "phone=secret"})
            self.assertEqual(b"test-mp4", response.content)
            self.assertEqual(206, response.status_code)
        record = json.loads(output.getvalue())
        self.assertEqual(("media", True, 206, "complete"),
                         (record["kind"], record["range"], record["status"], record["outcome"]))
        self.assertLessEqual(record["media_open_ms"], record["headers_ms"])
        self.assertLessEqual(record["headers_ms"], record["first_body_ms"])
        self.assertLessEqual(record["first_body_ms"], record["total_ms"])
        self.assertNotIn("test-mp4", output.getvalue())
        self.assertNotIn("phone=secret", output.getvalue())
        self.assertNotIn(path.split("=", 1)[1], output.getvalue())
        self.assertNotIn("site.test", output.getvalue())
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

    async def test_shutdown_requires_key_and_calls_server_callback(self):
        stopped = []
        self.app.state.shutdown = lambda: stopped.append(True)
        self.assertEqual(401, (await self.client.post("/v1/shutdown")).status_code)
        self.assertEqual([], stopped)
        self.assertEqual(200, (await self.client.post("/v1/shutdown", headers=self.auth)).status_code)
        self.assertEqual([True], stopped)

    async def test_stream_failure_still_releases_transport_and_slot(self):
        class BrokenStream(Stream):
            async def __aiter__(self):
                yield b"part"
                raise OSError("synthetic stream interruption")
        self.backend.stream = BrokenStream()
        path = await self.ticket(MEDIA, "media")
        with self.assertRaises(Exception):
            await self.client.get(path)
        self.assertTrue(self.backend.stream.closed)
        self.assertEqual(2, self.backend.streams._value)
        self.backend.transport.aclose.assert_awaited_once()


class BackendTests(unittest.IsolatedAsyncioTestCase):
    async def test_resource_page_bootstraps_once_and_stays_available_during_navigation(self):
        backend = ScraplingBackend(SITE)
        backend.page = SimpleNamespace(is_closed=lambda: False)
        page = SimpleNamespace(is_closed=lambda: False, route=AsyncMock(), unroute=AsyncMock(),
                               route_web_socket=AsyncMock(), goto=AsyncMock(), close=AsyncMock())
        context = SimpleNamespace(new_page=AsyncMock(return_value=page))
        backend.session = SimpleNamespace(context=context)
        self.assertIs(page, await backend._image_page())
        bootstrap = page.route.await_args_list[0].args[1]
        route = SimpleNamespace(request=SimpleNamespace(url=SITE.home, is_navigation_request=lambda: True),
                                fulfill=AsyncMock())
        await bootstrap(route)
        route.fulfill.assert_awaited_once()
        backend._guard = AsyncMock()
        route.request.url = "https://untrusted.test/"
        await bootstrap(route)
        backend._guard.assert_awaited_once_with(route)
        async with backend.lock:
            self.assertIs(page, await asyncio.wait_for(backend._image_page(), 0.5))
        context.new_page.assert_awaited_once()
        page.unroute.assert_awaited_once_with("**/*", bootstrap)

    async def test_cancelled_resource_page_initialization_closes_only_new_tab(self):
        backend = ScraplingBackend(SITE)
        backend.page = SimpleNamespace(is_closed=lambda: False)
        page = SimpleNamespace(route=AsyncMock(), route_web_socket=AsyncMock(), close=AsyncMock(),
                               goto=AsyncMock(side_effect=asyncio.CancelledError))
        session = SimpleNamespace(context=SimpleNamespace(new_page=AsyncMock(return_value=page)), close=AsyncMock())
        backend.session = session
        with self.assertRaises(asyncio.CancelledError):
            await backend._image_page()
        page.close.assert_awaited_once()
        session.close.assert_not_awaited()
        self.assertIsNone(backend.resource_page)

    async def test_repeated_image_requests_share_one_browser_fetch(self):
        backend = ScraplingBackend(SITE)
        page = SimpleNamespace(is_closed=lambda: False, evaluate=AsyncMock(return_value={
            "status": 200, "type": "image/jpeg", "data": "anBlZw==",
        }))
        backend._image_page = AsyncMock(return_value=page)
        with patch("gateway.backend.public_addresses", AsyncMock(return_value=["1.1.1.1"])):
            first, second = await asyncio.gather(backend.image(IMAGE), backend.image(IMAGE))
        self.assertEqual((b"jpeg", "image/jpeg"), first)
        self.assertEqual(first, second)
        page.evaluate.assert_awaited_once()

    async def test_cancelling_image_aborts_only_its_fetch_and_preserves_session(self):
        backend = ScraplingBackend(SITE)
        entered = asyncio.Event()
        async def stalled(script, argument):
            if script == ABORT_IMAGE_JS:
                return
            entered.set()
            await asyncio.sleep(60)
        page = SimpleNamespace(evaluate=AsyncMock(side_effect=stalled))
        backend._image_page = AsyncMock(return_value=page)
        session = SimpleNamespace(close=AsyncMock())
        backend.session = session
        with patch("gateway.backend.public_addresses", AsyncMock(return_value=["1.1.1.1"])):
            task = asyncio.create_task(backend.image(IMAGE))
            await entered.wait()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        session.close.assert_not_awaited()
        self.assertIs(session, backend.session)
        calls = page.evaluate.await_args_list
        self.assertEqual(ABORT_IMAGE_JS, calls[1].args[0])
        self.assertEqual(calls[0].args[1]["id"], calls[1].args[1])
        page.evaluate.side_effect = None
        page.evaluate.return_value = {"status": 200, "type": "image/jpeg", "data": "anBlZw=="}
        with patch("gateway.backend.public_addresses", AsyncMock(return_value=["1.1.1.1"])):
            self.assertEqual((b"jpeg", "image/jpeg"), await backend.image(IMAGE))

    async def test_stalled_images_do_not_block_media_and_concurrency_is_bounded(self):
        backend = ScraplingBackend(SITE)
        backend.page = SimpleNamespace(is_closed=lambda: False)
        backend.session = SimpleNamespace(context=SimpleNamespace(cookies=AsyncMock(return_value=[])))
        entered, release = asyncio.Event(), asyncio.Event()
        running = maximum = 0
        async def fetch(*args):
            nonlocal running, maximum
            running += 1
            maximum = max(maximum, running)
            if running == 2:
                entered.set()
            try:
                await release.wait()
                return {"status": 200, "type": "image/jpeg", "data": "anBlZw=="}
            finally:
                running -= 1
        backend._image_page = AsyncMock(return_value=SimpleNamespace(evaluate=fetch))
        backend.wait = AsyncMock()
        # Avoid incidental stripe collisions in this concurrency test.
        urls = {}
        for index in range(100):
            url = IMAGE + f"?sample={index}"
            urls.setdefault(hash(url) % 32, url)
        transport = httpx.AsyncClient(transport=httpx.MockTransport(lambda request:
            httpx.Response(206, headers={"content-type": "video/mp4"}, stream=Stream())))
        with patch("gateway.backend.public_addresses", AsyncMock(return_value=["1.1.1.1"])), \
                patch("gateway.backend.httpx.AsyncClient", return_value=transport):
            tasks = [asyncio.create_task(backend.image(url)) for url in list(urls.values())[:3]]
            try:
                await asyncio.wait_for(entered.wait(), 2)
                response, _, opened = await asyncio.wait_for(backend.media(MEDIA, PAGE, "bytes=0-7"), 2)
                await response.aclose()
                await opened.aclose()
                backend.streams.release()
                self.assertFalse(any(task.done() for task in tasks))
            finally:
                release.set()
                await asyncio.gather(*tasks)
        self.assertEqual(2, maximum)

    async def test_cancelling_navigation_preserves_resource_page_and_context(self):
        backend = ScraplingBackend(SITE)
        backend.session = SimpleNamespace(close=AsyncMock(), close_pages=AsyncMock())
        backend.resource_page = object()
        backend._page = AsyncMock(side_effect=asyncio.CancelledError)
        with self.assertRaises(asyncio.CancelledError):
            await backend.page_html(PAGE)
        backend.session.close_pages.assert_awaited_once()
        backend.session.close.assert_not_awaited()
        self.assertIsNotNone(backend.resource_page)

    async def test_pacing_stays_serial_and_rechecks_cooldown_after_wait(self):
        backend = ScraplingBackend(SITE)
        async def start():
            await backend.wait("site.test")
            return time.monotonic()
        starts = await asyncio.gather(*(start() for _ in range(3)))
        self.assertTrue(all(second - first >= 0.45 for first, second in zip(starts, starts[1:])))
        cooling = asyncio.create_task(backend.wait("site.test"))
        await asyncio.sleep(0.05)
        backend.cooldowns["site.test"] = time.monotonic() + 60
        with self.assertRaises(GatewayError):
            await cooling

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

    async def test_media_target_cache_skips_entry_and_refreshes_expired_target_once(self):
        backend = ScraplingBackend(SITE)
        backend.page = SimpleNamespace(is_closed=lambda: False)
        backend.session = SimpleNamespace(context=SimpleNamespace(cookies=AsyncMock(return_value=[])))
        backend.wait = AsyncMock()
        seen, clients = [], []
        statuses = iter([302, 206, 206, 403, 302, 206, 429])
        def handle(request):
            seen.append((request.headers["host"], request.headers["range"]))
            status = next(statuses)
            headers = {"location": "https://cdn.test/video.mp4"} if status == 302 else {"content-type": "video/mp4"}
            return httpx.Response(status, headers=headers, stream=Stream())
        client_type = httpx.AsyncClient
        def client(**kwargs):
            value = client_type(transport=httpx.MockTransport(handle))
            clients.append(value)
            return value
        dns = AsyncMock(return_value=["1.1.1.1"])
        with patch("gateway.backend.httpx.AsyncClient", side_effect=client), \
                patch("gateway.backend.public_addresses", dns):
            try:
                for index in range(3):
                    response, _, opened = await backend.media(MEDIA, PAGE, f"bytes={index}-7")
                    await response.aclose()
                    await opened.aclose()
                    backend.streams.release()
                    if index == 0:
                        expiry = backend.media_targets[(MEDIA, PAGE)][0]
                    elif index == 1:
                        self.assertEqual(expiry, backend.media_targets[(MEDIA, PAGE)][0])
                with self.assertRaises(GatewayError) as caught:
                    await backend.media(MEDIA, PAGE, "bytes=3-7")
                self.assertEqual(429, caught.exception.status)
            finally:
                for value in clients:
                    await value.aclose()
        self.assertEqual(["site.test", "cdn.test", "cdn.test", "cdn.test", "site.test", "cdn.test", "cdn.test"],
                         [host for host, _ in seen])
        self.assertEqual(["bytes=0-7"] * 2 + ["bytes=1-7"] + ["bytes=2-7"] * 3 + ["bytes=3-7"],
                         [value for _, value in seen])
        self.assertEqual(7, dns.await_count)
        self.assertEqual(2, backend.streams._value)

    async def test_expired_media_target_is_resolved_from_original_entry(self):
        backend = ScraplingBackend(SITE)
        backend.page = SimpleNamespace(is_closed=lambda: False)
        backend.session = SimpleNamespace(context=SimpleNamespace(cookies=AsyncMock(return_value=[])))
        backend.media_targets[(MEDIA, PAGE)] = (time.monotonic() - 1, "https://cdn.test/stale.mp4")
        backend.wait = AsyncMock()
        seen = []
        def handle(request):
            seen.append(request.headers["host"])
            return httpx.Response(206, headers={"content-type": "video/mp4"}, stream=Stream())
        transport = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        with patch("gateway.backend.httpx.AsyncClient", return_value=transport), \
                patch("gateway.backend.public_addresses", AsyncMock(return_value=["1.1.1.1"])):
            response, _, opened = await backend.media(MEDIA, PAGE, "bytes=0-7")
            await response.aclose()
            await opened.aclose()
            backend.streams.release()
        self.assertEqual(["site.test"], seen)
        self.assertNotIn((MEDIA, PAGE), backend.media_targets)


if __name__ == "__main__":
    unittest.main()
