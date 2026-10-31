param([string]$Out)
Add-Type -AssemblyName System.Drawing
# 256x256 icon drawn on a 32x32 grid of 8x8 "pixels".
$S = 8
$bmp = New-Object System.Drawing.Bitmap 256, 256
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::Transparent)
function C($hex) { [System.Drawing.ColorTranslator]::FromHtml($hex) }
function Px([int]$x, [int]$y, $col) { $b = New-Object System.Drawing.SolidBrush $col; $g.FillRectangle($b, $x * $S, $y * $S, $S, $S); $b.Dispose() }
function Rect([int]$x1, [int]$y1, [int]$x2, [int]$y2, $col) { for ($y = $y1; $y -le $y2; $y++) { for ($x = $x1; $x -le $x2; $x++) { Px $x $y $col } } }

# Background: dark square with rounded corners
Rect 0 0 31 31 (C '#1C2130')
foreach ($p in @(@(0,0),@(1,0),@(0,1),@(31,0),@(30,0),@(31,1),@(0,31),@(1,31),@(0,30),@(31,31),@(30,31),@(31,30))) {
    $b = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Transparent)
    $g.CompositingMode = 'SourceCopy'; $g.FillRectangle($b, $p[0] * $S, $p[1] * $S, $S, $S); $g.CompositingMode = 'SourceOver'
}
# TNT block (inside the minecart)
Rect 10 14 21 20 (C '#C8321E')
Rect 10 14 21 14 (C '#E5523A')   # lighter top edge
Rect 21 15 21 20 (C '#9E2414')   # shaded side
Rect 10 16 21 18 (C '#E8E8E8')   # white band
# "TNT" label (three 3x3 letters; the N is a lowercase "n", easier to read this small)
$black = C '#1A1A1A'
Rect 11 16 13 16 $black; Rect 12 17 12 18 $black           # T
Rect 15 16 16 16 $black; Rect 15 17 15 18 $black; Rect 17 17 17 18 $black   # n
Rect 19 16 21 16 $black; Rect 20 17 20 18 $black           # T
# lit fuse
Px 16 13 (C '#7A6A55'); Px 16 12 (C '#FFB03A'); Px 17 11 (C '#FFE07A')

# Minecart
Rect 7 21 24 26 (C '#7E848E')
Rect 7 21 24 21 (C '#B9BEC7')    # top edge
Rect 8 22 23 25 (C '#6A707A')
Rect 7 26 24 26 (C '#565B64')
Rect 7 21 7 26 (C '#9AA0A9'); Rect 24 21 24 26 (C '#5E636C')
# wheels
foreach ($wx in @(9, 20)) {
    Rect $wx 26 ($wx + 2) 28 (C '#262626')
    Px ($wx + 1) 27 (C '#5A5A5A')
}
# rail
Rect 3 29 28 29 (C '#6B5B45')

# Damage number "-9.8" in red, 3x5 font scaled 2x, with a shadow
$font = @{
    '-' = @('000','000','111','000','000');
    '9' = @('111','101','111','001','111');
    '8' = @('111','101','111','101','111');
    '.' = @('0','0','0','0','1')
}
function DrawText([string]$text, [int]$ox, [int]$oy, $col) {
    $cx = $ox
    foreach ($ch in $text.ToCharArray()) {
        $rows = $font["$ch"]
        for ($r = 0; $r -lt 5; $r++) {
            for ($c = 0; $c -lt $rows[$r].Length; $c++) {
                if ($rows[$r][$c] -eq '1') { Rect ($cx + $c * 2) ($oy + $r * 2) ($cx + $c * 2 + 1) ($oy + $r * 2 + 1) $col }
            }
        }
        $cx += $rows[0].Length * 2 + 1
    }
}
DrawText '-9.8' 5 2 (C '#4A0A0A')      # shadow
DrawText '-9.8' 4 1 (C '#FF2A2A')     # text
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
