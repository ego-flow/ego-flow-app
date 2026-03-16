# EgoFlow Video Upload Server

1차 개편용 최소 업로드 서버다. `samples/video-ingest-server`를 대체하지 않으며, 기존 MediaMTX 스택은 reference 상태로 유지된다.

## API

- `POST /api/videos`
- `multipart/form-data`
- 필수 필드: `file`
- 선택 필드: `metadata`

성공 응답:

```json
{"message":"success"}
```

실패 응답:

```json
{"message":"<reason>"}
```

## 실행

```bash
cd samples/video-upload-server
python3 server.py --host 0.0.0.0 --port 8000
```

파일은 기본적으로 `samples/video-upload-server/uploads` 아래에 저장된다.

## 테스트

```bash
cd samples/video-upload-server
python3 -m unittest test_server.py
```
