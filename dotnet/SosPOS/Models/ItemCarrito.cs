namespace SosPOS.Models;

public class ItemCarrito
{
    public Producto Producto { get; set; } = null!;
    public string Nombre => Producto.Nombre;
    public int Cantidad { get; set; }
    public decimal Total => Producto.Precio * Cantidad;
    public string TotalFormateado => $"${Total:F2}";
}
