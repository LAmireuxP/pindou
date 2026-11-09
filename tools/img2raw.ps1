# 图片 → 裸 ARGB 数据（int32 w, int32 h, w*h 个 int32），给 HarnessTest 离线跑管线用。
# 用法: powershell -ExecutionPolicy Bypass -File tools\img2raw.ps1 -In <图片> -Out <raw>
param(
    [Parameter(Mandatory = $true)][string]$In,
    [Parameter(Mandatory = $true)][string]$Out
)
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($In)
$bmp = New-Object System.Drawing.Bitmap $img.Width, $img.Height, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.DrawImage($img, 0, 0, $img.Width, $img.Height)
$g.Dispose()
$rect = New-Object System.Drawing.Rectangle 0, 0, $bmp.Width, $bmp.Height
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$bytes = New-Object byte[] ($data.Stride * $bmp.Height)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$bmp.UnlockBits($data)
$bmp.Dispose(); $img.Dispose()

$fs = [System.IO.File]::Create($Out)
$bw = New-Object System.IO.BinaryWriter $fs
$bw.Write([int]$data.Width)
$bw.Write([int]$data.Height)
for ($y = 0; $y -lt $data.Height; $y++) {
    $off = $y * $data.Stride
    for ($x = 0; $x -lt $data.Width; $x++) {
        $bw.Write([int][BitConverter]::ToInt32($bytes, $off + $x * 4))
    }
}
$bw.Flush(); $bw.Close(); $fs.Close()
"raw -> $Out ($($data.Width)x$($data.Height))"
