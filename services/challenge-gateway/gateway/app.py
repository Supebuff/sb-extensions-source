import asyncio
import hmac
import os
import re
import anyio
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, Field
from typing import Literal
from starlette.background import BackgroundTask

from .backend import ScraplingBackend
from .security import GatewayError, Site, Tickets


class TicketRequest(BaseModel):
    kind: Literal["image", "media"]
    urls: list[str] = Field(min_length=1, max_length=100)
    referer: str


async def cancellable(request, operation):
    stopped = asyncio.Event()
    async def watch():
        while not stopped.is_set() and not await request.is_disconnected():
            await asyncio.sleep(0.25)
    task, disconnected = asyncio.create_task(operation), asyncio.create_task(watch())
    try:
        done, _ = await asyncio.wait({task, disconnected}, timeout=90,
                                     return_when=asyncio.FIRST_COMPLETED)
        if task in done:
            return await task
        raise GatewayError(499 if disconnected in done else 504, "請求已取消或逾時。")
    finally:
        stopped.set()
        for pending in (task, disconnected):
            if not pending.done():
                pending.cancel()
            with suppress(asyncio.CancelledError, Exception):
                await pending


def create_app(key=None, backends=None):
    key = key if key is not None else os.environ.get("GATEWAY_API_KEY", "")
    if len(key) < 32:
        raise RuntimeError("GATEWAY_API_KEY must contain at least 32 characters")
    if backends is None:
        site = Site("x1hub", "https://www.x1hub.com/", frozenset({"www.x1hub.com", "x1hub.com"}),
                    frozenset(filter(None, os.environ.get("GATEWAY_X1HUB_MEDIA_HOSTS", "").lower().split(","))))
        backends = {site.name: ScraplingBackend(site, os.environ.get("GATEWAY_REAL_CHROME") == "1")}
    tickets = Tickets(key)

    @asynccontextmanager
    async def lifespan(app):
        yield
        for backend in backends.values():
            await backend.close()

    app = FastAPI(title="Challenge gateway", docs_url=None, redoc_url=None,
                  openapi_url=None, lifespan=lifespan)

    @app.middleware("http")
    async def errors(request, call_next):
        try:
            response = await call_next(request)
        except GatewayError as error:
            response = JSONResponse({"error": error.message}, status_code=error.status,
                                    headers={"Retry-After": str(error.retry_after)} if error.retry_after else {})
        except Exception:
            # Do not leak upstream URLs/cookies/browser exceptions to logs or clients.
            response = JSONResponse({"error": "網關擷取失敗，請確認服務狀態並重試。"}, status_code=502)
        response.headers["Cache-Control"] = "private, no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    def backend_for(site):
        if site not in backends:
            raise GatewayError(404, "尚未接入此來源。")
        return backends[site]

    def authenticate(request):
        if not hmac.compare_digest(request.headers.get("authorization", ""), "Bearer " + key):
            raise GatewayError(401, "服務金鑰無效。")

    @app.get("/healthz")
    async def health():
        return {"status": "ok", "api": 1}

    @app.post("/v1/shutdown")
    async def shutdown(request: Request):
        authenticate(request)
        callback = getattr(app.state, "shutdown", None)
        if callback is None:
            raise GatewayError(503, "此執行環境不支援停止服務。")
        callback()
        return {"status": "stopping"}

    @app.get("/v1/sites/{site}/page")
    async def page(site: str, url: str, request: Request):
        authenticate(request)
        backend = backend_for(site)
        backend.site.validate(url)
        html, source_url = await cancellable(request, backend.page_html(url))
        return Response(html, media_type="text/html", headers={"X-Source-Url": source_url})

    @app.post("/v1/sites/{site}/tickets")
    async def issue(site: str, data: TicketRequest, request: Request):
        authenticate(request)
        backend = backend_for(site)
        backend.site.validate(data.referer)
        result = []
        for url in data.urls:
            backend.site.validate(url, data.kind)
            value = tickets.issue(site, url, data.kind, data.referer)
            result.append(f"/v1/sites/{site}/asset?ticket={value}")
        return {"urls": result}

    @app.api_route("/v1/sites/{site}/asset", methods=["GET", "HEAD"])
    async def asset(site: str, ticket: str, request: Request):
        backend = backend_for(site)
        data = tickets.verify(ticket, site)
        backend.site.validate(data["u"], data["k"])
        backend.site.validate(data["r"])
        if data["k"] == "image":
            content, mime = await cancellable(request, backend.image(data["u"]))
            return Response(b"" if request.method == "HEAD" else content,
                            headers={"Content-Type": mime, "Content-Length": str(len(content))})
        range_header = request.headers.get("range")
        if range_header and not re.fullmatch(r"bytes=(?:\d+-\d*|-\d+)", range_header):
            raise GatewayError(416, "僅支援單一位元組範圍。")
        response, headers, transport = await cancellable(
            request, backend.media(data["u"], data["r"], range_header, request.method))

        closed = False
        async def finish():
            nonlocal closed
            if closed:
                return
            closed = True
            with anyio.CancelScope(shield=True):
                try:
                    await response.aclose()
                finally:
                    try:
                        await transport.aclose()
                    finally:
                        backend.streams.release()

        async def chunks():
            try:
                if request.method != "HEAD":
                    async for chunk in response.aiter_raw(chunk_size=65536):
                        yield chunk
            finally:
                await finish()

        return StreamingResponse(chunks(), status_code=response.status_code, headers=headers,
                                 background=BackgroundTask(finish))

    return app
