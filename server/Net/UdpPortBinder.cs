using System.Net;
using System.Net.Sockets;

namespace LocalStream.Server.Net;

/// <summary>
/// Binds a UDP socket to a fixed port with a short retry, falling back to an ephemeral
/// port (with a loud warning) only after the fixed port proves persistently unavailable.
/// Fixed ports matter: PROTOCOL.md clients may hard-code expectations, and a silent
/// ephemeral fallback made startup behavior unpredictable across restarts.
/// </summary>
internal static class UdpPortBinder
{
    private const int RetryAttempts = 4;
    private const int RetryDelayMs = 250;

    /// <summary>Returns the port actually bound.</summary>
    public static int Bind(Socket socket, int preferredPort, string label)
    {
        for (int attempt = 0; ; attempt++)
        {
            try
            {
                socket.Bind(new IPEndPoint(IPAddress.Any, preferredPort));
                return preferredPort;
            }
            catch (SocketException ex) when (attempt < RetryAttempts)
            {
                Console.Error.WriteLine(
                    $"[ports] {label} UDP port {preferredPort} bind failed ({ex.SocketErrorCode}) " +
                    $"— retry {attempt + 1}/{RetryAttempts} in {RetryDelayMs} ms...");
                Thread.Sleep(RetryDelayMs);
            }
            catch (SocketException ex)
            {
                Console.Error.WriteLine(
                    $"[ports] WARNING: {label} UDP port {preferredPort} still unavailable after " +
                    $"{RetryAttempts} retries ({ex.SocketErrorCode}). Falling back to an ephemeral " +
                    "port. Another LocalStream instance may already be running — stop it or pass " +
                    "--media-port/--audio-port to pick a free fixed port.");
                socket.Bind(new IPEndPoint(IPAddress.Any, 0));
                return ((IPEndPoint)socket.LocalEndPoint!).Port;
            }
        }
    }
}
