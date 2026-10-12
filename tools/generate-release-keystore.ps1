<#
Genera una clave de firma de PRODUCCION para ParDocs (.jks) y escribe las
credenciales en keystore.properties.local (ignorado por git).

- El keystore se crea FUERA del repositorio (por defecto %USERPROFILE%\pardocs-signing).
- La contrasena se genera aleatoriamente y nunca se imprime por pantalla.
- NUNCA subas el .jks ni keystore.properties.local a GitHub.
- Guarda copia de seguridad de ambos: sin la clave no podras actualizar la app.

Uso:
  ./tools/generate-release-keystore.ps1
  ./tools/generate-release-keystore.ps1 -SignDir D:\keys -KeyAlias mi-alias
#>
param(
    [string]$SignDir  = (Join-Path $env:USERPROFILE 'pardocs-signing'),
    [string]$KeyAlias = 'pardocs',
    [string]$DName    = 'CN=ParDocs, OU=Mobile, O=ParDocs, L=Madrid, ST=Madrid, C=ES'
)

$ErrorActionPreference = 'Stop'

$projectDir = Split-Path -Parent $PSScriptRoot
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { $null }
if (-not $keytool -or -not (Test-Path $keytool)) {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { $keytool = $cmd.Source }
}
if (-not $keytool -or -not (Test-Path $keytool)) {
    throw "No se encontro keytool. Define JAVA_HOME o agrega el JDK al PATH."
}

$keystore = Join-Path $SignDir 'pardocs-release.jks'
$propsOut = Join-Path $projectDir 'keystore.properties.local'

New-Item -ItemType Directory -Force -Path $SignDir | Out-Null
if (Test-Path $keystore) {
    throw "Ya existe $keystore. Borralo o usa -SignDir para no sobrescribir tu clave de firma."
}

# Contrasena aleatoria fuerte (32 caracteres), nunca se pasa por la linea de comandos.
$chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object 'System.Byte[]' 32
$rng.GetBytes($bytes)
$sb = New-Object System.Text.StringBuilder
foreach ($b in $bytes) { [void]$sb.Append($chars[$b % $chars.Length]) }
$pwd = $sb.ToString()

$pwdFile = Join-Path $SignDir '.storepass.tmp'
Set-Content -Path $pwdFile -Value $pwd -NoNewline -Encoding ascii

try {
    & $keytool -genkeypair -v `
        -keystore $keystore `
        -alias $KeyAlias `
        -keyalg RSA -keysize 4096 -validity 10000 `
        -storetype PKCS12 `
        -dname $DName `
        -storepass:file $pwdFile -keypass:file $pwdFile
    if ($LASTEXITCODE -ne 0) { throw "keytool fallo con codigo $LASTEXITCODE" }
}
finally {
    Remove-Item $pwdFile -Force -ErrorAction SilentlyContinue
}

# Barras normales para que el parser de .properties no se coma los backslashes.
$storePathForProps = $keystore -replace '\\', '/'

$props = @"
# ---------------------------------------------------------------------------
# FIRMA DE PRODUCCION - PARDOCS
# Ignorado por git (.gitignore). NUNCA lo subas a GitHub.
# Guarda copia de seguridad de este archivo Y del keystore.
# ---------------------------------------------------------------------------
storeFile=$storePathForProps
storePassword=$pwd
keyAlias=$KeyAlias
keyPassword=$pwd
"@
Set-Content -Path $propsOut -Value $props -Encoding ascii

Write-Host "Keystore:     $keystore"
Write-Host "Credenciales: $propsOut"
Write-Host "Alias:        $KeyAlias"
