using LocalStream.Server.Protocol;

namespace LocalStream.Server.Session;

/// <summary>
/// Process-wide, thread-safe server options shared between the control loop (which reads the
/// defaults when a client starts a stream) and the web dashboard (which can change quality via
/// POST /api/quality). The bitrate ceiling is fixed at process startup; quality remains mutable.
/// </summary>
public sealed class ServerOptions
{
    public const int MinimumBitrateKbps = 3000;
    // LAN-only product: 50 Mbps leaves 1080p60 HEVC headroom for high-motion scenes (30 Mbps
    // still sat at QP ~34 there under the one-frame VBV); congestion adaptation cuts it on
    // links that cannot carry it.
    public const int DefaultMaxBitrateKbps = 50000;

    /// <summary>Reads the operator variable <c>LOCALSTREAM_{name}</c>.</summary>
    public static string? GetEnv(string name) =>
        Environment.GetEnvironmentVariable("LOCALSTREAM_" + name);

    private int _mediaPort = Ports.PreferredMedia;
    private int _audioPort = Ports.PreferredAudio;

    // Codec APIs express bits/second as uint. Keeping the configured kbps below this boundary
    // prevents multiplication overflow even when an unreasonable CLI value is supplied.
    private const int MaximumEncoderBitrateKbps = 4_294_967;

    private volatile string _defaultQuality = "native";
    private int _maxBitrateKbps = DefaultMaxBitrateKbps;

    /// <summary>Server default stream quality applied when a client sends no "quality" field.</summary>
    public string DefaultQuality
    {
        get => _defaultQuality;
        set => _defaultQuality = Normalize(value);
    }

    /// <summary>Preferred UDP port for the video stream (fixed; see PROTOCOL.md §2).</summary>
    public int MediaPort
    {
        get => Volatile.Read(ref _mediaPort);
        set => Volatile.Write(ref _mediaPort, ValidatePort(value, Ports.PreferredMedia));
    }

    /// <summary>Preferred UDP port for the audio stream (fixed; see PROTOCOL.md §2).</summary>
    public int AudioPort
    {
        get => Volatile.Read(ref _audioPort);
        set => Volatile.Write(ref _audioPort, ValidatePort(value, Ports.PreferredAudio));
    }

    private static int ValidatePort(int value, int fallback) =>
        value is > 0 and < 65536 ? value : fallback;

    /// <summary>Hard server ceiling applied to every client START_STREAM request.</summary>
    public int MaxBitrateKbps
    {
        get => Volatile.Read(ref _maxBitrateKbps);
        set => Volatile.Write(
            ref _maxBitrateKbps,
            Math.Clamp(value, MinimumBitrateKbps, MaximumEncoderBitrateKbps));
    }

    /// <summary>
    /// Clamps a client-advertised ceiling to both the encoder floor and the server ceiling.
    /// Missing/non-positive values retain backward-compatible behavior and use the server cap.
    /// </summary>
    public int ClampClientBitrateKbps(int requestedKbps)
    {
        int requested = requestedKbps > 0 ? requestedKbps : MaxBitrateKbps;
        return Math.Clamp(requested, MinimumBitrateKbps, MaxBitrateKbps);
    }

    /// <summary>Maps any input to one of the two allowed values; anything unrecognized is "native".</summary>
    public static string Normalize(string? quality) =>
        string.Equals(quality, "720p", StringComparison.OrdinalIgnoreCase) ? "720p" : "native";
}
