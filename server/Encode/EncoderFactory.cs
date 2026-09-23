using Vortice.Direct3D11;

namespace DeskStreamer.Server.Encode;

public static class EncoderFactory
{
    public static IVideoEncoder Create(
        ID3D11Device device,
        int width,
        int height,
        int fps,
        int initialBitrateKbps,
        bool allowHevc,
        ID3D11Texture2D? registrationProbe = null)
    {
        string requested = Environment.GetEnvironmentVariable("DESKSTREAM_ENCODER")?
            .Trim().ToLowerInvariant() ?? "";

        if (requested is "mf" or "media-foundation" or "mediafoundation")
        {
            Console.WriteLine("[encoder] Media Foundation backend explicitly requested.");
            return new H264Encoder(device, width, height, fps, initialBitrateKbps);
        }

        // Side-by-side field comparison at 1080p60 (same host + TV): native NVENC started
        // cleanly and held 60 fps with a 2-3 ms pipeline p95, while the Media Foundation
        // path rejected candidates (E_OUTOFMEMORY / MF_E_INVALIDTYPE) and oscillated between
        // 3-59 fps. NVENC is therefore the preferred default. Media Foundation remains the
        // vendor-neutral fallback for non-NVIDIA GPUs and for any NVENC init/registration
        // failure, so a machine without NVIDIA support still streams. DESKSTREAM_ENCODER=nvenc
        // makes the preference strict (no fallback) for debugging.
        try
        {
            Console.WriteLine("[encoder] attempting native NVIDIA NVENC backend.");
            return new NvencEncoder(
                device, width, height, fps, initialBitrateKbps, allowHevc, registrationProbe);
        }
        catch (Exception ex)
        {
            if (requested == "nvenc")
                throw;
            Console.WriteLine(
                $"[encoder] NVENC unavailable ({ex.Message}); falling back to Media Foundation.");
        }

        Console.WriteLine("[encoder] using Media Foundation hardware H.264 backend.");
        return new H264Encoder(device, width, height, fps, initialBitrateKbps);
    }
}
