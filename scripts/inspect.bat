@echo off
:: Nexus Flow quality-gate script (Windows CMD)
:: Usage: scripts\inspect.bat

setlocal enabledelayedexpansion

set "REPO_ROOT=%~dp0.."
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%REPO_ROOT%"

echo ========================================
echo  Nexus Flow -- quality gate
echo ========================================
echo JAVA_HOME : %JAVA_HOME%
java -version
echo.

:: Run full check (excluding SpotBugs)
call gradlew.bat :project:core:check ^
    -x spotbugsMain -x spotbugsTest ^
    --no-daemon ^
    --warning-mode summary

set BUILD_STATUS=%ERRORLEVEL%

echo.
echo ========================================
echo  Summary
echo ========================================

:: PMD violations
set PMD_XML=project\core\build\reports\pmd\main.xml
if exist "%PMD_XML%" (
    for /f %%C in ('findstr /c:"<violation" "%PMD_XML%" ^| find /c /v ""') do (
        echo PMD violations ^(main^):   %%C
    )
) else (
    echo PMD violations ^(main^):   report not found
)

set PMD_TEST_XML=project\core\build\reports\pmd\test.xml
if exist "%PMD_TEST_XML%" (
    for /f %%C in ('findstr /c:"<violation" "%PMD_TEST_XML%" ^| find /c /v ""') do (
        echo PMD violations ^(test^):   %%C
    )
)

:: Tests — count from XML results
set TEST_DIR=project\core\build\test-results\test
set TOTAL=0
set FAILED=0
if exist "%TEST_DIR%\*.xml" (
    for %%F in ("%TEST_DIR%\*.xml") do (
        for /f "tokens=2 delims==""" %%T in ('findstr /i "tests=" "%%F"') do (
            set /a TOTAL+=%%T
        )
        for /f "tokens=2 delims==""" %%F2 in ('findstr /i "failures=" "%%F"') do (
            set /a FAILED+=%%F2
        )
    )
    echo Tests:                   total=!TOTAL!  failed=!FAILED!
) else (
    echo Tests:                   no results found
)

echo ----------------------------------------
if %BUILD_STATUS% EQU 0 (
    echo BUILD: PASSED
) else (
    echo BUILD: FAILED ^(exit code %BUILD_STATUS%^)
)
echo ========================================

exit /b %BUILD_STATUS%
