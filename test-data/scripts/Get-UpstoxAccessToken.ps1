# Loads the Upstox access token from api_tokens (same path as ApiTokenDbLoader /
# UpstoxConnectivityTest.token_isLoadedFromDatabase) and sets UPSTOX_ACCESS_TOKEN
# in the current PowerShell session.
#
# Prerequisites:
#   - TOKEN_ENCRYPTION_KEY set (User env var or in this session)
#   - psql on PATH, OR pass -EncryptedToken from a manual DB query
#
# Usage:
#   . .\test-data\scripts\Get-UpstoxAccessToken.ps1
#   $env:EXPIRY_DATE = "2026-07-07"
#   bash test-data/capture/capture_friday.sh

param(
    [string]$EncryptionKey = $env:TOKEN_ENCRYPTION_KEY,
    [string]$DbHost = "ep-green-snow-aozeabar.c-2.ap-southeast-1.aws.neon.tech",
    [string]$DbName = "zupptrade",
    [string]$DbUser = "zupp_app",
    [string]$DbPassword = $env:DB_PASSWORD,
    [string]$DbSchema = "zupptrade_dev",
    [string]$EncryptedToken
)

$ErrorActionPreference = "Stop"

function Decrypt-Aes256GcmToken {
    param(
        [Parameter(Mandatory)][string]$Base64Key,
        [Parameter(Mandatory)][string]$EncryptedBase64
    )

    $keyBytes = [Convert]::FromBase64String($Base64Key)
    if ($keyBytes.Length -ne 32) {
        throw "TOKEN_ENCRYPTION_KEY must decode to 32 bytes (256-bit AES key)."
    }

    $combined = [Convert]::FromBase64String($EncryptedBase64)
    if ($combined.Length -lt 28) {
        throw "Encrypted token payload is too short."
    }

    $iv = $combined[0..11]
    $cipherAndTag = $combined[12..($combined.Length - 1)]
    $tagLength = 16
    $cipherLength = $cipherAndTag.Length - $tagLength
    if ($cipherLength -le 0) {
        throw "Encrypted token payload missing GCM auth tag."
    }

    $cipherBytes = $cipherAndTag[0..($cipherLength - 1)]
    $tagBytes = $cipherAndTag[$cipherLength..($cipherAndTag.Length - 1)]
    $plainBytes = New-Object byte[] $cipherLength

    $aesGcm = [System.Security.Cryptography.AesGcm]::new($keyBytes)
    try {
        $aesGcm.Decrypt($iv, $cipherBytes, $tagBytes, $plainBytes)
    } finally {
        $aesGcm.Dispose()
    }

    return [System.Text.Encoding]::UTF8.GetString($plainBytes)
}

if ([string]::IsNullOrWhiteSpace($EncryptionKey)) {
    throw "TOKEN_ENCRYPTION_KEY is not set. Set it in User env vars or run: `$env:TOKEN_ENCRYPTION_KEY = 'your-base64-key'"
}

if ([string]::IsNullOrWhiteSpace($EncryptedToken)) {
    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        # Fallback: read password from gitignored application-local.yml if present
        $localYml = Join-Path $PSScriptRoot "..\..\agent1-market_analyst\src\main\resources\application-local.yml"
        if (Test-Path $localYml) {
            $match = Select-String -Path $localYml -Pattern '^\s*password:\s*(.+)$' | Select-Object -First 1
            if ($match) { $DbPassword = $match.Matches[0].Groups[1].Value.Trim() }
        }
    }

    if ([string]::IsNullOrWhiteSpace($DbPassword)) {
        throw "DB password not found. Set `$env:DB_PASSWORD or ensure agent1 application-local.yml exists."
    }

    $psql = Get-Command psql -ErrorAction SilentlyContinue
    if (-not $psql) {
        throw "psql not found on PATH. Install PostgreSQL client tools, or pass -EncryptedToken manually."
    }

    $env:PGPASSWORD = $DbPassword
    $sql = "SELECT encrypted_token FROM ${DbSchema}.api_tokens WHERE service = 'UPSTOX' ORDER BY fetched_at DESC LIMIT 1;"
    $EncryptedToken = & psql -h $DbHost -d $DbName -U $DbUser "sslmode=require" -t -A -c $sql
    $EncryptedToken = $EncryptedToken.Trim()
}

if ([string]::IsNullOrWhiteSpace($EncryptedToken)) {
    throw "No encrypted Upstox token in api_tokens. Run upstox-auth first."
}

$token = Decrypt-Aes256GcmToken -Base64Key $EncryptionKey -EncryptedBase64 $EncryptedToken

if ([string]::IsNullOrWhiteSpace($token) -or $token.Contains("PASTE_FRESH") -or $token.Length -le 50) {
    throw "Decrypted token looks invalid. Check TOKEN_ENCRYPTION_KEY matches upstox-auth."
}

$env:UPSTOX_ACCESS_TOKEN = $token
Write-Host "UPSTOX_ACCESS_TOKEN set in this session (length=$($token.Length))."
Write-Host "Run capture with:"
Write-Host '  $env:EXPIRY_DATE = "2026-07-07"'
Write-Host "  bash test-data/capture/capture_friday.sh"
