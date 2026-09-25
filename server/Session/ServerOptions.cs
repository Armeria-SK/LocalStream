namespace LocalStream.Server.Session;

/// <summary>
/// Process-wide server options, fixed at startup and read by every control session when a
/// client starts a stream.
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

    // Codec APIs express bits/second as uint. Keeping the configured kbps below this boundary
    // prevents multiplication overflow even when an unreasonable CLI value is supplied.
    private const int MaximumEncoderBitrateKbps = 4_294_967;

    private int _maxBitrateKbps = DefaultMaxBitrateKbps;

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
}
