[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$script:HadFailure = $false
$script:HadBlocked = $false
$script:StageResults = New-Object System.Collections.Generic.List[string]

function Write-StageResult {
    param(
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Message
    )

    $line = "[{0}] {1} - {2}" -f $Phase, $Status, $Message
    $script:StageResults.Add($line)
    Write-Host $line

    if ($Status -eq "FAIL" -or $Status -eq "KNOWN_SOURCE_DEFECT" -or $Status -eq "CLEANUP_FAILED") {
        $script:HadFailure = $true
    }
    if ($Status -eq "BLOCKED") {
        $script:HadBlocked = $true
    }
}

function Get-EnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$DefaultValue
    )

    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $DefaultValue
    }
    return $value
}

function Test-TcpPort {
    param(
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMilliseconds = 1500
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $asyncResult = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $asyncResult.AsyncWaitHandle.WaitOne($TimeoutMilliseconds, $false)) {
            return $false
        }
        $client.EndConnect($asyncResult)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Close()
    }
}

function Get-SafeErrorMessage {
    param([Parameter(Mandatory = $true)]$ErrorValue)

    $message = $ErrorValue.ToString().Replace("`r", " ").Replace("`n", " ")
    if ($message.Length -gt 240) {
        return $message.Substring(0, 240)
    }
    return $message
}

function Resolve-Executable {
    param(
        [string]$ConfiguredPath,
        [string[]]$CommandNames,
        [string[]]$FallbackPaths
    )

    if (-not [string]::IsNullOrWhiteSpace($ConfiguredPath) -and (Test-Path -LiteralPath $ConfiguredPath)) {
        return (Resolve-Path -LiteralPath $ConfiguredPath).Path
    }

    foreach ($commandName in $CommandNames) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
    }

    foreach ($fallbackPath in $FallbackPaths) {
        if (Test-Path -LiteralPath $fallbackPath) {
            return $fallbackPath
        }
    }

    return $null
}

function Invoke-ApiGet {
    param([Parameter(Mandatory = $true)][string]$Uri)

    return Invoke-RestMethod -Method Get -Uri $Uri -TimeoutSec 10
}

function Test-ApiSuccess {
    param($Response)

    return $null -ne $Response -and $null -ne $Response.code -and [int]$Response.code -eq 1
}

$mutex = New-Object System.Threading.Mutex($false, "Local\SkyTakeOutUserFlowSmokeTest")
$mutexHeld = $false
$tempSourceCreated = $false
$tempDirectoryCreated = $false
$initialGitStatus = $null
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\..\.."))
$tempTestDirectory = Join-Path $projectRoot "sky-server\src\test\java\com\sky\skill"
$tempTestSource = Join-Path $tempTestDirectory "UserFlowOrderSmokeTest.java"
$templateSource = Join-Path $PSScriptRoot "UserFlowOrderSmokeTest.java.template"
$compiledTestClass = Join-Path $projectRoot "sky-server\target\test-classes\com\sky\skill\UserFlowOrderSmokeTest.class"
$surefireText = Join-Path $projectRoot "sky-server\target\surefire-reports\com.sky.skill.UserFlowOrderSmokeTest.txt"
$surefireXml = Join-Path $projectRoot "sky-server\target\surefire-reports\TEST-com.sky.skill.UserFlowOrderSmokeTest.xml"

try {
    $mutexHeld = $mutex.WaitOne(0)
    if (-not $mutexHeld) {
        Write-StageResult "PRECHECK" "BLOCKED" "another user-flow smoke test is already running"
        throw "SKY_SMOKE_ABORT"
    }

    $requiredFiles = @(
        (Join-Path $projectRoot "pom.xml"),
        (Join-Path $projectRoot "sky-server\pom.xml"),
        (Join-Path $projectRoot "sky-server\src\main\java\com\sky\SkyApplication.java"),
        $templateSource
    )
    foreach ($requiredFile in $requiredFiles) {
        if (-not (Test-Path -LiteralPath $requiredFile)) {
            Write-StageResult "PRECHECK" "BLOCKED" "required project or skill file is missing"
            throw "SKY_SMOKE_ABORT"
        }
    }

    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) {
        Write-StageResult "PRECHECK" "BLOCKED" "git is not available"
        throw "SKY_SMOKE_ABORT"
    }

    $initialGitStatus = ((& git -C $projectRoot status --short 2>&1) | Out-String).TrimEnd()
    if ($LASTEXITCODE -ne 0) {
        Write-StageResult "PRECHECK" "BLOCKED" "project root is not a readable Git working tree"
        throw "SKY_SMOKE_ABORT"
    }

    if (Test-Path -LiteralPath $tempTestSource) {
        Write-StageResult "PRECHECK" "BLOCKED" "temporary JUnit target already exists; inspect it before retrying"
        throw "SKY_SMOKE_ABORT"
    }

    $mysqlHost = Get-EnvironmentValue "SKY_SMOKE_MYSQL_HOST" "127.0.0.1"
    $mysqlPort = [int](Get-EnvironmentValue "SKY_SMOKE_MYSQL_PORT" "3306")
    $redisHost = Get-EnvironmentValue "SKY_SMOKE_REDIS_HOST" "127.0.0.1"
    $redisPort = [int](Get-EnvironmentValue "SKY_SMOKE_REDIS_PORT" "6379")
    $redisDb = [int](Get-EnvironmentValue "SKY_SMOKE_REDIS_DB" "1")
    $baseUrl = (Get-EnvironmentValue "SKY_SMOKE_BASE_URL" "http://127.0.0.1:8080").TrimEnd('/')

    $mysqlAvailable = Test-TcpPort $mysqlHost $mysqlPort
    $redisAvailable = Test-TcpPort $redisHost $redisPort

    try {
        $baseUri = New-Object System.Uri($baseUrl)
        $httpPort = $baseUri.Port
        $httpAvailable = Test-TcpPort $baseUri.Host $httpPort
    }
    catch {
        $httpAvailable = $false
    }

    if ($mysqlAvailable) {
        Write-StageResult "PRECHECK" "PASS" "MySQL is reachable and the working tree snapshot was recorded"
    }
    else {
        Write-StageResult "PRECHECK" "BLOCKED" "MySQL is not reachable; order integration test cannot run"
    }

    $dishCategoryId = $null
    $setmealCategoryId = $null
    $setmealId = $null

    if (-not $httpAvailable) {
        Write-StageResult "ADMIN_LOGIN" "BLOCKED" "application HTTP endpoint is not reachable"
        Write-StageResult "USER_CATALOG" "BLOCKED" "application HTTP endpoint is not reachable"
    }
    else {
        $adminUsername = [Environment]::GetEnvironmentVariable("SKY_SMOKE_ADMIN_USERNAME")
        $adminPassword = [Environment]::GetEnvironmentVariable("SKY_SMOKE_ADMIN_PASSWORD")

        if ([string]::IsNullOrWhiteSpace($adminUsername) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
            Write-StageResult "ADMIN_LOGIN" "BLOCKED" "set SKY_SMOKE_ADMIN_USERNAME and SKY_SMOKE_ADMIN_PASSWORD to test admin authentication"
        }
        else {
            try {
                $loginBody = @{
                    username = $adminUsername
                    password = $adminPassword
                } | ConvertTo-Json -Compress
                $loginResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/admin/employee/login" `
                    -ContentType "application/json" -Body $loginBody -TimeoutSec 10

                if (-not (Test-ApiSuccess $loginResponse) -or $null -eq $loginResponse.data `
                        -or [string]::IsNullOrWhiteSpace([string]$loginResponse.data.token)) {
                    Write-StageResult "ADMIN_LOGIN" "FAIL" "login response did not contain a successful result and token"
                }
                else {
                    $adminHeaders = @{ token = [string]$loginResponse.data.token }
                    $adminProbe = Invoke-RestMethod -Method Get `
                        -Uri "$baseUrl/admin/dish/page?page=1&pageSize=1" `
                        -Headers $adminHeaders -TimeoutSec 10
                    if (Test-ApiSuccess $adminProbe) {
                        Write-StageResult "ADMIN_LOGIN" "PASS" "real login token accessed a protected read-only admin endpoint"
                    }
                    else {
                        Write-StageResult "ADMIN_LOGIN" "FAIL" "protected admin probe returned an unsuccessful result"
                    }
                }
            }
            catch {
                Write-StageResult "ADMIN_LOGIN" "FAIL" (Get-SafeErrorMessage $_.Exception)
            }
        }

        try {
            $dishCategoriesResponse = Invoke-ApiGet "$baseUrl/user/category/list?type=1"
            $setmealCategoriesResponse = Invoke-ApiGet "$baseUrl/user/category/list?type=2"
            if (-not (Test-ApiSuccess $dishCategoriesResponse) -or -not (Test-ApiSuccess $setmealCategoriesResponse)) {
                throw "category endpoint returned an unsuccessful result"
            }

            $dishCategories = @($dishCategoriesResponse.data)
            $setmealCategories = @($setmealCategoriesResponse.data)
            if ($dishCategories.Count -gt 0) {
                $dishCategoryId = [long]$dishCategories[0].id
                $dishResponse = Invoke-ApiGet "$baseUrl/user/dish/list?categoryId=$dishCategoryId"
                if (-not (Test-ApiSuccess $dishResponse)) {
                    throw "dish endpoint returned an unsuccessful result"
                }
            }
            if ($setmealCategories.Count -gt 0) {
                $setmealCategoryId = [long]$setmealCategories[0].id
                $setmealResponse = Invoke-ApiGet "$baseUrl/user/setmeal/list?categoryId=$setmealCategoryId"
                if (-not (Test-ApiSuccess $setmealResponse)) {
                    throw "setmeal endpoint returned an unsuccessful result"
                }
                $setmeals = @($setmealResponse.data)
                if ($setmeals.Count -gt 0) {
                    $setmealId = [long]$setmeals[0].id
                    $setmealDishResponse = Invoke-ApiGet "$baseUrl/user/setmeal/dish/$setmealId"
                    if (-not (Test-ApiSuccess $setmealDishResponse)) {
                        throw "setmeal dish endpoint returned an unsuccessful result"
                    }
                }
            }

            if ($null -eq $dishCategoryId -and $null -eq $setmealCategoryId) {
                Write-StageResult "USER_CATALOG" "BLOCKED" "no active dish or setmeal category is available"
            }
            else {
                Write-StageResult "USER_CATALOG" "PASS" "category, dish, setmeal, and available setmeal-detail reads succeeded"
            }
        }
        catch {
            Write-StageResult "USER_CATALOG" "FAIL" (Get-SafeErrorMessage $_.Exception)
        }
    }

    if (-not $redisAvailable) {
        Write-StageResult "REDIS_CACHE" "BLOCKED" "Redis is not reachable"
    }
    elseif (-not $httpAvailable) {
        Write-StageResult "REDIS_CACHE" "BLOCKED" "HTTP reads could not populate or validate cache entries"
    }
    elseif ($null -eq $dishCategoryId -and $null -eq $setmealCategoryId) {
        Write-StageResult "REDIS_CACHE" "SKIP" "no category ID was available for an exact cache-key check"
    }
    else {
        $redisCli = Resolve-Executable `
            ([Environment]::GetEnvironmentVariable("SKY_SMOKE_REDIS_CLI")) `
            @("redis-cli.exe", "redis-cli") `
            @("C:\Program Files\Redis\redis-cli.exe")

        if ($null -eq $redisCli) {
            Write-StageResult "REDIS_CACHE" "BLOCKED" "redis-cli is not available for read-only key and TTL checks"
        }
        else {
            $previousRedisAuth = [Environment]::GetEnvironmentVariable("REDISCLI_AUTH")
            $configuredRedisPassword = [Environment]::GetEnvironmentVariable("SKY_SMOKE_REDIS_PASSWORD")
            try {
                if (-not [string]::IsNullOrWhiteSpace($configuredRedisPassword)) {
                    [Environment]::SetEnvironmentVariable("REDISCLI_AUTH", $configuredRedisPassword)
                }

                $keysChecked = 0
                $keysValid = 0
                if ($null -ne $dishCategoryId) {
                    [void](Invoke-ApiGet "$baseUrl/user/dish/list?categoryId=$dishCategoryId")
                    $dishKey = "dishCache::$dishCategoryId"
                    $dishExists = [int]((& $redisCli -h $redisHost -p $redisPort -n $redisDb EXISTS $dishKey) | Select-Object -Last 1)
                    $dishTtl = [int]((& $redisCli -h $redisHost -p $redisPort -n $redisDb TTL $dishKey) | Select-Object -Last 1)
                    $keysChecked++
                    if ($dishExists -eq 1 -and $dishTtl -gt 0 -and $dishTtl -le 1810) {
                        $keysValid++
                    }
                }
                if ($null -ne $setmealCategoryId) {
                    [void](Invoke-ApiGet "$baseUrl/user/setmeal/list?categoryId=$setmealCategoryId")
                    $setmealKey = "setmealCache::$setmealCategoryId"
                    $setmealExists = [int]((& $redisCli -h $redisHost -p $redisPort -n $redisDb EXISTS $setmealKey) | Select-Object -Last 1)
                    $setmealTtl = [int]((& $redisCli -h $redisHost -p $redisPort -n $redisDb TTL $setmealKey) | Select-Object -Last 1)
                    $keysChecked++
                    if ($setmealExists -eq 1 -and $setmealTtl -gt 0 -and $setmealTtl -le 1810) {
                        $keysValid++
                    }
                }

                if ($keysChecked -gt 0 -and $keysChecked -eq $keysValid) {
                    Write-StageResult "REDIS_CACHE" "PASS" "exact dish/setmeal cache keys exist with positive 30-minute TTL values"
                }
                else {
                    Write-StageResult "REDIS_CACHE" "FAIL" "one or more expected exact cache keys or TTL values were invalid"
                }
            }
            catch {
                Write-StageResult "REDIS_CACHE" "FAIL" (Get-SafeErrorMessage $_.Exception)
            }
            finally {
                if ([string]::IsNullOrWhiteSpace($previousRedisAuth)) {
                    [Environment]::SetEnvironmentVariable("REDISCLI_AUTH", $null)
                }
                else {
                    [Environment]::SetEnvironmentVariable("REDISCLI_AUTH", $previousRedisAuth)
                }
            }
        }
    }

    if (-not $mysqlAvailable) {
        Write-StageResult "CART_ADDRESS_ORDER" "BLOCKED" "MySQL is unavailable"
        Write-StageResult "CLEANUP" "SKIP" "no temporary order fixture was created"
        Write-StageResult "RESIDUE_CHECK" "SKIP" "no temporary order fixture was created"
    }
    else {
        $maven = Resolve-Executable `
            ([Environment]::GetEnvironmentVariable("SKY_SMOKE_MVN")) `
            @("mvn.cmd", "mvn") `
            @("C:\apache-maven-3.9.16\bin\mvn.cmd")

        if ($null -eq $maven) {
            Write-StageResult "CART_ADDRESS_ORDER" "BLOCKED" "Maven is not available"
            Write-StageResult "CLEANUP" "SKIP" "no temporary order fixture was created"
            Write-StageResult "RESIDUE_CHECK" "SKIP" "no temporary order fixture was created"
        }
        else {
            if (-not (Test-Path -LiteralPath $tempTestDirectory)) {
                New-Item -ItemType Directory -Path $tempTestDirectory | Out-Null
                $tempDirectoryCreated = $true
            }
            [System.IO.File]::Copy($templateSource, $tempTestSource, $false)
            $tempSourceCreated = $true

            $mavenLines = @(
                & $maven -q -f (Join-Path $projectRoot "pom.xml") -pl sky-server -am `
                    "-Dtest=com.sky.skill.UserFlowOrderSmokeTest" `
                    "-Dsurefire.failIfNoSpecifiedTests=false" test 2>&1 | ForEach-Object { $_.ToString() }
            )
            $mavenExitCode = $LASTEXITCODE
            $structuredLines = @($mavenLines | Where-Object { $_ -like "*SKY_SMOKE_STATUS=*" })

            if ($mavenExitCode -eq 0) {
                Write-StageResult "CART_ADDRESS_ORDER" "PASS" "temporary user, address, cart, order, and detail assertions passed"
            }
            elseif (($structuredLines -join "`n") -match "KNOWN_SOURCE_DEFECT" -or ($mavenLines -join "`n") -match "Parameter 'ad' not found") {
                Write-StageResult "CART_ADDRESS_ORDER" "KNOWN_SOURCE_DEFECT" "OrderDetailMapper.xml:9 references ad.amount instead of od.amount"
            }
            elseif (($structuredLines -join "`n") -match "\|BLOCKED\|") {
                Write-StageResult "CART_ADDRESS_ORDER" "BLOCKED" "the fixed integration test reported an unmet data precondition"
            }
            else {
                $safeErrorLine = $mavenLines | Where-Object {
                    $_ -match "(^|\s)(ERROR|Exception|Caused by:)" -and
                    $_ -notmatch "password|token|access.?key|secret"
                } | Select-Object -Last 1
                if ([string]::IsNullOrWhiteSpace([string]$safeErrorLine)) {
                    $safeErrorLine = "specified Maven integration test failed"
                }
                Write-StageResult "CART_ADDRESS_ORDER" "FAIL" (Get-SafeErrorMessage $safeErrorLine)
            }

            $cleanupLine = $structuredLines | Where-Object { $_ -match "SKY_SMOKE_STATUS=CLEANUP\|" } | Select-Object -Last 1
            $residueLine = $structuredLines | Where-Object { $_ -match "SKY_SMOKE_STATUS=RESIDUE_CHECK\|" } | Select-Object -Last 1

            if ([string]$cleanupLine -match "\|PASS\|") {
                Write-StageResult "CLEANUP" "PASS" "temporary database rows were removed with strict ID and marker constraints"
            }
            elseif ([string]$cleanupLine -match "CLEANUP_FAILED") {
                Write-StageResult "CLEANUP" "CLEANUP_FAILED" "the integration test could not safely remove every temporary row"
            }
            else {
                Write-StageResult "CLEANUP" "BLOCKED" "the test did not reach a verifiable cleanup report"
            }

            if ([string]$residueLine -match "\|PASS\|") {
                Write-StageResult "RESIDUE_CHECK" "PASS" "temporary user-flow marker counts are zero"
            }
            elseif ([string]$cleanupLine -match "CLEANUP_FAILED") {
                Write-StageResult "RESIDUE_CHECK" "FAIL" "temporary residue may remain; inspect the strict cleanup failure"
            }
            else {
                Write-StageResult "RESIDUE_CHECK" "BLOCKED" "no verified residue report was produced"
            }
        }
    }
}
catch {
    if ($_.Exception.Message -ne "SKY_SMOKE_ABORT") {
        Write-StageResult "SUMMARY" "FAIL" (Get-SafeErrorMessage $_.Exception)
    }
}
finally {
    if ($tempSourceCreated -and (Test-Path -LiteralPath $tempTestSource)) {
        Remove-Item -LiteralPath $tempTestSource -Force
    }
    foreach ($artifact in @($compiledTestClass, $surefireText, $surefireXml)) {
        if (Test-Path -LiteralPath $artifact) {
            Remove-Item -LiteralPath $artifact -Force
        }
    }
    if ($tempDirectoryCreated -and (Test-Path -LiteralPath $tempTestDirectory)) {
        $remainingFiles = @(Get-ChildItem -LiteralPath $tempTestDirectory -Force)
        if ($remainingFiles.Count -eq 0) {
            Remove-Item -LiteralPath $tempTestDirectory -Force
        }
    }

    if ($null -ne $initialGitStatus) {
        try {
            $finalGitStatus = ((& git -C $projectRoot status --short 2>&1) | Out-String).TrimEnd()
            if ($LASTEXITCODE -ne 0) {
                Write-StageResult "WORKTREE" "FAIL" "could not read final Git working tree state"
            }
            elseif ($finalGitStatus -ne $initialGitStatus) {
                Write-StageResult "WORKTREE" "FAIL" "working tree differs from the pre-test snapshot; no automatic recovery was attempted"
            }
            else {
                Write-StageResult "WORKTREE" "PASS" "working tree matches the pre-test snapshot"
            }
        }
        catch {
            Write-StageResult "WORKTREE" "FAIL" (Get-SafeErrorMessage $_.Exception)
        }
    }

    if ($mutexHeld) {
        try {
            $mutex.ReleaseMutex()
        }
        catch {
        }
    }
    $mutex.Dispose()
}

if ($script:HadFailure) {
    Write-StageResult "SUMMARY" "FAIL" "one or more smoke-test stages failed"
    exit 1
}
elseif ($script:HadBlocked) {
    Write-StageResult "SUMMARY" "BLOCKED" "completed all safe stages, but one or more prerequisites were unavailable"
    exit 2
}
else {
    Write-StageResult "SUMMARY" "PASS" "all smoke-test stages passed"
    exit 0
}
