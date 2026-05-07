using System;
using System.IO;
using System.Windows;

namespace SkyModel.Viewer;

public static class Program
{
    private static readonly string LogFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "SkyModel.Viewer",
        "error.log");

    [STAThread]
    public static void Main()
    {
        try
        {
            LogMessage("=== Application Startup ===");
            LogMessage($"Time: {DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}");
            LogMessage($"Working Directory: {Directory.GetCurrentDirectory()}");

            LogMessage("Creating App instance...");
            var app = new App();
            LogMessage("App instance created successfully");

            LogMessage("Starting application...");
            app.Run();

            LogMessage("Application exited normally");
        }
        catch (Exception ex)
        {
            LogException(ex);
            MessageBox.Show(
                $"Fatal Error:\n\n{ex.GetType().Name}: {ex.Message}\n\nSee {LogFile} for details.",
                "Application Startup Failed",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
    }

    public static void LogMessage(string message)
    {
        try
        {
            var dir = Path.GetDirectoryName(LogFile);
            if (dir != null && !Directory.Exists(dir))
            {
                Directory.CreateDirectory(dir);
            }

            File.AppendAllText(LogFile, $"[{DateTime.Now:HH:mm:ss.fff}] {message}\n");
        }
        catch { }
    }

    private static void LogException(Exception ex)
    {
        try
        {
            var dir = Path.GetDirectoryName(LogFile);
            if (dir != null && !Directory.Exists(dir))
            {
                Directory.CreateDirectory(dir);
            }

            var message = string.Format(
                "[{0:HH:mm:ss.fff}] EXCEPTION: {1}\nMessage: {2}\nStackTrace:\n{3}",
                DateTime.Now,
                ex.GetType().FullName,
                ex.Message,
                ex.StackTrace);

            if (ex.InnerException != null)
            {
                message += string.Format(
                    "\n\nInnerException: {0}\n{1}\n{2}",
                    ex.InnerException.GetType().FullName,
                    ex.InnerException.Message,
                    ex.InnerException.StackTrace);
            }

            File.AppendAllText(LogFile, message + "\n\n");
        }
        catch { }
    }
}
