param(
    [Parameter(Mandatory = $true)]
    [string]$RemoteUrl,

    [string]$Tag = "",

    [switch]$ForceRemote
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path ".git")) {
    Write-Error "Run this script from the AirChat repository root."
}

$dirty = git status --short
if ($dirty) {
    Write-Error "Working tree is not clean. Commit or stash changes before publishing."
}

$branch = git branch --show-current
if ($branch -ne "main") {
    Write-Error "Expected branch main, got $branch."
}

$existingRemote = git remote get-url origin 2>$null
if ($LASTEXITCODE -eq 0 -and $existingRemote) {
    if ($existingRemote -ne $RemoteUrl) {
        if (-not $ForceRemote) {
            Write-Error "origin already points to $existingRemote. Re-run with -ForceRemote to replace it."
        }
        git remote set-url origin $RemoteUrl
    }
} else {
    git remote add origin $RemoteUrl
}

git push -u origin main

if ($Tag) {
    $existingTag = git tag --list $Tag
    if (-not $existingTag) {
        git tag -a $Tag -m "AirChat $Tag"
    }
    git push origin $Tag
}

git remote -v
