using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using LocalStream.Server.Logging;

namespace LocalStream.Server.Net;

/// <summary>
/// Registers the media socket with qWAVE as audio/video traffic. Windows ignores IP_TOS from
/// ordinary applications, but a qWAVE flow of this type is marked DSCP CS5 (40), which Wi-Fi
/// maps to the WMM video queue (AC_VI) on the router-to-TV hop. Best effort: without qWAVE, or
/// if a policy strips the mark, media keeps flowing unmarked.
///
/// A PC outside a domain may additionally need the "Do not use NLA" = "1" value under
/// HKLM\SYSTEM\CurrentControlSet\Services\Tcpip\QoS before marks leave the machine; check
/// the DSCP field in a packet capture to confirm.
///
/// The socket is unconnected, so the flow is bound to one destination and re-targeted each
/// time the media endpoint changes. Thread-safe.
/// </summary>
internal sealed class MediaQosFlow : IDisposable
{
    private const int QosTrafficTypeAudioVideo = 3;
    // Our own congestion control drives the bitrate; qWAVE must not probe or adapt the flow.
    private const uint QosNonAdaptiveFlow = 0x00000002;

    private readonly Socket _socket;
    private readonly object _gate = new();
    private IntPtr _handle;
    private uint _flowId;
    private string _status = "";

    public MediaQosFlow(Socket socket)
    {
        _socket = socket;
        var version = new QosVersion { MajorVersion = 1, MinorVersion = 0 };
        try
        {
            if (!QOSCreateHandle(ref version, out _handle))
            {
                _handle = IntPtr.Zero;
                Report($"unavailable (QOSCreateHandle error {Marshal.GetLastWin32Error()}); DSCP stays best effort");
            }
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
        {
            _handle = IntPtr.Zero;
            Report("unavailable (qwave.dll not found); DSCP stays best effort");
        }
    }

    /// <summary>Points the flow at a newly learned client media endpoint.</summary>
    public void SetDestination(EndPoint destination)
    {
        if (destination is not IPEndPoint ip)
            return;
        lock (_gate)
        {
            if (_handle == IntPtr.Zero)
                return;
            if (_flowId != 0)
            {
                QOSRemoveSocketFromFlow(_handle, _socket.Handle, _flowId, 0);
                _flowId = 0;
            }

            SocketAddress address = ip.Serialize();
            IntPtr sockaddr = Marshal.AllocHGlobal(address.Size);
            try
            {
                for (int i = 0; i < address.Size; i++)
                    Marshal.WriteByte(sockaddr, i, address[i]);
                uint flowId = 0;
                if (QOSAddSocketToFlow(_handle, _socket.Handle, sockaddr,
                        QosTrafficTypeAudioVideo, QosNonAdaptiveFlow, ref flowId))
                {
                    _flowId = flowId;
                    Report("audio/video flow active (DSCP CS5, Wi-Fi AC_VI)");
                }
                else
                {
                    Report($"QOSAddSocketToFlow failed (error {Marshal.GetLastWin32Error()}); DSCP stays best effort");
                }
            }
            finally
            {
                Marshal.FreeHGlobal(sockaddr);
            }
        }
    }

    /// <summary>Logs only transitions, so a client that re-announces its port does not spam.</summary>
    private void Report(string status)
    {
        if (status == _status)
            return;
        _status = status;
        Console.WriteLine($"[media] qWAVE {status}.");
        AsyncLogger.Info($"[media] qWAVE {status}.");
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_handle == IntPtr.Zero)
                return;
            if (_flowId != 0)
            {
                try { QOSRemoveSocketFromFlow(_handle, _socket.Handle, _flowId, 0); } catch { }
                _flowId = 0;
            }
            QOSCloseHandle(_handle);
            _handle = IntPtr.Zero;
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct QosVersion
    {
        public ushort MajorVersion;
        public ushort MinorVersion;
    }

    [DllImport("qwave.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QOSCreateHandle(ref QosVersion version, out IntPtr qosHandle);

    [DllImport("qwave.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QOSCloseHandle(IntPtr qosHandle);

    [DllImport("qwave.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QOSAddSocketToFlow(
        IntPtr qosHandle, IntPtr socket, IntPtr destAddr, int trafficType, uint flags, ref uint flowId);

    [DllImport("qwave.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QOSRemoveSocketFromFlow(IntPtr qosHandle, IntPtr socket, uint flowId, uint flags);
}
