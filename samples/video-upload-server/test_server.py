import json
import pathlib
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

from server import VideoUploadHandler


def multipart_body(boundary: str, fields: dict, file_name: str, file_bytes: bytes) -> bytes:
    lines = []
    for key, value in fields.items():
        lines.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode(),
                value.encode(),
                b"\r\n",
            ]
        )
    lines.extend(
        [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{file_name}"\r\n'.encode(),
            b"Content-Type: video/mp4\r\n\r\n",
            file_bytes,
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
    )
    return b"".join(lines)


class UploadServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), VideoUploadHandler)
        self.server.upload_dir = pathlib.Path(self.temp_dir.name)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        time.sleep(0.05)

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp_dir.cleanup()

    def url(self, path: str) -> str:
        host, port = self.server.server_address
        return f"http://{host}:{port}{path}"

    def test_upload_success(self) -> None:
        boundary = "----egoflow-boundary"
        data = multipart_body(boundary, {"metadata": '{"title":"clip"}'}, "clip.mp4", b"video-data")
        request = urllib.request.Request(
            self.url("/api/videos"),
            data=data,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
            method="POST",
        )
        with urllib.request.urlopen(request) as response:
            payload = json.loads(response.read().decode())
            self.assertEqual(response.status, 200)
            self.assertEqual(payload["message"], "success")

        saved_files = list(pathlib.Path(self.temp_dir.name).glob("*.mp4"))
        self.assertEqual(len(saved_files), 1)

    def test_upload_requires_file(self) -> None:
        request = urllib.request.Request(
            self.url("/api/videos"),
            data=b"{}",
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(request)
        payload = json.loads(error.exception.read().decode())
        self.assertEqual(error.exception.code, 400)
        self.assertIn("multipart/form-data", payload["message"])


if __name__ == "__main__":
    unittest.main()
