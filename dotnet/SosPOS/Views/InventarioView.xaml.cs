using System.Windows;
using System.Windows.Controls;

namespace SosPOS.Views;

public partial class InventarioView : Page
{
    public InventarioView()
    {
        InitializeComponent();
        // TODO: cargar productos desde ServicioInventario
    }

    private void TxtBuscar_TextChanged(object sender, TextChangedEventArgs e)
    {
        // TODO: filtrar lista
    }

    private void BtnNuevo_Click(object sender, RoutedEventArgs e)
    {
        // TODO: abrir dialogo de nuevo producto
    }

    private void BtnEditar_Click(object sender, RoutedEventArgs e)
    {
        // TODO: abrir dialogo de edicion
    }

    private void BtnEliminar_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show("¿Eliminar este producto?", "Confirmar",
            MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (result == MessageBoxResult.Yes)
        {
            // TODO: eliminar de SQLite
        }
    }
}
