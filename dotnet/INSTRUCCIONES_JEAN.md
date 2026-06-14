# Instrucciones para Jean — SOS POS (.NET/WPF)

Hola Jean! Este documento te explica todo lo que necesitas saber para continuar el desarrollo de SOS POS en .NET.

---

## Estado actual del proyecto (14/06/2026)

### Lo que ya esta hecho
- Estructura completa del proyecto WPF en C#
- Pantalla de Login con navegacion al Dashboard
- Dashboard con sidebar de navegacion (Inicio, Punto de Venta, Inventario)
- Pantalla de Inicio (HomeView) con tarjetas de resumen
- Pantalla de Punto de Venta (PosView) con carrito y lista de productos
- Pantalla de Inventario (InventarioView) con tabla y botones de accion
- Estilos visuales globales (colores, botones, inputs)
- Modelos de datos: Producto, ItemCarrito

### Lo que FALTA implementar (en orden de prioridad)

#### 1. Base de datos SQLite (HACER PRIMERO)
- Crear `Services/DatabaseService.cs`
- Inicializar el archivo `sospos.db` al arrancar la app
- Crear las tablas: `productos`, `ventas`, `detalle_ventas`, `usuarios`

#### 2. Servicio de Inventario
- Crear `Services/InventarioService.cs`
- Metodos: ObtenerTodos, Buscar, Agregar, Editar, Eliminar
- Conectar con `InventarioView.xaml.cs` (reemplazar los TODO)

#### 3. Servicio de Ventas
- Crear `Services/VentasService.cs`
- Metodos: RegistrarVenta, ObtenerVentasHoy, ObtenerTotalHoy
- Conectar con `PosView.xaml.cs` (reemplazar los TODO)

#### 4. Login real
- El login actual usa credenciales fijas: usuario `admin`, password `admin`
- Hay que conectarlo a la tabla `usuarios` en SQLite
- Archivo a modificar: `Views/LoginView.xaml.cs`

#### 5. Dialogo de productos
- Crear `Views/ProductoDialogView.xaml` para agregar y editar productos
- Llamarlo desde los botones de InventarioView

#### 6. HomeView con datos reales
- Mostrar ventas del dia y cantidad de productos reales
- Conectar con VentasService e InventarioService

---

## Como empezar (primer dia)

1. Instalar Visual Studio 2022 siguiendo `MANUAL_INSTALACION.md`
2. Abrir `SosPOS/SosPOS.csproj` en Visual Studio
3. Presionar F5 para ver que la app corre (Login → Dashboard)
4. Abrir Claude Code en VS Code o usar Claude en el navegador
5. Copiar el prompt de `PROMPT_PARA_CLAUDE.md` y pegarlo al inicio de la sesion
6. Decirle a Claude que quieres implementar primero: `DatabaseService`

---

## Como usar Claude para continuar

Cada vez que abras una sesion nueva con Claude, pega el contenido de `PROMPT_PARA_CLAUDE.md` primero. Eso le da todo el contexto del proyecto sin que tengas que explicar de nuevo.

Ejemplo de como pedir cosas:
- "Implementa el DatabaseService con SQLite para crear las tablas de productos y ventas"
- "Conecta InventarioView con la base de datos usando InventarioService"
- "Crea el dialogo para agregar un producto nuevo"

---

## Archivos clave que vas a tocar mas seguido

| Archivo | Para que |
|---|---|
| `Views/LoginView.xaml.cs` | Logica del login |
| `Views/InventarioView.xaml.cs` | Logica del inventario |
| `Views/PosView.xaml.cs` | Logica del punto de venta |
| `Services/` | Crear todos los servicios aqui |
| `Assets/Styles/GlobalStyles.xaml` | Cambiar colores o estilos |

---

## Credenciales actuales (solo desarrollo)

| Campo | Valor |
|---|---|
| Usuario | admin |
| Password | admin |

Cambiar esto cuando implementes el login real contra la base de datos.

---

## Dudas frecuentes

**No se abren los archivos XAML en modo diseño**
→ Normal si no tienes el SDK instalado. Instala Visual Studio completo (Paso 1 del manual).

**Aparece error de paquetes NuGet**
→ Clic derecho en el proyecto en el Explorador de soluciones → "Restaurar paquetes NuGet"

**No se donde poner la logica**
→ La logica de negocio va en `Services/`, la logica de UI en `ViewModels/`, y las pantallas en `Views/`.

---

Cualquier duda preguntale a Claude con el contexto del `PROMPT_PARA_CLAUDE.md` y te ayudara paso a paso.
