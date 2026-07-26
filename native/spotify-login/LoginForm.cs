using System;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Nuta.SpotifyLogin;

// Okno logowania Spotify wyłącznie do jednego celu: przejść przez WebView2/Edge
// (który — inaczej niż JCEF — przechodzi reCAPTCHA/challenge Spotify) i odczytać
// httpOnly cookie sp_dc/sp_t przez natywny CookieManager, którego JS nie widzi.
// Wynik trafia na stdout jako jedna linia JSON; proces kończy się i nic więcej nie robi.
public sealed class LoginForm : Form
{
    private const string StartUrl = "https://open.spotify.com/";
    private readonly WebView2 _webView = new() { Dock = DockStyle.Fill };
    private bool _resultWritten;

    public LoginForm()
    {
        Text = "Zaloguj się do Spotify";
        Width = 480;
        Height = 720;
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(_webView);
        Load += OnLoad;
        FormClosed += OnFormClosed;
    }

    private async void OnLoad(object? sender, EventArgs e)
    {
        try
        {
            var userDataFolder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Nuta", "spotify-login-webview2");
            Directory.CreateDirectory(userDataFolder);

            var env = await CoreWebView2Environment.CreateAsync(userDataFolder: userDataFolder);
            await _webView.EnsureCoreWebView2Async(env);

            _webView.CoreWebView2.SourceChanged += OnSourceChanged;
            _webView.CoreWebView2.Navigate(StartUrl);
        }
        catch (Exception ex)
        {
            await Console.Error.WriteLineAsync($"init_failed: {ex.Message}");
            Environment.ExitCode = 2;
            Close();
        }
    }

    private async void OnSourceChanged(object? sender, CoreWebView2SourceChangedEventArgs e)
    {
        if (_resultWritten) return;
        if (_webView.CoreWebView2?.Source is not { } sourceUrl) return;
        if (!Uri.TryCreate(sourceUrl, UriKind.Absolute, out var uri)) return;

        // Po zalogowaniu Spotify wraca na open.spotify.com i dopiero wtedy sp_dc istnieje —
        // sam host nie wystarczy jako sygnał (widzimy open.spotify.com też przed logowaniem).
        if (uri.Host != "open.spotify.com") return;

        await TryExtractSessionAsync();
    }

    private async Task TryExtractSessionAsync()
    {
        if (_resultWritten || _webView.CoreWebView2 is null) return;

        var cookies = await _webView.CoreWebView2.CookieManager.GetCookiesAsync("https://open.spotify.com");
        var spDc = cookies.FirstOrDefault(c => c.Name == "sp_dc");
        if (spDc is null || string.IsNullOrEmpty(spDc.Value)) return;

        var spT = cookies.FirstOrDefault(c => c.Name == "sp_t");
        _resultWritten = true;

        var result = JsonSerializer.Serialize(new
        {
            spDc = spDc.Value,
            spT = spT?.Value,
            obtainedAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        });
        Console.WriteLine(result);
        Environment.ExitCode = 0;
        Close();
    }

    private void OnFormClosed(object? sender, FormClosedEventArgs e)
    {
        if (!_resultWritten && Environment.ExitCode == 0)
        {
            // Użytkownik zamknął okno ręcznie bez ukończenia logowania.
            Environment.ExitCode = 1;
        }
        Application.Exit();
    }
}
