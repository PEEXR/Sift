<#
.SYNOPSIS
    Dispatches a single sequential task to a worker agent (Gemini in Antigravity or OpenCode).

.DESCRIPTION
    Used by Claude Code (or developers) to delegate code implementation, refactoring,
    or boilerplate tasks to Gemini via Antigravity CLI (agy) or local models via OpenCode.

.PARAMETER Engine
    Worker engine to use: 'gemini' (default, uses agy) or 'opencode'.

.PARAMETER Model
    Model name override. Default for gemini is 'gemini-3.8-flash-high'.

.PARAMETER TaskFile
    Path to the task specification markdown file. Default is '.agent/tasks/active_task.md'.

.PARAMETER Prompt
    Optional short prompt. If provided without a TaskFile, writes it into active_task.md.

.EXAMPLE
    .\scripts\run-worker.ps1 -Engine gemini
    .\scripts\run-worker.ps1 -Engine opencode
    .\scripts\run-worker.ps1 -Engine gemini -Model gemini-3.1-pro-high
#>

[CmdletBinding()]
param(
    [ValidateSet("gemini", "opencode")]
    [string]$Engine = "gemini",

    [string]$Model = "",

    [string]$TaskFile = ".agent/tasks/active_task.md",

    [string]$Prompt = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

# Ensure .agent/tasks directory exists
$TasksDir = Join-Path $RepoRoot ".agent/tasks"
if (-not (Test-Path $TasksDir)) {
    New-Item -ItemType Directory -Path $TasksDir -Force | Out-Null
}

$ResolvedTaskFile = Join-Path $RepoRoot $TaskFile
$LogFile = Join-Path $TasksDir "last_run.log"

# If an inline prompt was provided, write it to the task file
if ($Prompt -ne "") {
    Set-Content -Path $ResolvedTaskFile -Value $Prompt -Encoding UTF8
    Write-Host "[Dispatcher] Written prompt to $TaskFile" -ForegroundColor Cyan
}

if (-not (Test-Path $ResolvedTaskFile)) {
    Write-Error "Task file not found: $ResolvedTaskFile. Create this file or pass -Prompt."
    exit 1
}

$InstructionPrompt = "Follow the exact specifications and instructions in $TaskFile. Read relevant files, apply all necessary file edits cleanly, and adhere to project standards."

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [Dispatcher] Starting sequential worker execution" -ForegroundColor Cyan
Write-Host " Engine:    $Engine" -ForegroundColor Cyan
Write-Host " Task File: $TaskFile" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$StartTime = Get-Date

try {
    if ($Engine -eq "gemini") {
        if (-not (Get-Command agy -ErrorAction SilentlyContinue)) {
            Write-Error "Antigravity CLI ('agy') not found in PATH."
            exit 1
        }

        $ActualModel = if ($Model -ne "") { $Model } else { "gemini-3.8-flash-high" }
        Write-Host " [Dispatcher] Invoking agy with model: $ActualModel..." -ForegroundColor Yellow

        # Execute agy in non-interactive print mode with auto-permissions
        & agy -p $InstructionPrompt --dangerously-skip-permissions --model $ActualModel --mode accept-edits 2>&1 | Tee-Object -FilePath $LogFile
    }
    elseif ($Engine -eq "opencode") {
        if (-not (Get-Command opencode -ErrorAction SilentlyContinue)) {
            Write-Error "OpenCode CLI ('opencode') not found in PATH."
            exit 1
        }

        $OpencodeArgs = @("run", $InstructionPrompt, "--auto")
        if ($Model -ne "") {
            $OpencodeArgs += @("-m", $Model)
        }

        Write-Host " [Dispatcher] Invoking opencode..." -ForegroundColor Yellow
        & opencode @OpencodeArgs 2>&1 | Tee-Object -FilePath $LogFile
    }

    $ExitCode = $LASTEXITCODE
}
catch {
    Write-Error "Execution failed: $_"
    $ExitCode = 1
}

$Duration = (Get-Date) - $StartTime
Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " [Dispatcher] Worker execution completed in $($Duration.TotalSeconds.ToString('F1'))s with exit code: $ExitCode" -ForegroundColor Cyan
Write-Host " Log saved to: $LogFile" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Show git changes summary for the orchestrator
Write-Host "`n[Git Working Tree Status]:" -ForegroundColor Green
git status --short

exit $ExitCode
