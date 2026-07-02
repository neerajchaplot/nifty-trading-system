# Marketaux API - Direct Test Script
# Tests the exact call made by MarketauxClient.java

$API_KEY = "EPUBFxfKt8k8TOaZsOxtKCTeOHjmlWDCXu2bFoXH"
$BASE_URL = "https://api.marketaux.com"

Write-Host ""
Write-Host "========== TEST 1 - Exact call the code makes ==========" -ForegroundColor Cyan
$url1 = "$BASE_URL/v1/news/all?symbols=%5ENSEI&api_token=$API_KEY&language=en&limit=3"
Write-Host "URL: $url1"
try {
    $resp = Invoke-RestMethod -Uri $url1 -Method GET
    Write-Host "meta.found: $($resp.meta.found)"
    Write-Host "meta.returned: $($resp.meta.returned)"
    Write-Host "data count: $($resp.data.Count)"

    if ($resp.data.Count -eq 0) {
        Write-Host "NO ARTICLES RETURNED -- check quota or symbol spelling" -ForegroundColor Red
    } else {
        $i = 0
        foreach ($article in $resp.data) {
            Write-Host ""
            Write-Host "  Article [$i]: $($article.title)" -ForegroundColor Yellow
            Write-Host "  published_at: $($article.published_at)"
            Write-Host "  source: $($article.source)"
            if ($null -eq $article.entities -or $article.entities.Count -eq 0) {
                Write-Host "  entities: NONE" -ForegroundColor Red
            } else {
                Write-Host "  entities ($($article.entities.Count) total):"
                foreach ($e in $article.entities) {
                    $marker = if ($e.symbol -eq "^NSEI") { " <-- THIS IS WHAT CODE LOOKS FOR" } else { "" }
                    Write-Host ("    symbol={0,-15} sentiment_score={1}{2}" -f $e.symbol, $e.sentiment_score, $marker)
                }
                $nseiEntity = $article.entities | Where-Object { $_.symbol -eq "^NSEI" }
                if ($null -eq $nseiEntity) {
                    Write-Host "  >> NO ^NSEI entity in this article -- code scores this as null" -ForegroundColor Red
                } else {
                    Write-Host "  >> ^NSEI entity found: score=$($nseiEntity.sentiment_score)" -ForegroundColor Green
                }
            }
            $i++
        }
    }
} catch {
    Write-Host "EXCEPTION: $_" -ForegroundColor Red
    Write-Host "Status code may indicate quota exhausted (429) or bad key (401)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========== TEST 2 - Check remaining quota ==========" -ForegroundColor Cyan
$url2 = "$BASE_URL/v1/news/all?symbols=%5ENSEI&api_token=$API_KEY&language=en&limit=1"
Write-Host "URL: $url2"
try {
    $resp2 = Invoke-RestMethod -Uri $url2 -Method GET -ResponseHeadersVariable respHeaders
    Write-Host "meta.requests_remaining: $($resp2.meta.requests_remaining)" -ForegroundColor $(if ($resp2.meta.requests_remaining -lt 10) { "Red" } else { "Green" })
    Write-Host "meta.found: $($resp2.meta.found)"
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    Write-Host "HTTP $statusCode -- $($_.Exception.Message)" -ForegroundColor Red
    if ($statusCode -eq 429) {
        Write-Host ">> QUOTA EXHAUSTED -- free tier is 100 requests/day" -ForegroundColor Red
    } elseif ($statusCode -eq 401) {
        Write-Host ">> INVALID API KEY" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
