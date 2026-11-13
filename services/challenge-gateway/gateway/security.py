"""The API key never appears in resource URLs; scoped tickets authorize players/images."""
import asyncio
import base64
import hashlib
import hmac
import ipaddress
import json
import socket
import time
from dataclasses import dataclass
from urllib.parse import urlsplit


class GatewayError(Exception):
    def __init__(self, status, message, retry_after=None):
        self.status, self.message, self.retry_after = status, message, retry_after


@dataclass(frozen=True)
class Site:
    name: str
    home: str
    hosts: frozenset[str]
    media_hosts: frozenset[str] = frozenset()

    def validate(self, url, kind="page", redirect=False):
        try:
            parsed = urlsplit(url)
            valid = (parsed.scheme == "https" and parsed.port in (None, 443)
                     and not parsed.username and not parsed.password
                     and not parsed.fragment and len(url) <= 8192)
            host = (parsed.hostname or "").lower()
        except ValueError:
            valid, host = False, ""
        allowed = self.hosts | (self.media_hosts if kind == "media" else frozenset())
        # Only a trusted source's media redirect may introduce another public CDN.
        if not valid or (host not in allowed and not (kind == "media" and redirect)):
            raise GatewayError(400, "網址不在此來源允許範圍。")
        if kind == "image" and not parsed.path.startswith("/contents/videos"):
            raise GatewayError(400, "僅允許影片縮圖。")
        if kind == "media" and not redirect and host in self.hosts and not parsed.path.startswith("/get_file/"):
            raise GatewayError(400, "僅允許公開影片入口。")
        return host


async def public_addresses(host):
    """Reject private/local targets, including redirects and IPv4-mapped IPv6."""
    try:
        records = await asyncio.get_running_loop().getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        addresses = sorted({item[4][0] for item in records})
    except OSError:
        raise GatewayError(502, "無法解析來源主機。") from None
    if not addresses or any(not ipaddress.ip_address(value).is_global for value in addresses):
        raise GatewayError(400, "拒絕連線至非公開來源位址。")
    return addresses


class Tickets:
    def __init__(self, secret, now=time.time):
        self.secret, self.now = secret.encode(), now

    def issue(self, site, url, kind, referer):
        payload = json.dumps(dict(s=site, u=url, k=kind, r=referer,
                                  e=int(self.now()) + (604800 if kind == "image" else 43200)),
                             separators=(",", ":")).encode()
        value = base64.urlsafe_b64encode(payload).rstrip(b"=").decode()
        signature = hmac.new(self.secret, value.encode(), hashlib.sha256).hexdigest()
        return value + "." + signature

    def verify(self, value, site):
        try:
            if len(value) > 24000:
                raise ValueError()
            payload, signature = value.rsplit(".", 1)
            expected = hmac.new(self.secret, payload.encode(), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, signature):
                raise ValueError()
            data = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            if (data["s"] != site or data["k"] not in ("image", "media")
                    or data["e"] <= self.now()):
                raise ValueError()
            return data
        except (ValueError, KeyError, TypeError):
            raise GatewayError(401, "資源連結已過期或無效，請重新整理或重新開播。") from None
