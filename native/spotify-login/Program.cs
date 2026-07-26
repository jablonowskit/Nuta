using System;
using System.Windows.Forms;

namespace Nuta.SpotifyLogin;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new LoginForm());
    }
}
