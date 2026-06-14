# Prompt para continuar el desarrollo de SOS POS en .NET/WPF

Usa este prompt al inicio de cada sesion con Claude para darle contexto del proyecto.

---

## Prompt base (copiar y pegar al inicio de la sesion)

```
Estamos desarrollando SOS POS, un sistema de punto de venta offline para escritorio Windows.
El proyecto esta hecho en C# con WPF (.NET 8) y usa SQLite como base de datos local.

Estructura del proyecto (dotnet/SosPOS/):
- Views/        → Pantallas XAML: LoginView, DashboardView, HomeView, PosView, InventarioView
- Models/       → Clases de datos: Producto, ItemCarrito
- Services/     → Logica de negocio y acceso a SQLite (por implementar)
- ViewModels/   → Logica de UI con data binding (por implementar)
- Assets/Styles/GlobalStyles.xaml → Estilos y colores globales

Estado actual:
- Las vistas base estan creadas con la UI en XAML
- La logica (Services, ViewModels) esta pendiente de implementar
- Los TODO en los archivos .cs marcan que falta conectar con la base de datos

Necesito que me ayudes a implementar: [DESCRIBIR LO QUE QUIERES HACER]
```

---

## Tareas pendientes para continuar

### Prioridad alta
- [ ] Crear `Services/DatabaseService.cs` — inicializar SQLite y crear tablas
- [ ] Crear `Services/InventarioService.cs` — CRUD de productos
- [ ] Crear `Services/VentasService.cs` — registrar ventas
- [ ] Conectar `InventarioView` con la base de datos real
- [ ] Conectar `PosView` con productos reales y registrar ventas

### Prioridad media
- [ ] Implementar autenticacion real (usuarios en SQLite en vez de credenciales fijas)
- [ ] Agregar dialogo de nuevo/editar producto en InventarioView
- [ ] Mostrar totales reales en HomeView (ventas del dia, cantidad de productos)

### Prioridad baja
- [ ] Agregar reporte de ventas
- [ ] Exportar a PDF o Excel
- [ ] Configuracion de la tienda (nombre, logo)

---

## Tecnologias usadas

| Tecnologia         | Para que se usa                        |
|--------------------|----------------------------------------|
| C# / .NET 8        | Lenguaje y framework principal         |
| WPF                | Interfaz grafica de escritorio Windows |
| XAML               | Definicion de pantallas (como HTML)    |
| SQLite             | Base de datos local offline            |
| Microsoft.Data.Sqlite | Paquete NuGet para acceder a SQLite |
| CommunityToolkit.Mvvm | Helpers para data binding          |

---

## Convenciones del proyecto

- Nombres de archivos: `NombreView.xaml` y `NombreView.xaml.cs`
- Colores: usar siempre los brushes definidos en `GlobalStyles.xaml`
- Base de datos: un solo archivo `sospos.db` en la carpeta del ejecutable
- No usar code-behind para logica de negocio, moverla a Services
