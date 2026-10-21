"""Timestral forecasting server for Jarvis's prediction layer.

POST /forecast  {"values": [..], "horizon": 7, "quantiles": [0.1, 0.5, 0.9]}
             -> {"model": "...", "median": [..], "quantiles": {"0.1": [..], ...}}
GET  /health -> {"ok": true, "model": "..."}

Loopback only by default. CPU is enough (209M parameters); the model loads
once at start. Missing values may be sent as null (they become NaN, which
Timestral reads as missing).

    pip install "git+https://github.com/mistralai/timestral.git"
    python server.py --model mistralai/Timestral-209M --port 8095
"""

import argparse
import json
import math
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_CONTEXT = 32_768
MAX_HORIZON = 1024
MAX_BODY = 4 * 1024 * 1024


def to_list(x, horizon):
    """A forecast output (array/tensor, maybe [1, h] or [h, 1]) as a flat list of the last `horizon` floats."""
    import numpy as np

    arr = np.asarray(x.detach().cpu().numpy() if hasattr(x, "detach") else x, dtype=float).reshape(-1)
    return [float(v) for v in arr[-horizon:]]


class Model:
    def __init__(self, name, device):
        from timestral import TimestralForecaster

        self.name = name
        self.lock = threading.Lock()
        self.model = TimestralForecaster.from_pretrained(name, device=device)

    def forecast(self, values, horizon, quantiles):
        import numpy as np

        context = np.array([math.nan if v is None else float(v) for v in values], dtype=float)
        with self.lock:
            out = self.model.forecast(context, prediction_length=horizon)
        # out.quantiles is (B, V, H, Q) over the model's own levels; take the
        # nearest level to each one asked for (0.1 vs 0.1000000001 must not fail).
        levels = list(out.quantile_levels)
        nearest = lambda q: min(range(len(levels)), key=lambda i: abs(levels[i] - q))
        result = {"model": self.name, "median": to_list(out.quantiles[..., nearest(0.5)], horizon), "quantiles": {}}
        for q in quantiles:
            result["quantiles"][str(q)] = to_list(out.quantiles[..., nearest(q)], horizon)
        return result


def validate(body):
    values = body.get("values")
    if not isinstance(values, list) or len(values) < 3:
        raise ValueError("values must be a list of at least 3 numbers")
    if len(values) > MAX_CONTEXT:
        values = values[-MAX_CONTEXT:]
    for v in values:
        if v is not None and (not isinstance(v, (int, float)) or isinstance(v, bool) or not math.isfinite(v)):
            raise ValueError("values must be numbers (or null for missing)")
    horizon = body.get("horizon", 7)
    if not isinstance(horizon, int) or isinstance(horizon, bool) or not 1 <= horizon <= MAX_HORIZON:
        raise ValueError(f"horizon must be an integer 1..{MAX_HORIZON}")
    quantiles = body.get("quantiles", [0.1, 0.5, 0.9])
    if not isinstance(quantiles, list) or not quantiles or len(quantiles) > 21:
        raise ValueError("quantiles must be a list of 1..21 levels")
    for q in quantiles:
        if not isinstance(q, (int, float)) or not 0 < q < 1:
            raise ValueError("each quantile must be between 0 and 1")
    return values, horizon, quantiles


def make_handler(model):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, code, obj):
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/health":
                return self._send(200, {"ok": True, "model": model.name})
            self._send(404, {"error": "not found"})

        def do_POST(self):
            if self.path != "/forecast":
                return self._send(404, {"error": "not found"})
            length = int(self.headers.get("content-length") or 0)
            if length <= 0 or length > MAX_BODY:
                return self._send(413, {"error": "body missing or too large"})
            try:
                body = json.loads(self.rfile.read(length))
                values, horizon, quantiles = validate(body)
            except (ValueError, json.JSONDecodeError) as err:
                return self._send(400, {"error": str(err)})
            try:
                self._send(200, model.forecast(values, horizon, quantiles))
            except Exception as err:  # the model failing must not kill the server
                self._send(500, {"error": f"forecast failed: {err}"})

        def log_message(self, fmt, *args):
            pass

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="mistralai/Timestral-209M")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8095)
    ap.add_argument("--device", default="cpu")
    a = ap.parse_args()
    model = Model(a.model, a.device)
    server = ThreadingHTTPServer((a.host, a.port), make_handler(model))
    print(f"forecast server: {a.model} on http://{a.host}:{a.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
