# Manual de Instalacion — SOS POS (.NET / WPF)

Este manual explica paso a paso que instalar y como abrir el proyecto para poder desarrollarlo o ejecutarlo.

---

## Requisitos del sistema

- Windows 10 u 11 (64 bits)
- Conexion a internet para la instalacion (una sola vez)
- Al menos 4 GB de RAM y 10 GB de espacio libre

---

## Paso 1 — Instalar Visual Studio 2022

1. Ir a: https://visualstudio.microsoft.com/es/downloads/
2. Descargar **Visual Studio Community 2022** (es gratis)
3. Ejecutar el instalador
4. En la pantalla de **Cargas de trabajo**, seleccionar:
   - **Desarrollo de escritorio de .NET**
5. Hacer clic en **Instalar** y esperar (puede tardar 15-30 minutos)

> Visual Studio incluye automaticamente el .NET SDK, no hay que instalarlo por separado.

---

## Paso 2 — Instalar Git (si no lo tienes)

1. Ir a: https://git-scm.com/download/win
2. Descargar e instalar con las opciones por defecto
3. Verificar en terminal: `git --version`

---

## Paso 3 — Clonar el repositorio

Abrir **PowerShell** o **Simbolo del sistema** y ejecutar:

```bash
git clone https://github.com/TU_USUARIO/sos-pos.git
cd sos-pos/dotnet
```

> Reemplaza `TU_USUARIO` con el usuario de GitHub real.

---

## Paso 4 — Abrir el proyecto en Visual Studio

1. Abrir **Visual Studio 2022**
2. Clic en **Abrir un proyecto o solucion**
3. Navegar hasta la carpeta `dotnet/SosPOS/`
4. Seleccionar el archivo `SosPOS.csproj`
5. Esperar que Visual Studio restaure los paquetes NuGet automaticamente

---

## Paso 5 — Ejecutar el proyecto

1. En la barra superior, asegurarse que dice **Debug** y **Any CPU**
2. Presionar **F5** o el boton verde de Play
3. Debe abrirse la ventana de Login de SOS POS

---

## Paso 6 — Publicar como ejecutable (.exe sin instalar nada)

Para generar un `.exe` que cualquier PC pueda ejecutar sin instalar .NET:

1. En Visual Studio, menu **Compilar > Publicar SosPOS**
2. Elegir **Carpeta** como destino
3. Configuracion recomendada:
   - Modo de implementacion: **Independiente**
   - Runtime de destino: **win-x64**
   - Producir un solo archivo: **Si**
4. Clic en **Publicar**
5. El `.exe` aparece en `bin/Release/net8.0-windows/win-x64/publish/`

---

## Credenciales por defecto (modo desarrollo)

| Campo    | Valor   |
|----------|---------|
| Usuario  | admin   |
| Password | admin   |

> Cambiar estas credenciales antes de entregar a produccion.

---

## Estructura del proyecto

```
dotnet/
└── SosPOS/
    ├── Assets/
    │   ├── Images/        # Logo e iconos
    │   └── Styles/        # Estilos globales XAML
    ├── Models/            # Clases de datos (Producto, ItemCarrito...)
    ├── Services/          # Logica de negocio y acceso a SQLite
    ├── ViewModels/        # (Para logica de UI cuando se implemente)
    ├── Views/             # Pantallas de la app
    │   ├── LoginView      # Pantalla de inicio de sesion
    │   ├── DashboardView  # Ventana principal con sidebar
    │   ├── HomeView       # Pantalla de inicio / resumen
    │   ├── PosView        # Punto de venta
    │   └── InventarioView # Gestion de productos
    ├── App.xaml           # Entrada de la aplicacion
    └── SosPOS.csproj      # Archivo de proyecto
```

---

## Problemas comunes

**Error: "No se encontro el SDK de .NET"**
- Solucionar instalando Visual Studio con la carga de trabajo **Desarrollo de escritorio de .NET** (Paso 1).

**Error: "paquetes NuGet no restaurados"**
- En Visual Studio: clic derecho en el proyecto > **Restaurar paquetes NuGet**

**La app no abre al hacer F5**
- Verificar que `SosPOS.csproj` es el proyecto de inicio (debe estar en negrita en el Explorador de soluciones).
