using System.Windows;
using System.Windows.Controls;

namespace SosPOS.Views;

public partial class DashboardView : Window
{
    public DashboardView()
    {
        InitializeComponent();
        MainFrame.Navigate(new HomeView());
    }

    private void NavButton_Click(object sender, RoutedEventArgs e)
    {
        var tag = ((Button)sender).Tag?.ToString();
        switch (tag)
        {
            case "Home":
                MainFrame.Navigate(new HomeView());
                break;
            case "POS":
                MainFrame.Navigate(new PosView());
                break;
            case "Inventario":
                MainFrame.Navigate(new InventarioView());
                break;
        }
    }

    private void BtnLogout_Click(object sender, RoutedEventArgs e)
    {
        var login = new LoginView();
        login.Show();
        this.Close();
    }
}
