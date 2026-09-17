param(
    [Parameter(Mandatory=$true)]
    [string]$Jdk,

    [string]$ServerJar
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if(-not $ServerJar){
    $ServerJar = Join-Path $PSScriptRoot "..\server-1.8\spigot-1.8.8.jar"
}

function Invoke-Checked([string]$Program, [string[]]$Arguments){
    & $Program @Arguments
    if($LASTEXITCODE -ne 0){ throw "$Program failed (exit $LASTEXITCODE)" }
}

foreach($tool in @("cmake", "ninja", "cl")){
    if(-not(Get-Command $tool -ErrorAction SilentlyContinue)){
        throw "Missing $tool. Open the x64 Native Tools Command Prompt for Visual Studio, then run this script. Install Desktop development with C++ and C++ CMake tools if needed."
    }
}
$Jdk = (Resolve-Path -LiteralPath $Jdk).Path
$ServerJar = (Resolve-Path -LiteralPath $ServerJar).Path
$javac = Join-Path $Jdk "bin\javac.exe"
$jar = Join-Path $Jdk "bin\jar.exe"
if(-not(Test-Path -LiteralPath $javac) -or -not(Test-Path -LiteralPath $jar)){
    throw "Jdk must point to a complete JDK. Use your JDK 21 for building, not the server's Java 8 runtime."
}
if($env:VSCMD_ARG_TGT_ARCH -and $env:VSCMD_ARG_TGT_ARCH -ne "x64"){
    throw "Open the x64 development shell; this initial JNI build targets x64."
}
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
Push-Location $PSScriptRoot
try{
    $env:JAVA_HOME = $Jdk
    $env:Path = "$Jdk\bin;$env:Path"
    Invoke-Checked "cmake" @("-S", ".", "-B", "build/native", "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DAC_BUILD_JNI=ON")
    Invoke-Checked "cmake" @("--build", "build/native", "--parallel")
    Invoke-Checked "ctest" @("--test-dir", "build/native", "--output-on-failure")

    $classes = Join-Path $PSScriptRoot "build\plugin-classes"
    $libs = Join-Path $PSScriptRoot "build\libs"
    if(Test-Path -LiteralPath $classes){ Remove-Item -LiteralPath $classes -Recurse -Force }
    New-Item -ItemType Directory -Path $classes, $libs -Force | Out-Null
    $argFile = Join-Path $PSScriptRoot "build\java-sources.txt"
    $sources = @(Get-ChildItem "plugin\src\main\java" -Filter "*.java" -Recurse |
        Sort-Object FullName | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    [System.IO.File]::WriteAllLines($argFile, [string[]]$sources, ([System.Text.UTF8Encoding]::new($false)))
    Invoke-Checked $javac @("--release", "8", "-encoding", "UTF-8", "-cp", $ServerJar,
        "-d", $classes, "@$argFile")
    $pluginJar = Join-Path $libs "anticheat.jar"
    Invoke-Checked $jar @("cf", $pluginJar, "-C", $classes, ".", "-C", "plugin/src/main/resources", ".")
    Copy-Item "build\native\anticheat_native.dll" (Join-Path $libs "anticheat_native.dll") -Force
    Write-Host ""
    Write-Host "Built build\libs\anticheat.jar and build\libs\anticheat_native.dll"
    Write-Host "Stop Spigot before replacing the JAR or DLL. See README.md for the two destinations."
}finally{
    Pop-Location
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
}
