using System.Windows;

namespace SosPOS.Views;

public partial class LoginView : Window
{
    public LoginView()
    {
        InitializeComponent();
    }

    private void BtnLogin_Click(object sender, RoutedEventArgs e)
    {
        string usuario = TxtUsuario.Text.Trim();
        string password = TxtPassword.Password;

        // TODO: validar contra base de datos SQLite
        if (usuario == "admin" && password == "admin")
        {
            var dashboard = new DashboardView();
            dashboard.Show();
            this.Close();
        }
        else
        {
            TxtError.Text = "Usuario o contraseña incorrectos.";
            TxtError.Visibility = Visibility.Visible;
        }
    }
}
