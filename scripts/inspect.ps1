#Requires -Version 5.1
<#
.SYNOPSIS
    Nexus Flow quality-gate script (PowerShell).

.DESCRIPTION
    Sets JAVA_HOME, runs the full Gradle check task and prints a summary
    of PMD violations, Spotless status, and test results.

.EXAMPLE
    .\scripts\inspect.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent $scriptDir

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

Set-Location $repoRoot

Write-Host '========================================' -ForegroundColor Cyan
Write-Host ' Nexus Flow — quality gate'               -ForegroundColor Cyan
Write-Host '========================================'  -ForegroundColor Cyan
Write-Host "JAVA_HOME : $env:JAVA_HOME"
& java -version
Write-Host ''

# Run full check (SpotBugs excluded; run separately when its native libraries are available)
$gradleArgs = @(
    ':project:core:check',
    '-x', 'spotbugsMain',
    '-x', 'spotbugsTest',
    '--no-daemon',
    '--warning-mode', 'summary'
)

& .\gradlew.bat @gradleArgs
$buildStatus = $LASTEXITCODE

Write-Host ''
Write-Host '========================================'  -ForegroundColor Cyan
Write-Host ' Summary'                                  -ForegroundColor Cyan
Write-Host '========================================'  -ForegroundColor Cyan

# ── PMD ──────────────────────────────────────────────────────────────────────
function Get-PmdViolationCount([string]$xmlPath) {
    if (-not (Test-Path $xmlPath)) { return $null }
    $xml = [xml](Get-Content $xmlPath -Raw)
    $violations = $xml.SelectNodes('//*[local-name()="violation"]')
    return $violations.Count
}

$mainPmdXml = Join-Path $repoRoot 'project\core\build\reports\pmd\main.xml'
$testPmdXml = Join-Path $repoRoot 'project\core\build\reports\pmd\test.xml'

$mainViolations = Get-PmdViolationCount $mainPmdXml
if ($null -ne $mainViolations) {
    Write-Host "PMD violations (main):   $mainViolations"
} else {
    Write-Host 'PMD violations (main):   report not found'
}

$testViolations = Get-PmdViolationCount $testPmdXml
if ($null -ne $testViolations) {
    Write-Host "PMD violations (test):   $testViolations"
}

# ── Spotless ─────────────────────────────────────────────────────────────────
$spotlessDir = Join-Path $repoRoot 'project\core\build\reports\spotless'
$spotlessDirty = Test-Path $spotlessDir -PathType Container
if ($spotlessDirty) {
    $dirtyFiles = Get-ChildItem $spotlessDir -Filter '*.txt' -Recurse -ErrorAction SilentlyContinue
    if ($dirtyFiles) {
        Write-Host "Spotless:                violations found — run './gradlew :project:core:spotlessApply'" -ForegroundColor Yellow
    } else {
        Write-Host 'Spotless:                clean' -ForegroundColor Green
    }
} else {
    Write-Host 'Spotless:                clean' -ForegroundColor Green
}

# ── Tests ─────────────────────────────────────────────────────────────────────
$testResultsDir = Join-Path $repoRoot 'project\core\build\test-results\test'
$xmlFiles = Get-ChildItem $testResultsDir -Filter '*.xml' -Recurse -ErrorAction SilentlyContinue
if ($xmlFiles) {
    $total  = 0; $failed = 0; $errors = 0; $skipped = 0
    foreach ($f in $xmlFiles) {
        $xml      = [xml](Get-Content $f.FullName)
        $ts       = $xml.testsuite
        $total   += [int]$ts.tests
        $failed  += [int]$ts.failures
        $errors  += [int]$ts.errors
        $skipped += [int]$ts.skipped
    }
    $color = if ($failed -eq 0 -and $errors -eq 0) { 'Green' } else { 'Red' }
    Write-Host "Tests:                   total=$total  failed=$failed  errors=$errors  skipped=$skipped" -ForegroundColor $color
} else {
    Write-Host 'Tests:                   no results found' -ForegroundColor Yellow
}

Write-Host '----------------------------------------'
if ($buildStatus -eq 0) {
    Write-Host 'BUILD: PASSED' -ForegroundColor Green
} else {
    Write-Host "BUILD: FAILED (exit code $buildStatus)" -ForegroundColor Red
}
Write-Host '========================================'  -ForegroundColor Cyan

exit $buildStatus
