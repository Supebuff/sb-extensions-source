"""Opt-in live smoke test. Prints counts/status only, never titles, tokens or URLs."""
import argparse
import html
import json
import re
import sys
from pathlib import Path
from urllib.parse import urljoin, urlsplit, urlunsplit

import httpx


def decode_kvs(value, license_code):
    if not value.startswith("function/0/"):
        return value
    value = value.removeprefix("function/0/")
    digits = license_code.replace("$", "")
    normalized = digits.replace("0", "1")
    center = len(normalized) // 2
    seed = str(abs(int(normalized[:center + 1]) - int(normalized[center:])) * 4)[:center + 1]
    token = [(int(digits[index + offset]) + int(digit)) % 10
             for index, digit in enumerate(seed) for offset in range(4)]
    parsed = urlsplit(value)
    segments = parsed.path.split("/")
    chars, total = list(segments[3][:32]), 0
    for index in range(31, -1, -1):
        total += token[index]
        other = (index + total) % 32
        chars[index], chars[other] = chars[other], chars[index]
    segments[3] = "".join(chars) + segments[3][32:]
    return urlunsplit(parsed._replace(path="/".join(segments)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--live", action="store_true", required=True)
    parser.add_argument("--images", type=int, default=24)
    args = parser.parse_args()
    if not 1 <= args.images <= 32:
        parser.error("--images must be between 1 and 32")
    config = json.loads(args.config.read_text(encoding="utf-8-sig"))
    host = config.get("bind", "127.0.0.1")
    base = f"http://{host}:{config.get('port', 8787)}"
    auth = {"Authorization": "Bearer " + config["api_key"]}
    from lxml.html import fromstring
    stats = {"page": False, "images": 0, "media_range": False, "media_seek": False,
             "phone_playback": "not tested", "challenge_solving": "not established by this probe"}
    with httpx.Client(base_url=base, timeout=110, trust_env=False) as client:
        def page(url):
            response = client.get("/v1/sites/x1hub/page", params={"url": url}, headers=auth)
            if response.status_code != 200:
                raise RuntimeError(f"page HTTP {response.status_code}")
            return response.content

        def tickets(urls, kind, referer):
            response = client.post("/v1/sites/x1hub/tickets", headers=auth,
                                   json={"urls": urls, "kind": kind, "referer": referer})
            if response.status_code != 200:
                raise RuntimeError(f"tickets HTTP {response.status_code}")
            return response.json()["urls"]

        latest = "https://www.x1hub.com/latest-updates/"
        root = fromstring(page(latest))
        links = root.cssselect(".list-videos .item > a[href]")
        if not links:
            raise RuntimeError("no listing items")
        stats["page"] = True
        images = []
        for link in links[:args.images]:
            image = next(iter(link.cssselect("img")), None)
            if image is not None:
                value = next((image.get(key) for key in ("data-original", "data-src", "src") if image.get(key)), None)
                if value:
                    images.append(urljoin(latest, value))
        for path in tickets(images, "image", latest):
            response = client.get(path)
            if response.status_code != 200 or not response.headers.get("content-type", "").startswith("image/"):
                raise RuntimeError(f"image HTTP {response.status_code}")
            stats["images"] += 1
        video_page = urljoin(latest, links[0].get("href"))
        body = page(video_page).decode("utf-8")
        match = re.search(r"\bvar\s+flashvars\s*=\s*\{([\s\S]*?)\}\s*;", body)
        fields = {item[0]: html.unescape(item[2].replace("\\/", "/")) for item in
                  re.findall(r"\b(\w+)\s*:\s*(['\"])((?:\\.|(?!\2)[^\\])*)\2", match[1])}
        media = next(decode_kvs(value, fields.get("license_code", "")) for key, value in fields.items()
                     if re.fullmatch(r"video_(?:url|alt_url[0-9]*)", key) and fields.get(key + "_redirect") != "1")
        path = tickets([urljoin(video_page, media)], "media", video_page)[0]
        for offset, label in ((0, "media_range"), (1024, "media_seek")):
            with client.stream("GET", path, headers={"Range": f"bytes={offset}-{offset + 1023}"}) as response:
                if response.status_code != 206 or not response.headers.get("content-range", "").startswith(f"bytes {offset}-"):
                    raise RuntimeError(f"{label} HTTP {response.status_code}")
                content = b"".join(response.iter_bytes())
                if len(content) != 1024 or (offset == 0 and content[4:8] != b"ftyp"):
                    raise RuntimeError("invalid MP4 range")
                stats[label] = True
        print(json.dumps(stats))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Sanitize arbitrary library exceptions (which may contain complete capability URLs).
        safe = str(error) if type(error) is RuntimeError else type(error).__name__
        print(json.dumps({"probe": "failed", "reason": safe}))
        sys.exit(1)
