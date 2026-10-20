# T11 评测：意图解析准确率
# 用法：powershell -File t11-eval.ps1 [-Limit 200]
param([int]$Limit = 200)
$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:8080"
$outDir = "C:\IDEA\sky-take-out\docs\verify"
$run = Get-Date -Format "MMddHHmmss"

$login = Invoke-RestMethod -Uri "$base/user/user/login" -Method Post -ContentType "application/json" -Body '{"code":"eval_user"}'
$token = $login.data.token

function Ask([string]$q) {
    $json = '{"query":"' + $q + '"}'
    try {
        # PS 5.1 会按 ANSI 解码 JSON 响应导致中文乱码，这里拿原始字节自行按 UTF-8 解码
        $resp = Invoke-WebRequest -Uri "$base/user/ai/recommendSync" -Method Post `
            -ContentType "application/json; charset=utf-8" -Headers @{authentication=$token} `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 60 -UseBasicParsing
        $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        $obj = $text | ConvertFrom-Json
        return $obj.data.parsed
    } catch {
        return $null
    }
}

function NormArr($v) {
    if ($null -eq $v) { return @() }
    return @($v | ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne "" } | Sort-Object)
}
function SameSet($a, $b) {
    $x = NormArr $a; $y = NormArr $b
    return (($x -join ',') -eq ($y -join ','))
}

# ---------------- 用例生成：170 条标准 + 30 条脏输入 ----------------
$spicyCases = @(
    @{ w = '微辣'; v = 1 }, @{ w = '中辣'; v = 2 }, @{ w = '特辣'; v = 3 },
    @{ w = '不辣'; v = 0 }, @{ w = '一点都不辣'; v = 0 }, @{ w = '微微辣'; v = 1 }
)
$peopleCases = @(
    @{ w = '一个人'; v = 1 }, @{ w = '两个人'; v = 2 }, @{ w = '三个人'; v = 3 }, @{ w = '四个人'; v = 4 }
)
$budgetCases = @(
    @{ w = '不超过40'; v = 40 }, @{ w = '预算50'; v = 50 }, @{ w = '不超过60'; v = 60 },
    @{ w = '人均80'; v = 80 }, @{ w = '100以内'; v = 100 }
)
$excludeCases = @(
    @{ w = '不要香菜'; v = @('香菜') }, @{ w = '别放葱'; v = @('葱') },
    @{ w = '不吃蒜'; v = @('蒜') }, @{ w = '不要香菜和葱'; v = @('香菜','葱') },
    @{ w = ''; v = @() }
)
$prefixes = @('', '我想', '帮我看看，', '你好，')
$suffixes = @('', '谢谢', '推荐一下', '快一点')

$rnd = New-Object System.Random(20261020)
$cases = New-Object System.Collections.ArrayList
$used = @{}
while ($cases.Count -lt 170) {
    $s = $spicyCases[$rnd.Next($spicyCases.Count)]
    $p = $peopleCases[$rnd.Next($peopleCases.Count)]
    $b = $budgetCases[$rnd.Next($budgetCases.Count)]
    $e = $excludeCases[$rnd.Next($excludeCases.Count)]
    $pre = $prefixes[$rnd.Next($prefixes.Count)]
    $suf = $suffixes[$rnd.Next($suffixes.Count)]
    $sep1 = if ($rnd.Next(2) -eq 0) { '，' } else { ' ' }
    $sep2 = if ($rnd.Next(2) -eq 0) { '，' } else { ' ' }
    $q = $pre + $p.w + $sep1 + '想吃' + $s.w + '的' + $sep2 + $b.w
    if ($e.w -ne '') { $q = $q + '，' + $e.w }
    if ($suf -ne '') { $q = $q + '，' + $suf }
    if ($used.ContainsKey($q)) { continue }
    $used[$q] = 1
    [void]$cases.Add([pscustomobject]@{
        query = $q; spicy = $s.v; people = $p.v; budget = $b.v; exclude = $e.v; dirty = $false
    })
}

# 30 条脏输入：错别字 / 语音碎片 / 口语 / 同音字
$dirty = @(
    @{ q = '来个宫爆鸡丁 不要香采'; s = $null; p = $null; b = $null; e = @('香菜') },
    @{ q = '我要个那个水煮鱼 两个人'; s = $null; p = 2; b = $null; e = @() },
    @{ q = '嗯 就是 想吃辣的 不要聪'; s = $null; p = $null; b = $null; e = @('葱') },
    @{ q = '两各人 吃点辣得 六十以内'; s = $null; p = 2; b = 60; e = @() },
    @{ q = '不迟香菜 来点轻淡的'; s = 0; p = $null; b = $null; e = @('香菜') },
    @{ q = '三个人 微辣 不要蒜 预算一百'; s = 1; p = 3; b = 100; e = @('蒜') },
    @{ q = '特辣 特辣 就要特辣'; s = 3; p = $null; b = $null; e = @() },
    @{ q = '一个人吃 不超五十 别放香菜'; s = $null; p = 1; b = 50; e = @('香菜') },
    @{ q = '中辣就行 四个人 八十块以内'; s = 2; p = 4; b = 80; e = @() },
    @{ q = '不要香采和冲 两个人'; s = $null; p = 2; b = $null; e = @('香菜') },
    @{ q = '我我我想吃辣得 三个人'; s = $null; p = 3; b = $null; e = @() },
    @{ q = '特辣 一百以内 不要葱'; s = 3; p = $null; b = 100; e = @('葱') },
    @{ q = '随便来点 两个人 五十'; s = $null; p = 2; b = 50; e = @() },
    @{ q = '清淡点 一个人 四十'; s = 0; p = 1; b = 40; e = @() },
    @{ q = '要微辣 不要香菜 三个人 预算六十'; s = 1; p = 3; b = 60; e = @('香菜') },
    @{ q = '来个那个 嗯 辣的 两个人'; s = $null; p = 2; b = $null; e = @() },
    @{ q = '不辣 四十以内 一个人'; s = 0; p = 1; b = 40; e = @() },
    @{ q = '特辣 不要蒜和香菜 四个人'; s = 3; p = 4; b = $null; e = @('蒜','香菜') },
    @{ q = '中辣 两各人 不要香采'; s = 2; p = 2; b = $null; e = @('香菜') },
    @{ q = '预算一百 三个人 微辣 不要葱'; s = 1; p = 3; b = 100; e = @('葱') },
    @{ q = '我想吃辣 但不要太辣 两个人'; s = $null; p = 2; b = $null; e = @() },
    @{ q = '六是块 两个人 不辣'; s = 0; p = 2; b = 60; e = @() },
    @{ q = '不要冲 特辣 八十'; s = 3; p = $null; b = 80; e = @('葱') },
    @{ q = '一个仍 微辣 四十'; s = 1; p = 1; b = 40; e = @() },
    @{ q = '四个人 特辣 一百以内 不要香采'; s = 3; p = 4; b = 100; e = @('香菜') },
    @{ q = '轻淡的 两个人 五十以内'; s = 0; p = 2; b = 50; e = @() },
    @{ q = '中辣 不要蒜 三个人 预算八十'; s = 2; p = 3; b = 80; e = @('蒜') },
    @{ q = '辣一点的 两个人 六十'; s = $null; p = 2; b = 60; e = @() },
    @{ q = '不辣 不要葱 两个人 四十'; s = 0; p = 2; b = 40; e = @('葱') },
    @{ q = '特辣特辣 一个人 一百'; s = 3; p = 1; b = 100; e = @() }
)
foreach ($d in $dirty) {
    [void]$cases.Add([pscustomobject]@{ query = $d.q; spicy = $d.s; people = $d.p; budget = $d.b; exclude = $d.e; dirty = $true })
}

if ($Limit -lt $cases.Count) { $cases = $cases[0..($Limit - 1)] }

# ---------------- 逐条评测 ----------------
$results = New-Object System.Collections.ArrayList
$i = 0
foreach ($c in $cases) {
    $i++
    $parsed = Ask $c.query
    if ($null -eq $parsed) {
        [void]$results.Add([pscustomobject]@{ query=$c.query; dirty=$c.dirty; parsedOk=$false;
            spicyOk=$false; peopleOk=$false; budgetOk=$false; excludeOk=$false; allOk=$false; parsed='' })
        continue
    }
    $spicyOk = ("$($parsed.spicy)" -eq "$($c.spicy)")
    $peopleOk = ("$($parsed.people)" -eq "$($c.people)")
    $budgetOk = ("$($parsed.budgetPerPerson)" -eq "$($c.budget)")
    $excludeOk = SameSet $parsed.excludeIngredients $c.exclude
    $allOk = $spicyOk -and $peopleOk -and $budgetOk -and $excludeOk
    [void]$results.Add([pscustomobject]@{
        query=$c.query; dirty=$c.dirty; parsedOk=$true
        spicyOk=$spicyOk; peopleOk=$peopleOk; budgetOk=$budgetOk; excludeOk=$excludeOk; allOk=$allOk
        parsed=($parsed | ConvertTo-Json -Compress)
    })
    if ($i % 20 -eq 0) { Write-Host "  ... $i / $($cases.Count)" }
}

# ---------------- 统计 ----------------
function Rate($items, $prop) {
    if ($items.Count -eq 0) { return 0 }
    $ok = @($items | Where-Object { $_.$prop -eq $true }).Count
    return [math]::Round(100.0 * $ok / $items.Count, 2)
}
$std = @($results | Where-Object { -not $_.dirty })
$drt = @($results | Where-Object { $_.dirty })
$all = @($results)

$csv = Join-Path $outDir ("t11-eval-result-$run.csv")
$results | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8

Write-Host ""
Write-Host "================ T11 意图解析评测 ================"
Write-Host ("样本总数        : {0}（标准 {1} / 脏输入 {2}）" -f $all.Count, $std.Count, $drt.Count)
Write-Host ("整体准确率(4字段全对): {0}%" -f (Rate $all 'allOk'))
Write-Host ("  标准集         : {0}%" -f (Rate $std 'allOk'))
Write-Host ("  脏输入集       : {0}%" -f (Rate $drt 'allOk'))
Write-Host ("字段级(全体)    : spicy={0}%  people={1}%  budget={2}%  exclude={3}%" -f `
    (Rate $all 'spicyOk'), (Rate $all 'peopleOk'), (Rate $all 'budgetOk'), (Rate $all 'excludeOk'))
Write-Host ("字段级(脏输入)  : spicy={0}%  people={1}%  budget={2}%  exclude={3}%" -f `
    (Rate $drt 'spicyOk'), (Rate $drt 'peopleOk'), (Rate $drt 'budgetOk'), (Rate $drt 'excludeOk'))
Write-Host ("失败样本前 10 条:")
$bad = @($results | Where-Object { $_.allOk -eq $false } | Select-Object -First 10)
foreach ($b in $bad) {
    $tag = '标'
    if ($b.dirty) { $tag = '脏' }
    Write-Host ("  [{0}] {1}" -f $tag, $b.query)
    Write-Host ("        parsed={0}" -f $b.parsed)
}
Write-Host ("CSV: {0}" -f $csv)
