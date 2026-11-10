"""Regression checks for admin seed HTTP requests and validation recovery."""

import importlib.util
import socket
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError

SPEC = importlib.util.spec_from_file_location(
    "seed_diagnosis_problems", Path(__file__).with_name("seed-diagnosis-problems.py")
)
seed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed)


class SeedDiagnosisProblemsTest(unittest.TestCase):
    def test_redirect_does_not_send_admin_token_to_another_origin(self):
        received = []

        class Gateway(BaseHTTPRequestHandler):
            def do_GET(self):
                received.append((self.path, self.headers.get("Authorization")))
                self.send_response(302)
                self.send_header("Location", f"http://localhost:{self.server.server_port}/redirected")
                self.end_headers()

            def log_message(self, *_):
                pass

        with HTTPServer(("127.0.0.1", 0), Gateway) as server:
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                with self.assertRaises(HTTPError) as raised:
                    seed.call(base, "test-token", "/original")
                with raised.exception as response:
                    self.assertEqual(response.code, 302)
            finally:
                server.shutdown()
                thread.join()
        self.assertEqual(received, [("/original", "Bearer test-token")])

    @patch.object(seed.time, "sleep")
    def test_poll_retries_transient_errors_without_new_validation(self, _sleep):
        passed = {"status": "PASSED", "resultApplied": True}
        request = Mock(side_effect=[
            HTTPError("http://gateway.test", 503, "", None, None),
            TimeoutError(),
            URLError("temporarily unavailable"),
            passed,
        ])

        result = seed.wait_for_validation(request, "/progress/1", "PASSED", time.monotonic() + 180)

        self.assertEqual(result, passed)
        self.assertEqual([call.args for call in request.call_args_list], [("/progress/1",)] * 4)

    @patch.object(seed.time, "sleep")
    def test_poll_retries_when_gateway_closes_during_request(self, _sleep):
        for sent_partial_body in (False, True):
            with self.subTest(sent_partial_body=sent_partial_body):
                self._assert_poll_retries_after_disconnect(sent_partial_body)

    def _assert_poll_retries_after_disconnect(self, sent_partial_body):
        paths = []
        body = b'{"data":{"status":"PASSED","resultApplied":true}}'

        class Gateway(BaseHTTPRequestHandler):
            def do_GET(self):
                paths.append(self.path)
                first_request = len(paths) == 1
                if not first_request or sent_partial_body:
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body[:8] if first_request else body)
                    self.wfile.flush()
                if first_request:
                    self.connection.shutdown(socket.SHUT_RDWR)

            def log_message(self, *_):
                pass

        with HTTPServer(("127.0.0.1", 0), Gateway) as server:
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"

                def request(path, *, timeout):
                    return seed.call(base, "test-token", path, timeout=timeout)

                result = seed.wait_for_validation(request, "/progress/1", "PASSED", time.monotonic() + 10)
            finally:
                server.shutdown()
                thread.join()

        self.assertEqual(result["status"], "PASSED")
        self.assertEqual(paths, ["/progress/1", "/progress/1"])

    @patch.object(seed.time, "sleep")
    def test_poll_propagates_permanent_or_repeated_errors(self, _sleep):
        for code, expected_calls in ((401, 1), (503, 4)):
            with self.subTest(status=code):
                request = Mock(side_effect=HTTPError("http://gateway.test", code, "", None, None))
                with self.assertRaises(HTTPError) as raised:
                    seed.wait_for_validation(request, "/progress/1", "PASSED", time.monotonic() + 180)
                raised.exception.close()
                self.assertEqual(request.call_count, expected_calls)

    @patch.object(seed.time, "sleep")
    def test_only_an_applied_error_advances_the_validation_key(self, _sleep):
        posted_keys = []

        def request(path, body=None, key=None, *, timeout=None):
            if body is not None:
                posted_keys.append(key)
                return {"validationId": "2" if key.endswith("-retry-2") else "1"}
            if path.endswith("/1"):
                return error_states.pop(0)
            return {"status": "PASSED", "resultApplied": True, "tests": [{"verdict": "AC"}]}

        fixture = seed.GOLDEN / "p1-sum"
        for _ in range(2):
            error_states = [
                {"status": "ERROR", "resultApplied": False},
                {"status": "ERROR", "resultApplied": True},
            ]
            seed.verify_solution(request, "/revision", fixture, "sample", "ac", "PASSED", 1, 2)
            self.assertEqual(error_states, [])
        self.assertEqual(posted_keys, ["sample-ac", "sample-ac-retry-2"] * 2)


if __name__ == "__main__":
    unittest.main()
