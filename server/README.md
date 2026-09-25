# LocalStream.Server

The Windows half of **LocalStream**, a low-latency LAN screen streamer. It captures the
primary display with DXGI Desktop Duplication, converts BGRA→NV12 on the GPU, hardware-
encodes HEVC (when the client can decode it) or H.264 through native NVIDIA NVENC when
available (falling back automatically to the Windows Media Foundation H.264 hardware path on
other GPUs), and streams it to LAN clients. It also captures the default Windows playback
device through WASAPI loopback and streams normalized 48 kHz stereo PCM in fixed 5 ms blocks.
Authenticated clients can also forward mouse, physical keyboard, and game-controller input
to the interactive Windows desktop.

## Requirements

- Windows 10/11 (x64) with NVIDIA NVENC, AMD AMF, or Intel QuickSync hardware H.264.
  Native NVENC is preferred when an NVIDIA GPU is present (it held a steady 60 fps in
  side-by-side field comparisons); Media Foundation is the automatic vendor-neutral
  fallback. There is **no CPU encoder**; if hardware setup
  fails, the server keeps the client connection open and reports the stream error.
- [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0).
- Optional for controller forwarding: the
  [ViGEmBus 1.22 driver](https://github.com/nefarius/ViGEmBus/releases/latest). Without it,
  the server reports controller support as unavailable while video/audio continue.

## Build & run

```powershell
cd server
dotnet run -c Release
```

On start it prints the local IP addresses and ports (video/audio UDP are fixed at
47802/47803), then waits for a client. When a new device pairs, a **6-digit PIN** is shown in
a box — type it into the Android app. Once streaming, a 1 Hz status line reports encoded fps,
current bitrate, client-side dropped frames, IDR requests per second, and the active audio
payload rate. All ports are bound at startup with a short retry so restarts are
deterministic; a busy video/audio port falls back to an ephemeral one with a warning on stderr.

Paired devices are remembered in `paired_clients.json` next to the built executable, so
subsequent connections auto-authenticate (TOFU). Delete that file to force re-pairing.

### Runtime options

- `--max-bitrate-kbps N` sets a hard encoder-target ceiling for every client. The default is
  50,000 kbps (tuned for games/movies on a LAN; sessions start at 16,000 kbps and probe up) and the
  minimum is 3,000. On congested Wi-Fi, `--max-bitrate-kbps 12000` is a
  useful 1080p60 starting point; this ceiling excludes XOR-FEC, packet headers, and PCM audio.
- `--headless` writes lifecycle, pairing (including the PIN), and error output to
  `localstream.log` beside the executable. It replaces the 1 Hz stats line with a 10 s summary
  in `localstream.app.log` and keeps only `localstream.log` plus `localstream.previous.log`
  across restarts.
- `--install-autostart` (run from the published `.exe`, not `dotnet run`) creates an interactive,
  per-user logon Scheduled Task that starts the server with `--headless` at normal privileges.
  `--uninstall-autostart` removes the task. This is deliberately not a session-0 Windows
  service, because session 0 cannot capture your desktop.

To pin the encoder backend explicitly, launch from PowerShell with:

```powershell
# Strict NVENC (fail instead of falling back to Media Foundation):
$env:LOCALSTREAM_ENCODER = "nvenc"
# Or force Media Foundation even on NVIDIA machines:
$env:LOCALSTREAM_ENCODER = "mf"
# Keep H.264 even for HEVC-capable clients:
$env:LOCALSTREAM_CODEC = "h264"
.\LocalStream.Server.exe
```

## Firewall

Allow these on the **Private** network profile (the first run usually triggers a Windows
Defender Firewall prompt — approve it for Private networks):

- **UDP 47800** — discovery
- **TCP 47801** — control
- **UDP 47802** — media (server → client; also receives the client's `DSMH` hole-punch)
- **UDP 47803** — audio (server → client; also receives the client's `DSAH` hole-punch)

```powershell
# Optional explicit rules (run in an elevated PowerShell):
New-NetFirewallRule -DisplayName "LocalStream discovery" -Direction Inbound -Protocol UDP -LocalPort 47800 -Profile Private -Action Allow
New-NetFirewallRule -DisplayName "LocalStream control"   -Direction Inbound -Protocol TCP -LocalPort 47801 -Profile Private -Action Allow
New-NetFirewallRule -DisplayName "LocalStream media"     -Direction Inbound -Protocol UDP -LocalPort 47802 -Profile Private -Action Allow
New-NetFirewallRule -DisplayName "LocalStream audio"     -Direction Inbound -Protocol UDP -LocalPort 47803 -Profile Private -Action Allow
```

## Architecture

Source layout:

| Path | Responsibility |
|------|----------------|
| `Program.cs` | Wire-up, console UX, GC latency mode, 1 Hz stats |
| `Capture/DesktopDuplicator.cs` | DXGI Output Duplication + shared D3D11 device |
| `Capture/Nv12Converter.cs` | GPU BGRA→NV12 via `ID3D11VideoProcessor` |
| `Encode/H264Encoder.cs` | Async hardware H.264 MFT (D3D-managed input, CODECAPI controls) |
| `Encode/NvencEncoder.cs` | Native NVENC ULL (HEVC → H.264 fallback), single-pass CBR, zero reorder, one-frame VBV, on-demand intra-refresh loss recovery |
| `Encode/EncoderFactory.cs` | Backend selection: NVENC first, Media Foundation fallback |
| `Encode/MfGuids.cs` / `Encode/NalUtil.cs` | MF/CODECAPI GUIDs; Annex-B NAL scanning |
| `Net/DiscoveryResponder.cs` | UDP 47800 `DSPROBE1` → `DSREPLY` |
| `Net/ControlServer.cs` | TCP 47801 length-prefixed JSON, keepalive, viewer/controller slots |
| `Net/MediaSender.cs` | Packetizer (20-byte header, ≤1200 B) + XOR FEC + UDP send |
| `Service/Autostart.cs` | Interactive per-user logon Scheduled Task management |
| `Audio/SystemAudioCapture.cs` | Low-latency WASAPI system-output loopback, normalized PCM |
| `Net/AudioSender.cs` | `DSAH` address learning + fixed 5 ms audio packetizer/UDP send |
| `Input/VirtualGamepadManager.cs` | Up to four ViGEm-backed virtual Xbox 360 controllers |
| `Input/RemoteMouseManager.cs` | Authenticated `SendInput` mouse motion/buttons with safe reset |
| `Input/RemoteKeyboardManager.cs` | Ordered USB HID keyboard usages → `SendInput` scan codes, `KEYEVENTF_UNICODE` text bursts, safe reset |
| `Session/StreamSession.cs` | Control state machine + adaptation controller |
| `Session/PairingManager.cs` | TOFU PIN pairing, `paired_clients.json` persistence |
| `Protocol/*.cs` | Wire DTOs and big-endian media header helpers |

## Notes

- The hot path (packetize → send) is allocation-free; GC runs in `SustainedLowLatency`.
- Every pipeline stage holds at most one frame; a stale capture is dropped when the encoder
  is busy (newest-wins), never queued.
- `EnableWindowsTargeting` is set so the project compiles on non-Windows CI, but it only
  **runs** on Windows.
- Windows UIPI blocks a normal process from injecting into an elevated game. Run the server
  at the same integrity level as the target app when remote mouse or keyboard input is needed
  there.
