"""Run one worker: browser sessions and cooldowns must not split across processes."""
import argparse
import json
import logging
import os
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8-sig"))
    os.environ["GATEWAY_API_KEY"] = config["api_key"]
    os.environ["GATEWAY_REAL_CHROME"] = "1" if config.get("real_chrome", True) else "0"
    os.environ["GATEWAY_X1HUB_MEDIA_HOSTS"] = ",".join(config.get("x1hub_media_hosts", []))
    # Third-party exceptions can include full requested URLs. Log only generic errors.
    logging.disable(logging.CRITICAL)
    import uvicorn
    from gateway.app import create_app
    app = create_app()
    server = uvicorn.Server(uvicorn.Config(
        app, host=config.get("bind", "127.0.0.1"), port=config.get("port", 8787),
        access_log=False, log_config=None, log_level="critical", workers=1, limit_concurrency=32,
        timeout_graceful_shutdown=15,
    ))
    app.state.shutdown = lambda: setattr(server, "should_exit", True)
    server.run()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Gateway could not start; check configuration, Python dependencies and port availability.", file=sys.stderr)
        sys.exit(1)
