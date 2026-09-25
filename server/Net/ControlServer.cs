using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using LocalStream.Server.Logging;
using LocalStream.Server.Protocol;
using LocalStream.Server.Session;

namespace LocalStream.Server.Net;

/// <summary>
/// Control channel: TCP 47801, length-prefixed (uint32 BE) UTF-8 JSON.
/// Handles framing, keepalive (PING/PONG + 6 s dead-connection timeout), and the slot
/// rules: at most one viewer (screen output) session and one controller
/// session at a time, decided from the role field of the first HELLO. Protocol
/// semantics live in <see cref="StreamSession"/>.
/// </summary>
public sealed class ControlServer : IDisposable
{
    private static readonly TimeSpan IdleTimeout = TimeSpan.FromSeconds(6);

    /// <summary>Hard cap on concurrent sockets (incl. ones that never send HELLO), so a
    /// connect flood cannot pin a reader task per socket for the full idle timeout.</summary>
    private const int MaxConnections = 8;

    private readonly PairingManager _pairing;
    private readonly string _serverName;
    private readonly ServerOptions _options;
    private readonly TcpListener _listener;
    private CancellationTokenSource? _cts;
    private Task? _acceptLoop;
    private int _busy;
    private int _controllerBusy;
    private int _connections;
    private StreamSession? _current;

    /// <summary>The active session, if any (read by the console stats printer).</summary>
    public StreamSession? Current => Volatile.Read(ref _current);

    public ControlServer(PairingManager pairing, string serverName, ServerOptions options)
    {
        _pairing = pairing;
        _serverName = serverName;
        _options = options;
        _listener = new TcpListener(IPAddress.Any, Ports.Control);
    }

    public void Start()
    {
        _cts = new CancellationTokenSource();
        _listener.Start();
        _acceptLoop = Task.Run(() => AcceptLoopAsync(_cts.Token));
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient tcp;
            try
            {
                tcp = await _listener.AcceptTcpClientAsync(ct);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { continue; }

            // Slot claiming and rejection moved into HandleClientAsync: the deciding frame
            // (HELLO's role field) only arrives once the socket is up, and a second
            // client must receive a BUSY answer rather than a blind pre-HELLO drop.
            if (Interlocked.Increment(ref _connections) > MaxConnections)
            {
                Interlocked.Decrement(ref _connections);
                _ = RejectAsync(tcp, "too many concurrent connections");
                continue;
            }

            _ = HandleClientAsync(tcp, ct);
        }
    }

    private async Task RejectAsync(TcpClient tcp, string message)
    {
        try
        {
            using (tcp)
            {
                var stream = tcp.GetStream();
                await WriteFrameAsync(stream, OutgoingMessages.Error("BUSY", message));
            }
        }
        catch { /* ignore */ }
    }

    private async Task HandleClientAsync(TcpClient tcp, CancellationToken ct)
    {
        var remote = tcp.Client.RemoteEndPoint;
        Console.WriteLine($"[control] client connected from {remote}.");
        AsyncLogger.Info($"[control] Client connected from {remote}");

        tcp.NoDelay = true;
        var stream = tcp.GetStream();
        var sendGate = new object();

        void Send(object message)
        {
            lock (sendGate)
            {
                try { WriteFrameSync(stream, message); } catch { /* socket dying; loop will end */ }
            }
        }

        bool claimedViewer = false;
        bool claimedController = false;
        StreamSession? session = null;
        try
        {
            // The role is declared in the first frame (HELLO's optional "role").
            // Deciding the slot here — after that frame instead of at accept time — is what
            // lets a second device receive BUSY and then reconnect as the controller.
            byte[]? first = await ReadFrameAsync(stream, ct);
            if (first == null)
                return; // peer closed, or idle-timeout before HELLO

            bool isController = IsControllerHello(first);
            if (isController)
            {
                if (Interlocked.CompareExchange(ref _controllerBusy, 1, 0) == 1)
                {
                    await WriteFrameAsync(stream, OutgoingMessages.Error("BUSY", "a controller is already connected"));
                    return;
                }
                claimedController = true;
            }
            else
            {
                if (Interlocked.CompareExchange(ref _busy, 1, 0) == 1)
                {
                    // Screen output stays single-client; the Android app turns this code
                    // into the role-selection dialog instead of a dead end.
                    await WriteFrameAsync(stream, OutgoingMessages.Error("BUSY", "screen output is already in use by another client"));
                    return;
                }
                claimedViewer = true;
            }

            var clientAddress = (remote as IPEndPoint)?.Address;
            var active = new StreamSession(
                Send,
                () => { try { tcp.Close(); } catch { } },
                _pairing,
                _serverName,
                clientAddress,
                _options,
                isController);
            session = active;
            if (!isController)
                Volatile.Write(ref _current, active);
            string role = isController ? "controller" : "viewer";
            Console.WriteLine($"[control] {role} session established for {remote}.");
            AsyncLogger.Info($"[control] {role} session established for {remote}");

            bool HandleFrame(byte[] frame)
            {
                string? type = ReadType(frame);
                if (type == null)
                    return false; // Malformed frame: either side closes the socket.

                if (type == "PING")
                {
                    long t1Us = MonotonicClock.NowUs;
                    long? t0Us = ReadOptionalInt64(frame, "t0Us");
                    Send(OutgoingMessages.Pong(t0Us, t0Us.HasValue ? t1Us : null,
                        t0Us.HasValue ? MonotonicClock.NowUs : null));
                    return true;
                }

                active.HandleMessage(type, frame);
                return true;
            }

            if (!HandleFrame(first))
                return;

            while (!ct.IsCancellationRequested)
            {
                byte[]? frame = await ReadFrameAsync(stream, ct);
                if (frame == null)
                    break; // clean close or timeout
                if (!HandleFrame(frame))
                    break;
            }
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[control] connection error: {ex.Message}");
            AsyncLogger.Error($"[control] Connection error for {remote}: {ex.Message}");
        }
        finally
        {
            if (claimedViewer)
                Volatile.Write(ref _current, null);
            session?.Dispose();
            if (claimedViewer)
                Interlocked.Exchange(ref _busy, 0);
            if (claimedController)
                Interlocked.Exchange(ref _controllerBusy, 0);
            Interlocked.Decrement(ref _connections);
            try { tcp.Close(); } catch { }
            Console.WriteLine($"[control] client {remote} disconnected.");
            AsyncLogger.Info($"[control] Client {remote} disconnected");
        }
    }

    // ---- First-frame role ----------------------------------------------

    /// <summary>True only when the first frame is a HELLO that explicitly declares
    /// <c>role:"controller"</c>. Anything else — including every pre-v0.8 client that
    /// omits the field — is a viewer, preserving old single-client behavior.</summary>
    private static bool IsControllerHello(byte[] frame)
    {
        try
        {
            using var doc = JsonDocument.Parse(frame);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object ||
                !root.TryGetProperty("type", out var type) ||
                type.ValueKind != JsonValueKind.String ||
                !string.Equals(type.GetString(), "HELLO", StringComparison.Ordinal))
                return false;
            return root.TryGetProperty("role", out var role) &&
                   role.ValueKind == JsonValueKind.String &&
                   string.Equals(role.GetString(), "controller", StringComparison.OrdinalIgnoreCase);
        }
        catch (JsonException)
        {
            return false;
        }
    }

    // ---- Framing --------------------------------------------------------------------------

    private static string? ReadType(byte[] frame)
    {
        try
        {
            using var doc = JsonDocument.Parse(frame);
            if (doc.RootElement.TryGetProperty("type", out var t) && t.ValueKind == JsonValueKind.String)
                return t.GetString();
        }
        catch (JsonException) { }
        return null;
    }

    private static long? ReadOptionalInt64(byte[] frame, string name)
    {
        try
        {
            using var doc = JsonDocument.Parse(frame);
            if (doc.RootElement.TryGetProperty(name, out var value) && value.TryGetInt64(out long result))
                return result;
        }
        catch (JsonException) { }
        return null;
    }

    private static async Task<byte[]?> ReadFrameAsync(NetworkStream stream, CancellationToken ct)
    {
        var lenBuf = new byte[4];
        if (!await ReadExactAsync(stream, lenBuf, 4, ct))
            return null;

        uint len = BinaryPrimitives.ReadUInt32BigEndian(lenBuf);
        if (len == 0 || len > ProtocolConstants.MaxControlFrame)
            return null; // malformed

        var payload = new byte[len];
        if (!await ReadExactAsync(stream, payload, (int)len, ct))
            return null;

        return payload;
    }

    /// <summary>
    /// Reads exactly <paramref name="count"/> bytes, honoring the 6 s idle timeout. Returns
    /// false on EOF, timeout, or cancellation.
    /// </summary>
    private static async Task<bool> ReadExactAsync(NetworkStream stream, byte[] buffer, int count, CancellationToken ct)
    {
        int read = 0;
        while (read < count)
        {
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(IdleTimeout);
            try
            {
                int n = await stream.ReadAsync(buffer.AsMemory(read, count - read), timeoutCts.Token);
                if (n == 0)
                    return false; // peer closed
                read += n;
            }
            catch (OperationCanceledException)
            {
                return false; // idle timeout or shutdown
            }
            catch (IOException)
            {
                return false;
            }
        }
        return true;
    }

    private static void WriteFrameSync(NetworkStream stream, object message)
    {
        byte[] json = Json.SerializeToUtf8(message);
        Span<byte> header = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(header, (uint)json.Length);
        stream.Write(header);
        stream.Write(json);
        stream.Flush();
    }

    private static async Task WriteFrameAsync(NetworkStream stream, object message)
    {
        byte[] json = Json.SerializeToUtf8(message);
        var header = new byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(header, (uint)json.Length);
        await stream.WriteAsync(header);
        await stream.WriteAsync(json);
        await stream.FlushAsync();
    }

    public void Dispose()
    {
        try { _cts?.Cancel(); } catch { }
        try { _listener.Stop(); } catch { }
        try { _acceptLoop?.Wait(1000); } catch { }
        _cts?.Dispose();
    }
}
