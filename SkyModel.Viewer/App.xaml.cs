using System.Configuration;
using System.Data;
using System.Windows;

namespace SkyModel.Viewer;

/// <summary>
/// Interaction logic for App.xaml
/// </summary>
public partial class App : System.Windows.Application
{
    public App()
    {
        Program.LogMessage("App constructor called");
        this.Startup += App_Startup;
        this.DispatcherUnhandledException += App_DispatcherUnhandledException;
    }

    private void App_Startup(object sender, StartupEventArgs e)
    {
        try
        {
            Program.LogMessage("App_Startup event fired");
            Program.LogMessage("Creating MainWindow...");
            var mainWindow = new MainWindow();
            Program.LogMessage($"MainWindow created, showing window...");
            mainWindow.Show();
            Program.LogMessage("MainWindow shown");
        }
        catch (Exception ex)
        {
            Program.LogMessage($"EXCEPTION in App_Startup: {ex.GetType().Name}: {ex.Message}\n{ex.StackTrace}");
            if (ex.InnerException != null)
            {
                Program.LogMessage($"InnerException: {ex.InnerException.GetType().Name}: {ex.InnerException.Message}\n{ex.InnerException.StackTrace}");
            }
            throw;
        }
    }

    private void App_DispatcherUnhandledException(object sender, System.Windows.Threading.DispatcherUnhandledExceptionEventArgs e)
    {
        Program.LogMessage($"UNHANDLED EXCEPTION: {e.Exception.GetType().Name}: {e.Exception.Message}\n{e.Exception.StackTrace}");
        if (e.Exception.InnerException != null)
        {
            Program.LogMessage($"InnerException: {e.Exception.InnerException.GetType().Name}: {e.Exception.InnerException.Message}");
        }
    }
}
