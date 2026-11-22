# Builds everything and collects the jars and the prompts into one folder: dist/
#   dist/code-intelligence-engine.jar   the one jar you run (all modules inside)
#   dist/lib/                           the module jars, for reference or embedding
#   dist/prompts/                       the AI driver prompt (Copilot prompt file and Claude skill)
#   dist/run-pipeline.ps1               a small launcher
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$flags = @('package'); if ($SkipTests) { $flags += '-DskipTests' }
mvn @flags
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
if (Test-Path dist) { Remove-Item dist -Recurse -Force }
New-Item -ItemType Directory dist, dist/lib, dist/prompts | Out-Null
Copy-Item cli/target/code-intelligence-engine.jar dist/
Get-ChildItem -Directory | Where-Object { Test-Path "$($_.FullName)/target" } | ForEach-Object {
    Get-ChildItem "$($_.FullName)/target/*.jar" | Where-Object { $_.Name -notlike 'original-*' -and $_.Name -notlike '*-shaded.jar' -and $_.Name -ne 'code-intelligence-engine.jar' } |
        Copy-Item -Destination dist/lib
}
Copy-Item prompts/functional-docs.prompt.md dist/prompts/
Copy-Item prompts/skill dist/prompts/skill -Recurse
@'
param([Parameter(Mandatory)][string[]]$Source, [string]$Bytecode, [string]$Output = 'analysis-output')
$args2 = @('-jar', "$PSScriptRoot/code-intelligence-engine.jar", 'pipeline', '--output', $Output)
foreach ($s in $Source) { $args2 += @('--source', $s) }
if ($Bytecode) { $args2 += @('--bytecode', $Bytecode) }
java @args2
'@ | Set-Content dist/run-pipeline.ps1
Write-Host "dist ready:" (Get-ChildItem dist -Recurse -File | Measure-Object).Count "files"
