"""Optional, capability-free request timing for a diagnostic run."""
from contextlib import asynccontextmanager, contextmanager
from contextvars import ContextVar
import json
from time import monotonic, time
from uuid import uuid4


_current = ContextVar("gateway_timing", default=None)


def tag(field, value):
    record = _current.get()
    if record is not None:
        record[field] = value


@contextmanager
def measure(field):
    record = _current.get()
    started = monotonic() if record is not None else None
    try:
        yield
    finally:
        if record is not None:
            record[field] = record.get(field, 0) + (monotonic() - started) * 1000


@asynccontextmanager
async def queued(lock):
    with measure("queue_ms"):
        await lock.acquire()
    try:
        yield
    finally:
        lock.release()


class TimingLog:
    def __init__(self, app, output):
        self.app = app
        self.output = output

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        started = monotonic()
        record = {"id": uuid4().hex, "start_epoch": time()}
        token = _current.set(record)
        disconnected = complete = False
        expected_bytes = None
        sent_bytes = 0

        async def receive_timed():
            nonlocal disconnected
            message = await receive()
            if message["type"] == "http.disconnect" and not complete:
                disconnected = True
            return message

        async def send_timed(message):
            nonlocal complete, expected_bytes, sent_bytes
            elapsed = (monotonic() - started) * 1000
            if message["type"] == "http.response.start":
                record["status"] = message["status"]
                record["headers_ms"] = elapsed
                for name, value in message.get("headers", []):
                    if name.lower() == b"content-length" and len(value) <= 19 and value.isdigit():
                        expected_bytes = int(value)
                        break
                if scope["method"] == "HEAD":
                    expected_bytes = 0
            elif message["type"] == "http.response.body" and message.get("body"):
                record.setdefault("first_body_ms", elapsed)
            was_connected = not disconnected
            await send(message)
            if message["type"] == "http.response.body" and was_connected:
                sent_bytes += len(message.get("body", b""))
                # Clients can close after receiving Content-Length, before the ASGI end marker.
                if expected_bytes is not None:
                    complete = sent_bytes == expected_bytes
                elif not message.get("more_body") and not disconnected:
                    complete = True
            elif message["type"] == "http.response.start" and expected_bytes == 0 and was_connected:
                complete = True

        try:
            await self.app(scope, receive_timed, send_timed)
        finally:
            _current.reset(token)
            if "kind" in record:
                record["outcome"] = (
                    "disconnected" if record.get("status") == 499
                    else "complete" if complete else "disconnected" if disconnected else "incomplete"
                )
                record["total_ms"] = (monotonic() - started) * 1000
                try:
                    self.output.write(json.dumps(record) + "\n")
                    self.output.flush()
                except OSError:
                    # A diagnostic file failure must not fail or replace a response.
                    pass
