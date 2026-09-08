function Get-LedgerBuildRoot {
    $actualRoot = Split-Path $PSScriptRoot -Parent
    if ($actualRoot -notmatch '[^\x00-\x7F]') { return $actualRoot }
    # Some Windows Java/emulator native components cannot read Unicode paths.
    # SUBST creates an alias only; no project files are moved or copied.
    if (-not ('LedgerPathNative' -as [type])) {
        Add-Type 'using System; using System.Text; using System.Runtime.InteropServices; public class LedgerPathNative { [DllImport("kernel32.dll", CharSet=CharSet.Unicode)] public static extern uint QueryDosDevice(string n, StringBuilder p, int size); }'
    }
    $pathBuffer = [System.Text.StringBuilder]::new(2048)
    $pathLength = [LedgerPathNative]::QueryDosDevice('R:', $pathBuffer, $pathBuffer.Capacity)
    if ($pathLength -gt 0) {
        if ($pathBuffer.ToString() -ne "\??\$actualRoot") { throw 'R: is already mapped to a different directory' }
    } else {
        & subst.exe R: $actualRoot
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create build path alias' }
    }
    return 'R:\'
}
