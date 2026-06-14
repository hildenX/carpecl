using System.Windows;
using System.Windows.Controls;

namespace SosPOS.Views;

public partial class PosView : Page
{
    public PosView()
    {
        InitializeComponent();
        // TODO: cargar productos desde ServicioInventario
    }

    private void TxtBuscar_TextChanged(object sender, TextChangedEventArgs e)
    {
        // TODO: filtrar lista de productos
    }

    private void BtnCobrar_Click(object sender, RoutedEventArgs e)
    {
        // TODO: procesar pago y guardar venta en SQLite
        MessageBox.Show("Venta procesada correctamente.", "SOS POS", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void BtnCancelar_Click(object sender, RoutedEventArgs e)
    {
        // TODO: limpiar carrito
    }
}
