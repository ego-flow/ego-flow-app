# Video Ingest Server

LAN RTMP ingest server for the Android app.

Responsibilities:

- receive live video frames from the app over RTMP
- make the stream viewable in a browser over HLS
- automatically save recordings on the server PC

## Layout

- `docker-compose.yml`: starts the ingest stack
- `mediamtx.yml`: RTMP ingest, HLS output, automatic recording
- `viewer/index.html`: simple browser player
- `recordings/`: saved video files on this PC

## Requirements

- Docker
- Docker Compose
- ngrok

## Start

```bash
docker compose up -d
```

## Stop

```bash
docker compose down
```

## App publish URL

Set the Android app RTMP publish URL to:

```text
rtmp://<SERVER_IP>:1935/live/glasses
```

`<SERVER_IP>` must be the LAN IP of this PC, for example `192.168.0.10`.

## Browser viewer

Open:

```text
http://<SERVER_IP>:8088
```

The page plays the default stream from:

```text
http://<SERVER_IP>:8888/live/glasses/index.m3u8
```

## Recording location

Recordings are saved on this PC under:

```text
./recordings/live/glasses/
```

The files are created by MediaMTX automatically as the stream is received.

## Logs

```bash
docker compose logs -f
```

## ngrok access

If the phone cannot reach this PC directly, expose the server through ngrok.

### Start ngrok

```bash
./start-ngrok.sh
```

If ngrok exits with `ERR_NGROK_4018`, your account is not authenticated on this
machine yet. Configure it once before starting tunnels:

```bash
ngrok config add-authtoken <YOUR_AUTHTOKEN>
```

Get the token from:

```text
https://dashboard.ngrok.com/get-started/your-authtoken
```

The script loads both:

- your default ngrok config at `~/.config/ngrok/ngrok.yml`
- this project's tunnel config at `./ngrok.yml`

If you run the script with `sudo` or as another user, that other account needs
its own ngrok config and authtoken too.

This opens three tunnels:

- TCP tunnel for RTMP publish on local port `1935`
- HTTP tunnel for the viewer on local port `8088`
- HTTP tunnel for raw HLS on local port `8888`

### Inspect tunnel URLs

In another terminal:

```bash
./print-ngrok-urls.sh
```

Or open:

```text
http://127.0.0.1:4040
```

### App publish URL through ngrok

Find the public TCP tunnel for `video-ingest-rtmp`.
It will look like:

```text
tcp://0.tcp.ngrok.io:12345
```

Then set the Android app RTMP publish URL to:

```text
rtmp://0.tcp.ngrok.io:12345/live/glasses
```

### Browser access through ngrok

Use the public HTTPS URL for:

- `video-ingest-viewer` to open the viewer page
- `video-ingest-hls` if you want the raw HLS endpoint directly

### Notes

- The ingest server itself still runs on this PC.
- ngrok only exposes the local ports to the internet.
- Recording files are still saved locally under `./recordings/live/glasses/`.
