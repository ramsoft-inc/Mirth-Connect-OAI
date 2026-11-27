<#
.SYNOPSIS
    Builds custom-lib/com.ramsoft.logging.jar (RamsoftAsyncHttpAppender) and runs its unit tests.

.DESCRIPTION
    This script is the only build of the jar; there is no CI build. It compiles against the same
    log4j and OkHttp jars Mirth 4.4.2 ships in server-lib, targets Java 8 so it loads on any JVM
    Mirth runs on, and contains only com/ramsoft/logging classes. If a compile or test step fails,
    no jar is written.

    Release: build from the merge commit without -SkipTests, then commit the jar to
      - ADO RapidResults/MirthOAI, branch main, /Win64/Mirth Connect/custom-lib/com.ramsoft.logging.jar
        (packaged by the Build-Mirth-HL7 installer pipeline, for new installs), and
      - an OmegaAI-Mono Mirth update package,
        Mirth-HL7/UpdatePackage/files/<version>/custom-lib/com.ramsoft.logging.jar
        (for existing instances; the package must restart the Mirth service for the jar to load).
    Each of those PRs states the source commit and the jar's SHA-256.

.PARAMETER SkipTests
    Build the jar without compiling/running the unit tests. Local experiments only; never for a
    jar that is released.

.EXAMPLE
    pwsh ./build-ramsoft-logging.ps1
    # -> build/ramsoft-logging/com.ramsoft.logging.jar
#>
param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$out = Join-Path $root 'build/ramsoft-logging'
$classes = Join-Path $out 'classes'
$testClasses = Join-Path $out 'test-classes'
$sep = [IO.Path]::PathSeparator

$compileCp = @(
    'lib/log4j/log4j-api-2.17.2.jar',
    'lib/log4j/log4j-core-2.17.2.jar',
    'lib/signalr/okhttp-3.11.0.jar',
    'lib/signalr/okio-1.14.0.jar'
) | ForEach-Object { Join-Path $root $_ }
$testCp = $compileCp + @(
    'testlib/junit-4.8.1.jar',
    'lib/signalr/gson-2.8.5.jar'
) | ForEach-Object { if ([IO.Path]::IsPathRooted($_)) { $_ } else { Join-Path $root $_ } }

$required = if ($SkipTests) { $compileCp } else { $testCp }
foreach ($jar in $required) { if (-not (Test-Path $jar)) { throw "Missing dependency: $jar" } }

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $classes, $testClasses | Out-Null

$sources = @(Get-ChildItem (Join-Path $root 'src/com/ramsoft/logging') -Filter *.java | ForEach-Object FullName)
& javac --release 8 -proc:none -encoding UTF-8 -d $classes -cp ($compileCp -join $sep) $sources
if ($LASTEXITCODE -ne 0) { throw 'javac (main) failed' }

if (-not $SkipTests) {
    $tests = @(Get-ChildItem (Join-Path $root 'test/com/ramsoft/logging') -Filter *Test.java | ForEach-Object FullName)
    & javac --release 8 -proc:none -encoding UTF-8 -d $testClasses -cp (($testCp + $classes) -join $sep) $tests
    if ($LASTEXITCODE -ne 0) { throw 'javac (test) failed' }
    # Every compiled *Test class runs, so a new test class cannot build and then silently never run.
    $testClassNames = @(Get-ChildItem (Join-Path $root 'test/com/ramsoft/logging') -Filter *Test.java |
        ForEach-Object { 'com.ramsoft.logging.' + $_.BaseName })
    & java -cp (($testCp + $classes + $testClasses) -join $sep) org.junit.runner.JUnitCore $testClassNames
    if ($LASTEXITCODE -ne 0) { throw 'unit tests failed' }
}

$jarPath = Join-Path $out 'com.ramsoft.logging.jar'
& jar --create --file $jarPath -C $classes com
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Write-Host "Built $jarPath"
& jar --list --file $jarPath
