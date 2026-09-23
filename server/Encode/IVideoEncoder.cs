using Vortice.Direct3D11;

namespace DeskStreamer.Server.Encode;

/// <summary>Common surface-to-bitstream contract for native and Media Foundation encoders.</summary>
public interface IVideoEncoder : IDisposable
{
    /// <summary>Called with a reused buffer. Consumers must finish reading before returning.</summary>
    Action<byte[], int, bool, uint>? OnEncodedFrame { get; set; }

    string BackendName { get; }

    /// <summary>Wire codec name for STREAM_STARTED: "h264" or "hevc" (Annex-B either way).</summary>
    string Codec { get; }

    /// <summary>True when <see cref="RequestRefresh"/> heals a loss with an intra-refresh wave
    /// instead of an IDR, so the client may keep decoding across a lost frame.</summary>
    bool SupportsRefreshRecovery { get; }

    void Submit(ID3D11Texture2D nv12, uint ptsMs);

    void RequestIdr();

    /// <summary>Loss repair without a keyframe: one gradual intra-refresh wave. Encoders that
    /// cannot refresh fall back to an IDR.</summary>
    void RequestRefresh();

    /// <summary>Attempts a live bitrate change. Returns true only when the encoder accepts it.</summary>
    bool SetBitrate(int kbps);
}
