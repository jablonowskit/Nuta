using System;
using System.Windows.Forms;

namespace Nuta.SpotifyLogin;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        // Bez tego wyjątek spoza jawnie obsłużonych ścieżek (np. w samym WebView2)
        // ubija proces po cichu, bez śladu na stderr — nie do zdiagnozowania z zewnątrz.
        Application.ThreadException += (_, e) =>
        {
            Console.Error.WriteLine($"unhandled_thread_exception: {e.Exception}");
            Environment.Exit(3);
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            Console.Error.WriteLine($"unhandled_exception: {e.ExceptionObject}");
            Environment.Exit(3);
        };

        ApplicationConfiguration.Initialize();
        Application.Run(new LoginForm());
    }
}
