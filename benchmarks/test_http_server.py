#!/usr/bin/env python3
"""
Lightweight high-throughput local HTTP test server for Magnet Platform benchmarks.
Supports:
- Deterministic synthetic payloads (1MB, 10MB, 100MB) without disk I/O bottlenecks.
- HEAD and GET requests.
- RFC 7233 byte-range requests (Range: bytes=start-end, 206 Partial Content).
- Access from localhost and Docker containers (0.0.0.0:18080 -> host.docker.internal:18080).
"""

import argparse
import http.server
import re
import socketserver
import sys

DEFAULT_PORT = 18080

# Pre-defined file sizes
SIZES = {
    "1m.bin": 1 * 1024 * 1024,
    "10m.bin": 10 * 1024 * 1024,
    "50m.bin": 50 * 1024 * 1024,
    "100m.bin": 100 * 1024 * 1024,
}

# 64KB chunk of repeating deterministic bytes
CHUNK = b"MAGNET_PLATFORM_TEST_PAYLOAD_CHUNK_DATA_1234567890abcdefghijklmnopqrst\n" * 910
CHUNK_LEN = len(CHUNK)


class BenchmarkHttpHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_HEAD(self):
        self.handle_request(send_body=False)

    def do_GET(self):
        self.handle_request(send_body=True)

    def handle_request(self, send_body=True):
        path = self.path.split("?")[0].strip("/")

        if path == "health":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", "2")
            self.end_headers()
            if send_body:
                self.wfile.write(b"OK")
            return

        filename = path.split("/")[-1] if "/" in path else path
        total_size = SIZES.get(filename)

        if total_size is None:
            # Check dynamic size pattern, e.g. /file/test_10mb.bin
            match = re.search(r"(\d+)(m|k|g)b?", filename, re.IGNORECASE)
            if match:
                num = int(match.group(1))
                unit = match.group(2).lower()
                multiplier = 1024 if unit == "k" else (1024 * 1024 if unit == "m" else 1024 * 1024 * 1024)
                total_size = num * multiplier
            else:
                total_size = 10 * 1024 * 1024  # default 10MB

        range_header = self.headers.get("Range")
        start_byte = 0
        end_byte = total_size - 1

        if range_header and range_header.startswith("bytes="):
            match = re.match(r"bytes=(\d*)-(\d*)", range_header)
            if match:
                s_str, e_str = match.groups()
                if s_str:
                    start_byte = int(s_str)
                if e_str:
                    end_byte = min(int(e_str), total_size - 1)
                elif not s_str:  # suffix range: bytes=-500
                    start_byte = max(total_size - int(e_str), 0)

            if start_byte > end_byte or start_byte >= total_size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total_size}")
                self.end_headers()
                return

            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start_byte}-{end_byte}/{total_size}")
        else:
            self.send_response(200)

        length = end_byte - start_byte + 1
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        if not send_body:
            return

        # Stream generated chunk data directly to socket
        remaining = length
        while remaining > 0:
            to_write = min(remaining, CHUNK_LEN)
            try:
                self.wfile.write(CHUNK[:to_write])
                remaining -= to_write
            except (BrokenPipeError, ConnectionResetError):
                break

    def log_message(self, format, *args):
        # Keep benchmark quiet unless in debug
        pass


class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def run(port=DEFAULT_PORT):
    server_address = ("0.0.0.0", port)
    with ThreadedTCPServer(server_address, BenchmarkHttpHandler) as httpd:
        print(f"Benchmark HTTP server running on http://0.0.0.0:{port} (PID {sys.argv})")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server...")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Magnet Benchmark HTTP Server")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Port to listen on")
    args = parser.parse_args()
    run(args.port)
