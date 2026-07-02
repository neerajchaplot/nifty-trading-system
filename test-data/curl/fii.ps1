# Upstox FII/DII API - Direct Test Script
$TOKEN = "eyJ0eXAiOiJKV1QiLCJrZXlfaWQiOiJza192MS4wIiwiYWxnIjoiSFMyNTYifQ.eyJzdWIiOiI1UUM3QkgiLCJqdGkiOiI2YTNmNDJiYTY1ZGQ2OTNmYjRiNWZmYjciLCJpc011bHRpQ2xpZW50IjpmYWxzZSwiaXNQbHVzUGxhbiI6dHJ1ZSwiaWF0IjoxNzgyNTMwNzQ2LCJpc3MiOiJ1ZGFwaS1nYXRld2F5LXNlcnZpY2UiLCJleHAiOjE3ODI1OTc2MDB9.4fH1ml9Nu2kgtlmwPVtXCItUK9-4ziA7Zvvth8fdoO4"

$headers = @{
    "Authorization" = "Bearer $TOKEN"
    "Accept"        = "application/json"
    "Api-Version"   = "2.0"
}

function Show-FiiEntries {
    param([string]$label, [string]$url)
    Write-Host ""
    Write-Host "========== $label ==========" -ForegroundColor Cyan
    Write-Host "URL: $url"
    try {
        $resp = Invoke-RestMethod -Uri $url -Headers $headers -Method GET
        if ($resp.status -ne "success") {
            Write-Host "ERROR: status=$($resp.status)" -ForegroundColor Red
            return
        }
        $key = "NSE_FO|INDEX_FUTURES"
        $entries = $resp.data.$key
        if ($null -eq $entries -or $entries.Count -eq 0) {
            $keys = ($resp.data | Get-Member -MemberType NoteProperty | Select-Object -ExpandProperty Name) -join ", "
            Write-Host "No entries for key '$key'. Keys in response: $keys" -ForegroundColor Yellow
            return
        }
        Write-Host "Total entries: $($entries.Count)"
        Write-Host "--- First 5 entries (index 0 = what code picks as latest) ---"
        $i = 0
        foreach ($e in $entries) {
            if ($i -ge 5) { break }
            $ts   = $e.time_stamp
            $date = ([DateTimeOffset]::FromUnixTimeMilliseconds($ts)).ToOffset([TimeSpan]::FromHours(5.5)).ToString("yyyy-MM-dd (ddd)")
            $net  = $e.buy_amount - $e.sell_amount
            Write-Host ("  [{0}] date={1}  buy={2}  sell={3}  net={4}  long={5}  short={6}" -f $i, $date, $e.buy_amount, $e.sell_amount, $net, $e.total_long_contracts, $e.total_short_contracts)
            $i++
        }
        $last     = $entries[$entries.Count - 1]
        $lastDate = ([DateTimeOffset]::FromUnixTimeMilliseconds($last.time_stamp)).ToOffset([TimeSpan]::FromHours(5.5)).ToString("yyyy-MM-dd (ddd)")
        Write-Host "--- Last entry (oldest) ---"
        Write-Host "  date=$lastDate  buy=$($last.buy_amount)  sell=$($last.sell_amount)"
    } catch {
        Write-Host "EXCEPTION: $_" -ForegroundColor Red
    }
}

# Test 1: from = 7 days ago (what old code sent)
$url1 = "https://api.upstox.com/v2/market/fii?data_type=NSE_FO%7CINDEX_FUTURES&interval=1D&from=2026-06-20"
Show-FiiEntries -label "TEST 1 - from=7 days ago (old code, 2026-06-20)" -url $url1

# Test 2: from = 14 days ago (current code after fix)
$url2 = "https://api.upstox.com/v2/market/fii?data_type=NSE_FO%7CINDEX_FUTURES&interval=1D&from=2026-06-13"
Show-FiiEntries -label "TEST 2 - from=14 days ago (current code, 2026-06-13)" -url $url2

# Test 3: from = today -- hypothesis: 'from' might be END date not START date
$url3 = "https://api.upstox.com/v2/market/fii?data_type=NSE_FO%7CINDEX_FUTURES&interval=1D&from=2026-06-27"
Show-FiiEntries -label "TEST 3 - from=today (2026-06-27) -- is 'from' actually an end date?" -url $url3

# Test 4: wide window - all of June
$url4 = "https://api.upstox.com/v2/market/fii?data_type=NSE_FO%7CINDEX_FUTURES&interval=1D&from=2026-06-01"
Show-FiiEntries -label "TEST 4 - from=2026-06-01 (wide window)" -url $url4

# Test 5: DII cash with from=today
Write-Host ""
Write-Host "========== TEST 5 - DII cash, from=today ==========" -ForegroundColor Cyan
$url5 = "https://api.upstox.com/v2/market/dii?data_type=NSE_EQ%7CCASH&interval=1D&from=2026-06-27"
Write-Host "URL: $url5"
try {
    $resp = Invoke-RestMethod -Uri $url5 -Headers $headers -Method GET
    $key = "NSE_EQ|CASH"
    $entries = $resp.data.$key
    if ($entries) {
        Write-Host "Total entries: $($entries.Count)"
        $i = 0
        foreach ($e in $entries) {
            if ($i -ge 3) { break }
            $date = ([DateTimeOffset]::FromUnixTimeMilliseconds($e.time_stamp)).ToOffset([TimeSpan]::FromHours(5.5)).ToString("yyyy-MM-dd (ddd)")
            $net  = $e.buy_amount - $e.sell_amount
            Write-Host ("  [{0}] date={1}  buy={2}  sell={3}  net={4}" -f $i, $date, $e.buy_amount, $e.sell_amount, $net)
            $i++
        }
    } else {
        $keys = ($resp.data | Get-Member -MemberType NoteProperty | Select-Object -ExpandProperty Name) -join ", "
        Write-Host "No DII entries. Keys: $keys" -ForegroundColor Yellow
    }
} catch {
    Write-Host "EXCEPTION: $_" -ForegroundColor Red
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
