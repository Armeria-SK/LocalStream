using System.Runtime.InteropServices;
using Lennox.NvEncSharp;
using LocalStream.Server.Logging;
using Vortice.Direct3D11;
using static Lennox.NvEncSharp.LibNvEnc;

namespace LocalStream.Server.Encode;

/// <summary>
/// Native NVIDIA NVENC backend (HEVC or H.264). Encoding is synchronous and has exactly one
/// output buffer, which deliberately prevents the application from building a hidden frame
/// queue. Raw NV12 textures stay on the shared D3D11 device for the entire path.
/// </summary>
public sealed class NvencEncoder : IVideoEncoder
{
    private sealed class RegisteredTexture : IDisposable
    {
        public readonly NvEncRegisterResource Registration;
        private readonly NvEncoder.NvEncRegisteredResource _lease;

        public RegisteredTexture(
            NvEncRegisterResource registration,
            NvEncoder.NvEncRegisteredResource lease)
        {
            Registration = registration;
            _lease = lease;
        }

        public void Dispose() => _lease.Dispose();
    }

    private readonly object _gate = new();
    private readonly int _width;
    private readonly int _height;
    private readonly int _fps;
    private readonly Dictionary<IntPtr, RegisteredTexture> _textures = new();

    private readonly IntPtr _device;
    private NvEncoder _encoder;
    private NvEncConfig _config;
    private Guid _presetGuid = NvEncPresetGuids.P5;
    private Guid _codecGuid = NvEncCodecGuids.H264;
    private bool _hevc;
    private bool _temporalAq;
    private bool _intraRefresh;
    private bool _singleSliceRefresh;
    private uint _intraRefreshFrames;
    private bool _sessionAttempted;
    private NvEncCreateBitstreamBuffer _bitstream;
    private byte[] _output = new byte[1 << 20];
    private uint _frameIndex;
    private bool _forceIdr = true;
    private bool _forceRefresh;
    private bool _initialized;
    private bool _disposed;

    public Action<byte[], int, bool, uint>? OnEncodedFrame { get; set; }
    public string BackendName => "nvenc-ultra-low-latency";
    public string Codec => _hevc ? "hevc" : "h264";
    public bool SupportsRefreshRecovery => _intraRefresh;

    /// <param name="allowHevc">Try HEVC Main first; H.264 High remains the fallback so a GPU
    /// without HEVC NVENC (or a driver that rejects it) still streams.</param>
    public NvencEncoder(
        ID3D11Device device,
        int width,
        int height,
        int fps,
        int initialBitrateKbps,
        bool allowHevc,
        ID3D11Texture2D? registrationProbe = null)
    {
        _width = width;
        _height = height;
        _fps = Math.Max(1, fps);

        var status = TryInitialize(out string? reason);
        if (status != LibNcEncInitializeStatus.Success)
            throw new EncoderUnavailableException(
                "NVIDIA NVENC API could not be initialized: " + (reason ?? status.ToString()));

        try
        {
            _device = device.NativePointer;
            _encoder = OpenEncoderForDirectX(device.NativePointer);

            // Quality ladder: HEVC before H.264 (~30-40% less bitrate for the same detail),
            // P5 + Temporal AQ first (better detail at the same bitrate), intra-refresh loss
            // recovery before plain IDR recovery, then drop features step by step so an older
            // GPU/driver still starts. The final rung is exactly the previous H.264 P3
            // configuration (known working).
            bool started = false;
            foreach (bool hevc in allowHevc ? new[] { true, false } : new[] { false })
            {
                started =
                    TryStartEncoder(hevc, NvEncPresetGuids.P5, temporalAq: true, intraRefresh: true, initialBitrateKbps) ||
                    TryStartEncoder(hevc, NvEncPresetGuids.P5, temporalAq: false, intraRefresh: true, initialBitrateKbps) ||
                    TryStartEncoder(hevc, NvEncPresetGuids.P5, temporalAq: false, intraRefresh: false, initialBitrateKbps) ||
                    TryStartEncoder(hevc, NvEncPresetGuids.P3, temporalAq: false, intraRefresh: false, initialBitrateKbps);
                if (started)
                    break;
            }
            if (!started)
            {
                throw new EncoderUnavailableException(
                    "NVENC rejected every encoder configuration (HEVC/H.264 x P5+TemporalAQ, P5, P3).");
            }
            string startedLine =
                $"[encoder] NVENC {Codec} started (temporal AQ {(_temporalAq ? "on" : "off")}, " +
                $"loss recovery {(_intraRefresh ? $"intra refresh over {_intraRefreshFrames} frames" : "IDR")}" +
                $"{(allowHevc && !_hevc ? "; HEVC was offered but NVENC rejected it" : "")}).";
            Console.WriteLine(startedLine);
            AsyncLogger.Info(startedLine);

            _bitstream = _encoder.CreateBitstreamBuffer();

            // Prove the operation this whole backend depends on -- registering a live D3D11
            // NV12 surface with NVENC -- while EncoderFactory can still fall back to Media
            // Foundation. Without this probe, an exotic driver refusing registration would
            // only surface mid-stream as a capture-loop fault. The registration is cached in
            // _textures and reused (map/unmap per frame) when that pool texture is submitted.
            if (registrationProbe != null)
                GetOrRegister(registrationProbe);

            _initialized = true;
        }
        catch
        {
            Dispose();
            throw;
        }
    }

    /// <summary>
    /// One rung of the encoder quality ladder: configure one preset/quality combination on
    /// a clean NVENC session. Any NVENC error (unsupported preset GUID, Temporal AQ
    /// rejected by the driver, initialize failure) reports false so the next rung runs.
    /// </summary>
    private bool TryStartEncoder(bool hevc, Guid preset, bool temporalAq, bool intraRefresh, int bitrateKbps)
    {
        try
        {
            if (_sessionAttempted)
            {
                // A failed attempt can leave the session half-initialized — start clean.
                try { _encoder.DestroyEncoder(); } catch { }
                _encoder = OpenEncoderForDirectX(_device);
            }
            _sessionAttempted = true;

            _hevc = hevc;
            _codecGuid = hevc ? NvEncCodecGuids.Hevc : NvEncCodecGuids.H264;
            _presetGuid = preset;
            _temporalAq = temporalAq && IsCapSupported(NvEncCaps.SupportTemporalAq);
            _intraRefresh = intraRefresh && IsCapSupported(NvEncCaps.SupportIntraRefresh);
            _singleSliceRefresh = _intraRefresh && IsCapSupported(NvEncCaps.SingleSliceIntraRefresh);
            // One refresh wave sweeps the picture in ~1/2 s, in a single slice. Measured on
            // this NVENC at 1080p60 HEVC 30 Mbps with the one-frame VBV: a 10-frame multi-slice
            // wave burst to 176 KB frames (2.7x the per-frame budget) and pushed QP to 34 for
            // ~200 ms of visible blocking; 30 single-slice frames peaked at 98 KB / QP 19 on
            // desktop content and stayed within budget (59 KB, QP 36 vs 41) on high motion.
            _intraRefreshFrames = (uint)Math.Clamp(_fps / 2, 8, 60);
            _config = _encoder.GetEncodePresetConfigEx(
                _codecGuid,
                _presetGuid,
                NvEncTuningInfo.UltraLowLatency).PresetCfg;

            Configure(bitrateKbps);
            InitializeEncoder();
            return true;
        }
        catch
        {
            return false;
        }
    }

    private bool IsCapSupported(NvEncCaps cap)
    {
        try
        {
            var caps = new NvEncCapsParam { CapsToQuery = cap };
            int supported = 0;
            _encoder.GetEncodeCaps(_codecGuid, ref caps, ref supported);
            return supported != 0;
        }
        catch
        {
            return false;
        }
    }

    private void Configure(int bitrateKbps)
    {
        uint bitrate = checked((uint)Math.Max(500, bitrateKbps) * 1000u);
        uint oneFrameVbv = Math.Max(32_000u, bitrate / (uint)_fps);

        _config.Version = NV_ENC_CONFIG_VER;
        _config.ProfileGuid = _hevc ? NvEncProfileGuids.HevcMain : NvEncProfileGuids.H264High;
        _config.GopLength = uint.MaxValue;
        _config.FrameIntervalP = 1;
        _config.FrameFieldMode = NvEncParamsFrameFieldMode.Frame;
        _config.MvPrecision = NvEncMvPrecision.QuarterPel;

        var rc = _config.RcParams;
        rc.Version = NV_ENC_RC_PARAMS_VER;
        rc.RateControlMode = NvEncParamsRcMode.Cbr;
        rc.AverageBitRate = bitrate;
        rc.MaxBitRate = bitrate;
        rc.VbvBufferSize = oneFrameVbv;
        rc.VbvInitialDelay = oneFrameVbv;
        rc.EnableLookahead = false;
        rc.EnableTemporalAQ = _temporalAq;
        rc.ZeroReorderDelay = true;
        rc.MultiPass = NvEncMultiPass.Disabled;
        rc.LowDelayKeyFrameScale = 1;
        _config.RcParams = rc;

        // Intra refresh is on-demand only (REQUEST_REFRESH): the periodic
        // interval is effectively infinite and Submit starts one wave per reported loss via
        // ForceIntraRefreshWithFrameCnt, so steady-state quality is unchanged.
        const uint OnDemandOnlyPeriod = 1u << 30;

        if (_hevc)
        {
            var hevc = _config.EncodeCodecConfig.HevcConfig;
            hevc.DisableSPSPPS = false;
            hevc.RepeatSPSPPS = true;
            hevc.IdrPeriod = uint.MaxValue;
            hevc.MaxNumRefFramesInDPB = 1;
            hevc.ChromaFormatIDC = 1;
            hevc.SliceMode = 0;
            hevc.SliceModeData = 0;
            hevc.EnableIntraRefresh = _intraRefresh;
            hevc.SingleSliceIntraRefresh = _singleSliceRefresh;
            hevc.OutputRecoveryPointSEI = _intraRefresh;
            hevc.IntraRefreshPeriod = _intraRefresh ? OnDemandOnlyPeriod : 0;
            hevc.IntraRefreshCnt = _intraRefresh ? _intraRefreshFrames : 0;
            hevc.HevcVUIParameters = ConfigureVui(hevc.HevcVUIParameters);
            _config.EncodeCodecConfig.HevcConfig = hevc;
        }
        else
        {
            var h264 = _config.EncodeCodecConfig.H264Config;
            h264.DisableSPSPPS = false;
            h264.RepeatSPSPPS = true;
            h264.IdrPeriod = uint.MaxValue;
            h264.MaxNumRefFrames = 1;
            h264.ChromaFormatIDC = 1;
            h264.SliceMode = 0;
            h264.SliceModeData = 0;
            h264.EnableIntraRefresh = _intraRefresh;
            h264.SingleSliceIntraRefresh = _singleSliceRefresh;
            h264.OutputRecoveryPointSEI = _intraRefresh;
            h264.IntraRefreshPeriod = _intraRefresh ? OnDemandOnlyPeriod : 0;
            h264.IntraRefreshCnt = _intraRefresh ? _intraRefreshFrames : 0;
            h264.H264VUIParameters = ConfigureVui(h264.H264VUIParameters);
            _config.EncodeCodecConfig.H264Config = h264;
        }
    }

    /// <summary>
    /// Explicit color signaling: BT.709 primaries/transfer/matrix, limited range 16-235. MUST match
    /// Nv12Converter's VideoProcessorSetOutputColorSpace — otherwise players guess (commonly
    /// BT.601 limited) and desktop/game colors visibly shift.
    ///
    /// BitstreamRestrictionFlag makes NVENC write bitstream_restriction (zero reorder frames,
    /// a one-picture DPB). Without it many Android TV decoders assume the level's worst-case
    /// reorder depth and hold several decoded pictures before output — the 100-160 ms
    /// decode-to-surface floor seen in field logs.
    /// </summary>
    private static NvEncConfigH264VuiParameters ConfigureVui(NvEncConfigH264VuiParameters vui)
    {
        vui.VideoSignalTypePresentFlag = 1;
        vui.VideoFormat = NvEncVuiVideoFormat.Unspecified;
        vui.VideoFullRangeFlag = 0; // limited 16-235, matching Nv12Converter
        vui.ColourDescriptionPresentFlag = 1;
        vui.ColourPrimaries = NvEncVuiColorPrimaries.Bt709;
        vui.TransferCharacteristics = NvEncVuiTransferCharacteristic.Bt709;
        vui.ColourMatrix = NvEncVuiMatrixCoeffs.Bt709;
        vui.BitstreamRestrictionFlag = 1;
        return vui;
    }

    private unsafe void InitializeEncoder()
    {
        fixed (NvEncConfig* config = &_config)
        {
            var init = CreateInitializeParams(config);
            _encoder.InitializeEncoder(ref init);
        }
    }

    private unsafe NvEncInitializeParams CreateInitializeParams(NvEncConfig* config) => new()
    {
        Version = NV_ENC_INITIALIZE_PARAMS_VER,
        EncodeGuid = _codecGuid,
        PresetGuid = _presetGuid,
        EncodeWidth = (uint)_width,
        EncodeHeight = (uint)_height,
        MaxEncodeWidth = (uint)_width,
        MaxEncodeHeight = (uint)_height,
        DarWidth = (uint)_width,
        DarHeight = (uint)_height,
        FrameRateNum = (uint)_fps,
        FrameRateDen = 1,
        EnableEncodeAsync = 0,
        EnablePTD = 1,
        ReportSliceOffsets = false,
        EnableSubFrameWrite = false,
        EnableWeightedPrediction = false,
        EncodeConfig = config,
        TuningInfo = NvEncTuningInfo.UltraLowLatency,
    };

    public void Submit(ID3D11Texture2D nv12, uint ptsMs)
    {
        lock (_gate)
        {
            ThrowIfDisposed();
            RegisteredTexture registered = GetOrRegister(nv12);
            var mapped = new NvEncMapInputResource
            {
                Version = NV_ENC_MAP_INPUT_RESOURCE_VER,
                RegisteredResource = registered.Registration.RegisteredResource,
            };

            bool isMapped = false;
            try
            {
                _encoder.MapInputResource(ref mapped);
                isMapped = true;

                bool forceIdr = _forceIdr;
                _forceIdr = false;
                // An IDR already resets every reference, so it supersedes a pending wave.
                bool forceRefresh = _forceRefresh && !forceIdr;
                _forceRefresh = false;
                var picture = new NvEncPicParams
                {
                    Version = NV_ENC_PIC_PARAMS_VER,
                    InputWidth = (uint)_width,
                    InputHeight = (uint)_height,
                    FrameIdx = _frameIndex++,
                    InputTimeStamp = ptsMs,
                    InputDuration = (ulong)Math.Max(1, 1000 / _fps),
                    InputBuffer = mapped.MappedResource,
                    OutputBitstream = _bitstream.BitstreamBuffer,
                    BufferFmt = mapped.MappedBufferFmt,
                    PictureStruct = NvEncPicStruct.Frame,
                    EncodePicFlags = forceIdr
                        ? (uint)(NvEncPicFlags.FlagForceidr | NvEncPicFlags.FlagOutputSpspps)
                        : 0,
                };
                if (forceRefresh)
                {
                    var codecParams = picture.CodecPicParams;
                    if (_hevc)
                    {
                        var hevcParams = codecParams.HevcPicParams;
                        hevcParams.ForceIntraRefreshWithFrameCnt = _intraRefreshFrames;
                        codecParams.HevcPicParams = hevcParams;
                    }
                    else
                    {
                        var h264Params = codecParams.H264PicParams;
                        h264Params.ForceIntraRefreshWithFrameCnt = _intraRefreshFrames;
                        codecParams.H264PicParams = h264Params;
                    }
                    picture.CodecPicParams = codecParams;
                }

                _encoder.EncodePicture(ref picture);
                var locked = _encoder.LockBitstream(ref _bitstream);
                try
                {
                    int length = checked((int)locked.BitstreamSizeInBytes);
                    EnsureCapacity(length);
                    Marshal.Copy(locked.BitstreamBufferPtr, _output, 0, length);
                    bool keyframe = locked.PictureType == NvEncPicType.Idr;
                    OnEncodedFrame?.Invoke(_output, length, keyframe, ptsMs);
                }
                finally
                {
                    _encoder.UnlockBitstream(_bitstream.BitstreamBuffer);
                }
            }
            finally
            {
                if (isMapped)
                    _encoder.UnmapInputResource(mapped.MappedResource);
            }
        }
    }

    private RegisteredTexture GetOrRegister(ID3D11Texture2D texture)
    {
        IntPtr key = texture.NativePointer;
        if (_textures.TryGetValue(key, out RegisteredTexture? existing))
            return existing;

        var registration = new NvEncRegisterResource
        {
            Version = NV_ENC_REGISTER_RESOURCE_VER,
            ResourceType = NvEncInputResourceType.Directx,
            Width = (uint)_width,
            Height = (uint)_height,
            Pitch = 0,
            ResourceToRegister = key,
            BufferFormat = NvEncBufferFormat.Nv12,
            BufferUsage = NvEncBufferUsage.NvEncInputImage,
        };
        NvEncoder.NvEncRegisteredResource lease = _encoder.RegisterResource(ref registration);
        var created = new RegisteredTexture(registration, lease);
        _textures.Add(key, created);
        return created;
    }

    public void RequestRefresh()
    {
        lock (_gate)
        {
            if (_disposed)
                return;
            if (_intraRefresh)
                _forceRefresh = true;
            else
                _forceIdr = true;
        }
    }

    public void RequestIdr()
    {
        lock (_gate)
        {
            if (!_disposed)
                _forceIdr = true;
        }
    }

    public unsafe bool SetBitrate(int kbps)
    {
        lock (_gate)
        {
            ThrowIfDisposed();
            Configure(kbps);
            fixed (NvEncConfig* config = &_config)
            {
                var reconfigure = new NvEncReconfigureParams
                {
                    Version = NV_ENC_RECONFIGURE_PARAMS_VER,
                    ReInitEncodeParams = CreateInitializeParams(config),
                    ResetEncoder = false,
                    ForceIDR = false,
                };
                _encoder.ReconfigureEncoder(ref reconfigure);
            }
            return true;
        }
    }

    private void EnsureCapacity(int length)
    {
        if (_output.Length < length)
            Array.Resize(ref _output, Math.Max(length, _output.Length * 2));
    }

    private void ThrowIfDisposed()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed)
                return;
            _disposed = true;

            foreach (RegisteredTexture texture in _textures.Values)
            {
                try { texture.Dispose(); } catch { }
            }
            _textures.Clear();

            if (_initialized)
            {
                try { _encoder.DestroyBitstreamBuffer(_bitstream.BitstreamBuffer); } catch { }
            }
            try { _encoder.DestroyEncoder(); } catch { }
            _initialized = false;
        }
    }
}
