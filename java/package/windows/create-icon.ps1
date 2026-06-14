# Convierte pudu-logo-new.png a icon.ico (multi-tamaño) para jpackage
# Ejecutar desde la carpeta package\windows\

param(
    [string]$PngPath = ""
)

Add-Type -AssemblyName System.Drawing

# Ruta siempre relativa al script, sin importar desde dónde se invoque
if (-not $PngPath) {
    $PngPath = Join-Path $PSScriptRoot "..\..\src\main\resources\com\sospos\images\pudu-logo-new.png"
}
$resolved = (Resolve-Path $PngPath).Path
$sizes    = @(16, 32, 48, 256)
$streams  = @()

foreach ($sz in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap($sz, $sz)
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $src = [System.Drawing.Image]::FromFile($resolved)
    $g.DrawImage($src, 0, 0, $sz, $sz)
    $g.Dispose()
    $src.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $streams += $ms
}

$icoPath  = Join-Path $PSScriptRoot "icon.ico"
$icoFile  = New-Object System.IO.FileStream($icoPath, [System.IO.FileMode]::Create)
$writer   = New-Object System.IO.BinaryWriter($icoFile)

# ICO header
$writer.Write([int16]0)               # Reserved
$writer.Write([int16]1)               # Type: ICO
$writer.Write([int16]$sizes.Count)    # Num images

# Offset inicial: header(6) + directorio(16 * n)
$offset = 6 + 16 * $sizes.Count

# Directorio de imágenes
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $sz   = $sizes[$i]
    $data = $streams[$i].ToArray()
    $w    = if ($sz -eq 256) { 0 } else { $sz }  # 0 = 256 en formato ICO

    $writer.Write([byte]$w)
    $writer.Write([byte]$w)
    $writer.Write([byte]0)            # Color count
    $writer.Write([byte]0)            # Reserved
    $writer.Write([int16]1)           # Planes
    $writer.Write([int16]32)          # Bits per pixel
    $writer.Write([int32]$data.Length)
    $writer.Write([int32]$offset)
    $offset += $data.Length
}

# Datos de imagen
foreach ($ms in $streams) {
    $writer.Write($ms.ToArray())
}

$writer.Close()
$icoFile.Close()

Write-Host "icon.ico generado en: $icoPath"
