using System.Runtime.InteropServices;

namespace LocalStream.Server.Net;

/// <summary>
/// Sub-millisecond waits for the pacing gate. Thread.Sleep follows the system timer resolution,
/// which is up to ~15.6 ms when no process has raised it, so a paced IDR stalls far longer than
/// asked and then leaves as the very burst pacing exists to avoid. A high-resolution waitable
/// timer (Windows 10 1803+) wakes within a fraction of a millisecond without changing the
/// global timer resolution. Falls back to Thread.Sleep where it is unavailable.
///
/// Not thread-safe: one instance per sending thread.
/// </summary>
internal sealed class HighResolutionSleeper : IDisposable
{
    private const uint CreateWaitableTimerHighResolution = 0x00000002;
    private const uint TimerAllAccess = 0x001F0003;
    private const uint WaitObject0 = 0;

    private IntPtr _timer;

    public bool IsHighResolution => _timer != IntPtr.Zero;

    public HighResolutionSleeper()
    {
        try
        {
            _timer = CreateWaitableTimerExW(
                IntPtr.Zero, null, CreateWaitableTimerHighResolution, TimerAllAccess);
        }
        catch (EntryPointNotFoundException)
        {
            _timer = IntPtr.Zero;
        }
    }

    public void Sleep(long microseconds)
    {
        if (microseconds <= 0)
            return;
        if (_timer != IntPtr.Zero)
        {
            long dueTime = -microseconds * 10; // negative = relative, in 100 ns units
            if (SetWaitableTimer(_timer, ref dueTime, 0, IntPtr.Zero, IntPtr.Zero, false))
            {
                // The timeout only guards against a timer that never fires.
                WaitForSingleObject(_timer, (uint)(microseconds / 1000 + 50));
                return;
            }
        }
        Thread.Sleep((int)Math.Ceiling(microseconds / 1000.0));
    }

    public void Dispose()
    {
        if (_timer != IntPtr.Zero)
        {
            CloseHandle(_timer);
            _timer = IntPtr.Zero;
        }
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateWaitableTimerExW(
        IntPtr lpTimerAttributes, string? lpTimerName, uint dwFlags, uint dwDesiredAccess);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWaitableTimer(
        IntPtr hTimer, ref long pDueTime, int lPeriod, IntPtr pfnCompletionRoutine,
        IntPtr lpArgToCompletionRoutine, [MarshalAs(UnmanagedType.Bool)] bool fResume);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr hObject);
}
