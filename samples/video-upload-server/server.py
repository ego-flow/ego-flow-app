#!/usr/bin/env python3
import argparse
import cgi
import json
import os
import pathlib
import shutil
import sys
import tempfile
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def ensure_directory(path: pathlib.Path) -> pathlib.Path:
    path.mkdir(parents=True, exist_ok=True)
    return path


def unique_destination(upload_dir: pathlib.Path, original_name: str) -> pathlib.Path:
    sanitized = pathlib.Path(original_name).name or "upload.bin"
    stem = pathlib.Path(sanitized).stem or "upload"
    suffix = pathlib.Path(sanitized).suffix or ".bin"
    return upload_dir / f"{stem}-{uuid.uuid4().hex}{suffix}"


class VideoUploadHandler(BaseHTTPRequestHandler):
    server_version = "EgoFlowVideoUpload/1.0"

    @property
    def upload_dir(self) -> pathlib.Path:
        return self.server.upload_dir

    def do_POST(self) -> None:
        if self.path != "/api/videos":
            self.respond(HTTPStatus.NOT_FOUND, {"message": "not found"})
            return

        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self.respond(HTTPStatus.BAD_REQUEST, {"message": "multipart/form-data is required"})
            return

        try:
            form = cgi.FieldStorage(
                fp=self.rfile,
                headers=self.headers,
                environ={
                    "REQUEST_METHOD": "POST",
                    "CONTENT_TYPE": content_type,
                },
            )
        except Exception as exc:
            self.respond(HTTPStatus.BAD_REQUEST, {"message": f"invalid multipart payload: {exc}"})
            return

        file_field = form["file"] if "file" in form else None
        if file_field is None or not getattr(file_field, "file", None):
            self.respond(HTTPStatus.BAD_REQUEST, {"message": "file field is required"})
            return

        original_name = getattr(file_field, "filename", None) or "upload.bin"
        destination = unique_destination(self.upload_dir, original_name)
        metadata = form.getvalue("metadata")

        try:
            ensure_directory(self.upload_dir)
            with tempfile.NamedTemporaryFile(dir=self.upload_dir, delete=False) as temp_file:
                shutil.copyfileobj(file_field.file, temp_file)
                temp_name = pathlib.Path(temp_file.name)
            temp_name.replace(destination)

            if metadata:
                metadata_path = destination.with_suffix(destination.suffix + ".json")
                metadata_path.write_text(json.dumps({"metadata": metadata}, ensure_ascii=True, indent=2))
        except Exception as exc:
            self.respond(HTTPStatus.INTERNAL_SERVER_ERROR, {"message": f"failed to save upload: {exc}"})
            return

        self.respond(HTTPStatus.OK, {"message": "success"})

    def log_message(self, format: str, *args) -> None:
        sys.stdout.write("%s - - [%s] %s\n" % (self.address_string(), self.log_date_time_string(), format % args))

    def respond(self, status: HTTPStatus, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Minimal video upload server for EgoFlow rebuild.")
    parser.add_argument("--host", default=os.getenv("HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.getenv("PORT", "8000")))
    parser.add_argument(
        "--upload-dir",
        default=os.getenv("UPLOAD_DIR", str(pathlib.Path(__file__).with_name("uploads"))),
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()
    upload_dir = ensure_directory(pathlib.Path(args.upload_dir))
    server = ThreadingHTTPServer((args.host, args.port), VideoUploadHandler)
    server.upload_dir = upload_dir
    print(f"Serving upload API on http://{args.host}:{args.port} -> {upload_dir}")
    server.serve_forever()


if __name__ == "__main__":
    main()
