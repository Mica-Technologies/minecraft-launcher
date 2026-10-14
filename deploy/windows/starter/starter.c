/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Native starter for the Windows launcher.
 *
 * Installed as "<name>.exe", the program every shortcut, pin, URL handler and relaunch runs. It
 * starts the jpackage launcher beside it ("<name> Core.exe") and makes sure a launch can never end
 * in silence. The JVM inside the jpackage launcher reports its own startup failures only on
 * stderr, which a GUI program never shows: a damaged install (2026-10-14: runtime\lib\modules
 * deleted by a queued reboot operation) looked like "absolutely nothing happens".
 *
 *   1. Before launching, it checks the files the JVM cannot start without. If any is missing it
 *      says which, and offers to repair the installation.
 *   2. It passes stdin and stdout straight through (the --mcp relay speaks JSON-RPC on them) and
 *      keeps the tail of stderr, which it also forwards. Started without a stdout (a shortcut),
 *      it keeps stdout's tail too: HotSpot reports its own startup failures there.
 *   3. It creates a state file and passes its path in MMCL_STARTER_STATE; the launcher writes how
 *      far it got (main, ready, handled - see StarterHandshake.java). After the launcher exits,
 *      a failure it could not report itself gets a dialog: never reached main (offers repair),
 *      failed before showing a window, or crashed (an NTSTATUS exit code).
 *
 * Every such outcome is also appended to ~/.MicaMinecraftLauncher[DEV]/logs/startup.log, the
 * launcher's pre-startup log. User-facing text comes from the launcher's resource bundles
 * (starter.* keys), generated into starter_strings.h by build-starter.ps1.
 */

#define WIN32_LEAN_AND_MEAN
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <knownfolders.h>
#include <msi.h>
#include <stdio.h>
#include <wchar.h>

#include "starter_build.h"
#include "starter_strings.h"

#pragma comment( lib, "comctl32.lib" )
#pragma comment( lib, "shell32.lib" )
#pragma comment( lib, "ole32.lib" )
#pragma comment( lib, "msi.lib" )
#pragma comment( lib, "user32.lib" )
#pragma comment( lib, "advapi32.lib" )

#define PATH_CHARS 32768
#define TEXT_CHARS 8192
#define ERR_TAIL_BYTES 8192

#define ID_REPAIR 1001
#define ID_LOGS 1002

enum Stage { STAGE_LAUNCHING, STAGE_MAIN, STAGE_READY, STAGE_HANDLED };

static wchar_t g_self[ PATH_CHARS ];
static wchar_t g_dir[ PATH_CHARS ];     /* install folder, with a trailing backslash */
static wchar_t g_core[ PATH_CHARS ];
static wchar_t g_cfg[ PATH_CHARS ];
static wchar_t g_dataDir[ PATH_CHARS ]; /* ~/.MicaMinecraftLauncher[DEV] */
static const StarterLocale *g_locale;

static CRITICAL_SECTION g_errLock;
static char g_errTail[ ERR_TAIL_BYTES ];
static size_t g_errLen;
static HANDLE g_realStderr;

/* ---------------------------------------------------------------------------------------- */
/*  Small string helpers                                                                     */
/* ---------------------------------------------------------------------------------------- */

static void append( wchar_t *dst, size_t cap, const wchar_t *src )
{
    wcsncat_s( dst, cap, src, _TRUNCATE );
}

/* The localized string, falling back to the English root bundle. */
static const wchar_t *S( int id )
{
    const wchar_t *s = g_locale != NULL ? g_locale->s[ id ] : NULL;
    return s != NULL ? s : STARTER_LOCALES[ 0 ].s[ id ];
}

/*
 * Fills a template's {0}. Bundle text uses plain apostrophes (LocalizationManager.format escapes
 * them for MessageFormat on the Java side), so nothing else needs unescaping.
 */
static void format1( wchar_t *out, size_t cap, const wchar_t *tmpl, const wchar_t *arg )
{
    size_t o = 0;
    for ( const wchar_t *p = tmpl; *p != 0 && o + 1 < cap; p++ ) {
        if ( p[ 0 ] == L'{' && p[ 1 ] == L'0' && p[ 2 ] == L'}' ) {
            for ( const wchar_t *a = arg; *a != 0 && o + 1 < cap; a++ ) {
                out[ o++ ] = *a;
            }
            p += 2;
        }
        else {
            out[ o++ ] = *p;
        }
    }
    out[ o ] = 0;
}

static void hexCode( wchar_t *out, size_t cap, DWORD code )
{
    swprintf_s( out, cap, L"0x%08lX (%lu)", code, code );
}

/* ---------------------------------------------------------------------------------------- */
/*  Paths, startup log, locale                                                               */
/* ---------------------------------------------------------------------------------------- */

static void resolvePaths( void )
{
    GetModuleFileNameW( NULL, g_self, PATH_CHARS );
    wcscpy_s( g_dir, PATH_CHARS, g_self );
    wchar_t *slash = wcsrchr( g_dir, L'\\' );
    wchar_t base[ MAX_PATH ] = L"";
    if ( slash != NULL ) {
        wcscpy_s( base, MAX_PATH, slash + 1 );
        slash[ 1 ] = 0;
    }
    wchar_t *dot = wcsrchr( base, L'.' );
    if ( dot != NULL ) {
        *dot = 0;
    }
    swprintf_s( g_core, PATH_CHARS, L"%s%s Core.exe", g_dir, base );
    swprintf_s( g_cfg, PATH_CHARS, L"%sapp\\%s Core.cfg", g_dir, base );

    PWSTR profile = NULL;
    if ( SUCCEEDED( SHGetKnownFolderPath( &FOLDERID_Profile, 0, NULL, &profile ) ) ) {
        swprintf_s( g_dataDir, PATH_CHARS, L"%s\\%s", profile, MMCL_DATA_FOLDER );
        CoTaskMemFree( profile );
    }
}

static void logsDir( wchar_t *out, size_t cap )
{
    swprintf_s( out, cap, L"%s\\logs", g_dataDir );
}

/* Appends a line to startup.log in the launcher's own format. Never fails loudly. */
static void startupLog( const wchar_t *message )
{
    if ( g_dataDir[ 0 ] == 0 ) {
        return;
    }
    wchar_t dir[ PATH_CHARS ];
    wchar_t file[ PATH_CHARS ];
    logsDir( dir, PATH_CHARS );
    SHCreateDirectoryExW( NULL, dir, NULL );
    swprintf_s( file, PATH_CHARS, L"%s\\startup.log", dir );

    SYSTEMTIME t;
    GetLocalTime( &t );
    wchar_t line[ TEXT_CHARS ];
    swprintf_s( line, TEXT_CHARS, L"%04u-%02u-%02u %02u:%02u:%02u.%03u [pid %lu] Starter: %s\r\n",
                t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond, t.wMilliseconds,
                GetCurrentProcessId(), message );
    char utf8[ TEXT_CHARS * 3 ];
    int n = WideCharToMultiByte( CP_UTF8, 0, line, -1, utf8, sizeof utf8, NULL, NULL );
    HANDLE h = CreateFileW( file, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                            OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL );
    if ( h != INVALID_HANDLE_VALUE ) {
        DWORD written;
        if ( n > 1 ) {
            WriteFile( h, utf8, ( DWORD ) ( n - 1 ), &written, NULL );
        }
        CloseHandle( h );
    }
}

static int sameLanguage( const wchar_t *a, const wchar_t *b )
{
    for ( ;; a++, b++ ) {
        wchar_t x = *a == L'_' ? L'-' : towlower( *a );
        wchar_t y = *b == L'_' ? L'-' : towlower( *b );
        int endA = x == 0 || x == L'-';
        int endB = y == 0 || y == L'-';
        if ( endA || endB ) {
            return endA && endB;
        }
        if ( x != y ) {
            return 0;
        }
    }
}

static const StarterLocale *findLocale( const wchar_t *tag )
{
    if ( tag == NULL || tag[ 0 ] == 0 ) {
        return NULL;
    }
    for ( int i = 0; i < STARTER_LOCALE_COUNT; i++ ) {
        if ( _wcsicmp( STARTER_LOCALES[ i ].tag, tag ) == 0 ) {
            return &STARTER_LOCALES[ i ];
        }
    }
    for ( int i = 0; i < STARTER_LOCALE_COUNT; i++ ) {
        if ( sameLanguage( STARTER_LOCALES[ i ].tag, tag ) ) {
            return &STARTER_LOCALES[ i ];
        }
    }
    return NULL;
}

/* The language override from the launcher's config, if the user picked one in Settings. */
static void readLocaleOverride( wchar_t *out, size_t cap )
{
    out[ 0 ] = 0;
    wchar_t file[ PATH_CHARS ];
    swprintf_s( file, PATH_CHARS, L"%s\\config\\configuration.json", g_dataDir );
    HANDLE h = CreateFileW( file, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL );
    if ( h == INVALID_HANDLE_VALUE ) {
        return;
    }
    static char json[ 1 << 20 ];
    DWORD read = 0;
    ReadFile( h, json, sizeof json - 1, &read, NULL );
    CloseHandle( h );
    json[ read ] = 0;
    const char *p = strstr( json, "\"localeOverride\"" );
    if ( p == NULL ) {
        return;
    }
    p += 16;
    while ( *p == ' ' || *p == ':' ) {
        p++;
    }
    if ( *p != '"' ) {
        return;
    }
    p++;
    size_t o = 0;
    while ( *p != 0 && *p != '"' && o + 1 < cap ) {
        out[ o++ ] = ( wchar_t ) ( unsigned char ) *p++;
    }
    out[ o ] = 0;
}

static void pickLocale( void )
{
    wchar_t chosen[ 64 ];
    readLocaleOverride( chosen, 64 );
    g_locale = findLocale( chosen );
    if ( g_locale != NULL ) {
        return;
    }
    ULONG count = 0;
    ULONG len = 0;
    if ( GetUserPreferredUILanguages( MUI_LANGUAGE_NAME, &count, NULL, &len ) && len > 0 && len < 4096 ) {
        wchar_t langs[ 4096 ];
        if ( GetUserPreferredUILanguages( MUI_LANGUAGE_NAME, &count, langs, &len ) ) {
            for ( const wchar_t *l = langs; *l != 0 && g_locale == NULL; l += wcslen( l ) + 1 ) {
                g_locale = findLocale( l );
            }
        }
    }
    if ( g_locale == NULL ) {
        g_locale = &STARTER_LOCALES[ 0 ];
    }
}

/* ---------------------------------------------------------------------------------------- */
/*  Self-check: the files the JVM cannot start without                                       */
/* ---------------------------------------------------------------------------------------- */

static int exists( const wchar_t *path )
{
    DWORD a = GetFileAttributesW( path );
    return a != INVALID_FILE_ATTRIBUTES && ( a & FILE_ATTRIBUTE_DIRECTORY ) == 0;
}

static void noteMissing( wchar_t *list, size_t cap, const wchar_t *path )
{
    if ( !exists( path ) ) {
        append( list, cap, L"\n" );
        append( list, cap, path );
    }
}

/* Adds each jar on the .cfg's app.classpath ($APPDIR = <install>\app) that is not there. */
static void checkClasspath( wchar_t *list, size_t cap )
{
    HANDLE h = CreateFileW( g_cfg, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL );
    if ( h == INVALID_HANDLE_VALUE ) {
        return;
    }
    static char text[ 65536 ];
    DWORD read = 0;
    ReadFile( h, text, sizeof text - 1, &read, NULL );
    CloseHandle( h );
    text[ read ] = 0;
    char *lines = NULL;
    for ( char *line = strtok_s( text, "\r\n", &lines ); line != NULL; line = strtok_s( NULL, "\r\n", &lines ) ) {
        if ( strncmp( line, "app.classpath=", 14 ) != 0 ) {
            continue;
        }
        wchar_t value[ PATH_CHARS ];
        MultiByteToWideChar( CP_UTF8, 0, line + 14, -1, value, PATH_CHARS );
        wchar_t *next = NULL;
        for ( wchar_t *entry = wcstok_s( value, L";", &next ); entry != NULL; entry = wcstok_s( NULL, L";", &next ) ) {
            wchar_t path[ PATH_CHARS ];
            if ( wcsncmp( entry, L"$APPDIR", 7 ) == 0 ) {
                swprintf_s( path, PATH_CHARS, L"%sapp%s", g_dir, entry + 7 );
            }
            else {
                wcscpy_s( path, PATH_CHARS, entry );
            }
            noteMissing( list, cap, path );
        }
    }
}

/* Fills list with one missing file per line (each line starting with \n); empty when intact. */
static void findMissingFiles( wchar_t *list, size_t cap )
{
    wchar_t path[ PATH_CHARS ];
    list[ 0 ] = 0;
    noteMissing( list, cap, g_core );
    noteMissing( list, cap, g_cfg );
    swprintf_s( path, PATH_CHARS, L"%sruntime\\bin\\server\\jvm.dll", g_dir );
    noteMissing( list, cap, path );
    swprintf_s( path, PATH_CHARS, L"%sruntime\\lib\\modules", g_dir );
    noteMissing( list, cap, path );
    checkClasspath( list, cap );
}

/* ---------------------------------------------------------------------------------------- */
/*  Launch                                                                                   */
/* ---------------------------------------------------------------------------------------- */

static DWORD WINAPI pumpStderr( LPVOID pipe )
{
    char buf[ 4096 ];
    DWORD n;
    while ( ReadFile( ( HANDLE ) pipe, buf, sizeof buf, &n, NULL ) && n > 0 ) {
        if ( g_realStderr != NULL ) {
            DWORD w;
            if ( !WriteFile( g_realStderr, buf, n, &w, NULL ) ) {
                g_realStderr = NULL;
            }
        }
        EnterCriticalSection( &g_errLock );
        if ( n >= ERR_TAIL_BYTES ) {
            memcpy( g_errTail, buf + n - ERR_TAIL_BYTES, ERR_TAIL_BYTES );
            g_errLen = ERR_TAIL_BYTES;
        }
        else {
            if ( g_errLen + n > ERR_TAIL_BYTES ) {
                size_t drop = g_errLen + n - ERR_TAIL_BYTES;
                memmove( g_errTail, g_errTail + drop, g_errLen - drop );
                g_errLen -= drop;
            }
            memcpy( g_errTail + g_errLen, buf, n );
            g_errLen += n;
        }
        LeaveCriticalSection( &g_errLock );
    }
    return 0;
}

/* Everything on our own command line after argv[0], passed on unchanged. */
static const wchar_t *argumentsTail( void )
{
    const wchar_t *p = GetCommandLineW();
    if ( *p == L'"' ) {
        p++;
        while ( *p != 0 && *p != L'"' ) {
            p++;
        }
        if ( *p == L'"' ) {
            p++;
        }
    }
    else {
        while ( *p != 0 && *p != L' ' && *p != L'\t' ) {
            p++;
        }
    }
    while ( *p == L' ' || *p == L'\t' ) {
        p++;
    }
    return p;
}

static HANDLE inheritable( HANDLE h )
{
    if ( h == NULL || h == INVALID_HANDLE_VALUE ) {
        return NULL;
    }
    return SetHandleInformation( h, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT ) ? h : NULL;
}

static enum Stage readStage( const wchar_t *stateFile )
{
    char word[ 16 ] = "";
    HANDLE h = CreateFileW( stateFile, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                            OPEN_EXISTING, 0, NULL );
    if ( h != INVALID_HANDLE_VALUE ) {
        DWORD n = 0;
        ReadFile( h, word, sizeof word - 1, &n, NULL );
        word[ n ] = 0;
        CloseHandle( h );
    }
    if ( strncmp( word, "handled", 7 ) == 0 ) {
        return STAGE_HANDLED;
    }
    if ( strncmp( word, "ready", 5 ) == 0 ) {
        return STAGE_READY;
    }
    if ( strncmp( word, "main", 4 ) == 0 ) {
        return STAGE_MAIN;
    }
    return STAGE_LAUNCHING;
}

/*
 * Runs the jpackage launcher and waits for it. Returns FALSE when it could not be started (the
 * Win32 error in *exitCode).
 */
static BOOL runCore( DWORD *exitCode, enum Stage *stage )
{
    wchar_t temp[ MAX_PATH ];
    wchar_t stateFile[ PATH_CHARS ];
    GetTempPathW( MAX_PATH, temp );
    swprintf_s( stateFile, PATH_CHARS, L"%smmcl-starter-%lu-%llu.state", temp, GetCurrentProcessId(),
                GetTickCount64() );
    HANDLE sf = CreateFileW( stateFile, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                             CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, NULL );
    if ( sf != INVALID_HANDLE_VALUE ) {
        DWORD w;
        WriteFile( sf, "launching", 9, &w, NULL );
        CloseHandle( sf );
        SetEnvironmentVariableW( L"MMCL_STARTER_STATE", stateFile );
    }
    SetEnvironmentVariableW( L"MMCL_STARTER_PATH", g_self );

    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    HANDLE errRead = NULL;
    HANDLE errWrite = NULL;
    if ( CreatePipe( &errRead, &errWrite, &sa, 0 ) ) {
        SetHandleInformation( errRead, HANDLE_FLAG_INHERIT, 0 );
    }
    HANDLE in = inheritable( GetStdHandle( STD_INPUT_HANDLE ) );
    HANDLE out = inheritable( GetStdHandle( STD_OUTPUT_HANDLE ) );
    if ( out == NULL ) {
        /* Started from a shortcut: nobody reads stdout. HotSpot prints its own startup failures
           there ("Error occurred during initialization of VM", fatal error banners), so keep it
           with stderr. A caller that has a stdout (the --mcp relay's client) gets it untouched. */
        out = errWrite;
    }
    g_realStderr = GetStdHandle( STD_ERROR_HANDLE );
    if ( g_realStderr == INVALID_HANDLE_VALUE ) {
        g_realStderr = NULL;
    }

    /* Hand down only these handles, not whatever else in this process is inheritable. */
    HANDLE list[ 3 ];
    int count = 0;
    HANDLE wanted[ 3 ] = { in, out, errWrite };
    for ( int i = 0; i < 3; i++ ) {
        int dup = wanted[ i ] == NULL;
        for ( int j = 0; j < count && !dup; j++ ) {
            dup = list[ j ] == wanted[ i ];
        }
        if ( !dup ) {
            list[ count++ ] = wanted[ i ];
        }
    }
    SIZE_T attrSize = 0;
    InitializeProcThreadAttributeList( NULL, 1, 0, &attrSize );
    LPPROC_THREAD_ATTRIBUTE_LIST attrs = HeapAlloc( GetProcessHeap(), 0, attrSize );
    BOOL haveAttrs = attrs != NULL && InitializeProcThreadAttributeList( attrs, 1, 0, &attrSize )
                     && ( count == 0 || UpdateProcThreadAttribute( attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                                                   list, count * sizeof( HANDLE ), NULL, NULL ) );

    STARTUPINFOW own;
    GetStartupInfoW( &own );
    STARTUPINFOEXW si;
    ZeroMemory( &si, sizeof si );
    si.StartupInfo.cb = sizeof si;
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES | ( own.dwFlags & STARTF_USESHOWWINDOW );
    si.StartupInfo.wShowWindow = own.wShowWindow;
    si.StartupInfo.hStdInput = in;
    si.StartupInfo.hStdOutput = out;
    si.StartupInfo.hStdError = errWrite;
    si.lpAttributeList = haveAttrs ? attrs : NULL;

    static wchar_t cmd[ PATH_CHARS ];
    swprintf_s( cmd, PATH_CHARS, L"\"%s\" %s", g_core, argumentsTail() );

    PROCESS_INFORMATION pi;
    AllowSetForegroundWindow( ASFW_ANY );
    BOOL started = CreateProcessW( g_core, cmd, NULL, NULL, count > 0,
                                   haveAttrs ? EXTENDED_STARTUPINFO_PRESENT : 0, NULL, NULL,
                                   &si.StartupInfo, &pi );
    DWORD startError = started ? 0 : GetLastError();
    if ( haveAttrs ) {
        DeleteProcThreadAttributeList( attrs );
    }
    if ( attrs != NULL ) {
        HeapFree( GetProcessHeap(), 0, attrs );
    }
    if ( errWrite != NULL ) {
        CloseHandle( errWrite );
    }

    if ( !started ) {
        if ( errRead != NULL ) {
            CloseHandle( errRead );
        }
        DeleteFileW( stateFile );
        *exitCode = startError;
        *stage = STAGE_LAUNCHING;
        return FALSE;
    }

    HANDLE pump = errRead != NULL ? CreateThread( NULL, 0, pumpStderr, errRead, 0, NULL ) : NULL;
    CloseHandle( pi.hThread );
    WaitForSingleObject( pi.hProcess, INFINITE );
    GetExitCodeProcess( pi.hProcess, exitCode );
    CloseHandle( pi.hProcess );
    /* Processes the launcher started (games) may still hold the pipe; don't wait for them. */
    if ( pump != NULL ) {
        WaitForSingleObject( pump, 750 );
    }
    *stage = readStage( stateFile );
    DeleteFileW( stateFile );
    return TRUE;
}

/* ---------------------------------------------------------------------------------------- */
/*  Repair                                                                                   */
/* ---------------------------------------------------------------------------------------- */

static int samePath( const wchar_t *a, const wchar_t *b )
{
    size_t la = wcslen( a );
    size_t lb = wcslen( b );
    while ( la > 0 && a[ la - 1 ] == L'\\' ) {
        la--;
    }
    while ( lb > 0 && b[ lb - 1 ] == L'\\' ) {
        lb--;
    }
    return la == lb && _wcsnicmp( a, b, la ) == 0;
}

/* The Windows Installer product installed in this folder, by its upgrade code. */
static BOOL findProduct( wchar_t code[ 39 ], BOOL *perMachine )
{
    wchar_t candidate[ 39 ];
    for ( DWORD i = 0; MsiEnumRelatedProductsW( MMCL_UPGRADE_CODE, 0, i, candidate ) == ERROR_SUCCESS; i++ ) {
        wchar_t location[ PATH_CHARS ];
        DWORD n = PATH_CHARS;
        if ( MsiGetProductInfoW( candidate, INSTALLPROPERTY_INSTALLLOCATION, location, &n ) == ERROR_SUCCESS
             && samePath( location, g_dir ) ) {
            wchar_t assignment[ 8 ];
            DWORD an = 8;
            *perMachine = MsiGetProductInfoW( candidate, INSTALLPROPERTY_ASSIGNMENTTYPE, assignment, &an ) == ERROR_SUCCESS
                          && assignment[ 0 ] == L'1';
            wcscpy_s( code, 39, candidate );
            return TRUE;
        }
    }
    return FALSE;
}

/*
 * Reinstalls missing files, registry entries and shortcuts (/fpums) from the installer Windows
 * cached. Files that are present, this starter included, are left alone, so it can run while
 * this program is open. Returns the msiexec exit code, or a Win32 error if it could not run.
 */
static DWORD repairInstall( BOOL *found )
{
    wchar_t code[ 39 ];
    BOOL perMachine = FALSE;
    *found = findProduct( code, &perMachine );
    if ( !*found ) {
        startupLog( L"Repair: no Windows Installer product is registered for this folder" );
        return ERROR_UNKNOWN_PRODUCT;
    }
    wchar_t params[ 128 ];
    swprintf_s( params, 128, L"/fpums %s /passive /norestart", code );
    wchar_t note[ 256 ];
    swprintf_s( note, 256, L"Repair: msiexec %s (%s)", params, perMachine ? L"per-machine" : L"per-user" );
    startupLog( note );

    SHELLEXECUTEINFOW sei;
    ZeroMemory( &sei, sizeof sei );
    sei.cbSize = sizeof sei;
    sei.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC;
    sei.lpVerb = perMachine ? L"runas" : L"open";
    sei.lpFile = L"msiexec.exe";
    sei.lpParameters = params;
    sei.nShow = SW_SHOWNORMAL;
    if ( !ShellExecuteExW( &sei ) || sei.hProcess == NULL ) {
        DWORD e = GetLastError();
        return e != 0 ? e : ERROR_GEN_FAILURE;
    }
    WaitForSingleObject( sei.hProcess, INFINITE );
    DWORD result = ERROR_GEN_FAILURE;
    GetExitCodeProcess( sei.hProcess, &result );
    CloseHandle( sei.hProcess );
    swprintf_s( note, 256, L"Repair finished with msiexec exit code %lu", result );
    startupLog( note );
    return result;
}

/* ---------------------------------------------------------------------------------------- */
/*  Dialogs                                                                                  */
/* ---------------------------------------------------------------------------------------- */

static void openLogFolder( void )
{
    wchar_t dir[ PATH_CHARS ];
    logsDir( dir, PATH_CHARS );
    SHCreateDirectoryExW( NULL, dir, NULL );
    ShellExecuteW( NULL, L"open", dir, NULL, NULL, SW_SHOWNORMAL );
}

static HRESULT CALLBACK dialogEvents( HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam, LONG_PTR ref )
{
    ( void ) lParam;
    ( void ) ref;
    if ( msg == TDN_CREATED ) {
        /* No owner window: without this it can open behind whatever the user is looking at. */
        SetWindowPos( hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE );
        SetForegroundWindow( hwnd );
    }
    else if ( msg == TDN_BUTTON_CLICKED && wParam == ID_LOGS ) {
        openLogFolder();
        return S_FALSE;
    }
    return S_OK;
}

/* Shows a failure; returns TRUE when the user chose Repair. */
static BOOL showFailure( const wchar_t *heading, const wchar_t *body, const wchar_t *details, BOOL offerRepair )
{
    wchar_t repair[ 1024 ];
    swprintf_s( repair, 1024, L"%s\n%s", S( S_BUTTON_REPAIR ), S( S_BUTTON_REPAIR_NOTE ) );
    TASKDIALOG_BUTTON buttons[ 2 ];
    UINT count = 0;
    if ( offerRepair ) {
        buttons[ count ].nButtonID = ID_REPAIR;
        buttons[ count++ ].pszButtonText = repair;
    }
    buttons[ count ].nButtonID = ID_LOGS;
    buttons[ count++ ].pszButtonText = S( S_BUTTON_LOGS );

    TASKDIALOGCONFIG c;
    ZeroMemory( &c, sizeof c );
    c.cbSize = sizeof c;
    c.dwFlags = TDF_USE_COMMAND_LINKS | TDF_ALLOW_DIALOG_CANCELLATION;
    c.dwCommonButtons = TDCBF_CLOSE_BUTTON;
    c.pszWindowTitle = MMCL_APP_NAME;
    c.pszMainIcon = TD_ERROR_ICON;
    c.pszMainInstruction = heading;
    c.pszContent = body;
    c.cButtons = count;
    c.pButtons = buttons;
    c.nDefaultButton = offerRepair ? ID_REPAIR : IDCLOSE;
    if ( details != NULL && details[ 0 ] != 0 ) {
        c.pszExpandedInformation = details;
        c.pszExpandedControlText = S( S_DETAILS_HIDE );
        c.pszCollapsedControlText = S( S_DETAILS_SHOW );
    }
    c.pfCallback = dialogEvents;
    int pressed = 0;
    if ( FAILED( TaskDialogIndirect( &c, &pressed, NULL, NULL ) ) ) {
        /* Common controls 6 missing (should not happen with our manifest): still say something. */
        wchar_t text[ TEXT_CHARS ];
        swprintf_s( text, TEXT_CHARS, L"%s\n\n%s", heading, body );
        MessageBoxW( NULL, text, MMCL_APP_NAME, MB_ICONERROR | MB_TOPMOST | MB_SETFOREGROUND );
        return FALSE;
    }
    return pressed == ID_REPAIR;
}

/* "Exit code: ..." plus the last part of what the launcher wrote to stderr. */
static void buildDetails( wchar_t *out, size_t cap, DWORD code )
{
    wchar_t hex[ 64 ];
    hexCode( hex, 64, code );
    format1( out, cap, S( S_DETAILS_EXIT_CODE ), hex );

    EnterCriticalSection( &g_errLock );
    size_t len = g_errLen;
    static char bytes[ ERR_TAIL_BYTES + 1 ];
    memcpy( bytes, g_errTail, len );
    LeaveCriticalSection( &g_errLock );
    if ( len == 0 ) {
        return;
    }
    bytes[ len ] = 0;
    static wchar_t text[ ERR_TAIL_BYTES + 1 ];
    if ( MultiByteToWideChar( CP_UTF8, MB_ERR_INVALID_CHARS, bytes, -1, text, ERR_TAIL_BYTES + 1 ) == 0 ) {
        MultiByteToWideChar( CP_ACP, 0, bytes, -1, text, ERR_TAIL_BYTES + 1 );
    }
    /* Keep the last ~2500 characters, starting on a line. */
    const wchar_t *tail = text;
    size_t tl = wcslen( text );
    if ( tl > 2500 ) {
        tail = text + tl - 2500;
        const wchar_t *nl = wcschr( tail, L'\n' );
        if ( nl != NULL ) {
            tail = nl + 1;
        }
    }
    append( out, cap, L"\n\n" );
    append( out, cap, S( S_DETAILS_OUTPUT ) );
    append( out, cap, L"\n" );
    append( out, cap, tail );
}

static void logOutcome( const wchar_t *what, DWORD code, enum Stage stage )
{
    static const wchar_t *const names[] = { L"launching", L"main", L"ready", L"handled" };
    wchar_t line[ TEXT_CHARS ];
    swprintf_s( line, TEXT_CHARS, L"%s (exit code 0x%08lX, stage %s)", what, code, names[ stage ] );
    EnterCriticalSection( &g_errLock );
    if ( g_errLen > 0 ) {
        /* The JVM's own error, e.g. "Error occurred during initialization of VM". */
        size_t start = g_errLen > 600 ? g_errLen - 600 : 0;
        wchar_t err[ 700 ];
        int n = MultiByteToWideChar( CP_UTF8, 0, g_errTail + start, ( int ) ( g_errLen - start ), err, 699 );
        err[ n > 0 ? n : 0 ] = 0;
        for ( wchar_t *p = err; *p != 0; p++ ) {
            if ( *p == L'\r' || *p == L'\n' ) {
                *p = L' ';
            }
        }
        append( line, TEXT_CHARS, L"; stderr: " );
        append( line, TEXT_CHARS, err );
    }
    LeaveCriticalSection( &g_errLock );
    startupLog( line );
}

/* Offers the repair result to the user; returns TRUE when the launcher should be started again. */
static BOOL repairAndReport( void )
{
    BOOL found = FALSE;
    DWORD result = repairInstall( &found );
    if ( result == ERROR_SUCCESS || result == ERROR_SUCCESS_REBOOT_REQUIRED || result == ERROR_SUCCESS_REBOOT_INITIATED ) {
        return TRUE;
    }
    wchar_t body[ TEXT_CHARS ];
    if ( !found ) {
        wcscpy_s( body, TEXT_CHARS, S( S_REPAIR_NOT_FOUND ) );
    }
    else {
        wchar_t num[ 32 ];
        swprintf_s( num, 32, L"%lu", result );
        format1( body, TEXT_CHARS, S( S_REPAIR_FAILED ), num );
    }
    showFailure( S( S_REPAIR_HEADING ), body, NULL, FALSE );
    return FALSE;
}

/* ---------------------------------------------------------------------------------------- */
/*  Entry point                                                                              */
/* ---------------------------------------------------------------------------------------- */

int WINAPI wWinMain( HINSTANCE instance, HINSTANCE previous, PWSTR cmdLine, int show )
{
    ( void ) instance;
    ( void ) previous;
    ( void ) cmdLine;
    ( void ) show;
    InitializeCriticalSection( &g_errLock );
    resolvePaths();
    pickLocale();

    /* One repair per run: if the launcher still fails afterwards, say so and stop. */
    BOOL repaired = FALSE;
    for ( ;; ) {
        wchar_t missing[ TEXT_CHARS ];
        findMissingFiles( missing, TEXT_CHARS );
        if ( missing[ 0 ] != 0 ) {
            wchar_t line[ TEXT_CHARS ];
            swprintf_s( line, TEXT_CHARS, L"Not starting, files are missing:%s", missing );
            for ( wchar_t *p = line; *p != 0; p++ ) {
                if ( *p == L'\n' ) {
                    *p = L' ';
                }
            }
            startupLog( line );
            wchar_t body[ TEXT_CHARS ];
            format1( body, TEXT_CHARS, S( S_MISSING_BODY ), missing + 1 );
            if ( !repaired && showFailure( S( S_FAILED_HEADING ), body, NULL, TRUE ) && repairAndReport() ) {
                repaired = TRUE;
                continue;
            }
            return 1;
        }

        DWORD code = 0;
        enum Stage stage = STAGE_LAUNCHING;
        wchar_t details[ TEXT_CHARS ];
        wchar_t hex[ 64 ];
        wchar_t body[ TEXT_CHARS ];
        if ( !runCore( &code, &stage ) ) {
            logOutcome( L"Could not start the launcher", code, stage );
            buildDetails( details, TEXT_CHARS, code );
            hexCode( hex, 64, code );
            format1( body, TEXT_CHARS, S( S_FAILED_BODY ), hex );
            if ( !repaired && showFailure( S( S_FAILED_HEADING ), body, details, TRUE ) && repairAndReport() ) {
                repaired = TRUE;
                continue;
            }
            return ( int ) code;
        }
        if ( code == 0 ) {
            return 0;
        }

        /* An NTSTATUS error code means the process crashed rather than exited. */
        BOOL crashed = ( code & 0xC0000000u ) == 0xC0000000u;
        if ( stage >= STAGE_READY && !crashed ) {
            /* The launcher already showed what went wrong, or this is how it is meant to end. */
            return ( int ) code;
        }
        buildDetails( details, TEXT_CHARS, code );
        hexCode( hex, 64, code );
        if ( stage == STAGE_LAUNCHING ) {
            /* The JVM never reached main(): almost always a damaged install. */
            logOutcome( L"The launcher exited before Java started", code, stage );
            format1( body, TEXT_CHARS, S( S_FAILED_BODY ), hex );
            if ( !repaired && showFailure( S( S_FAILED_HEADING ), body, details, TRUE ) && repairAndReport() ) {
                repaired = TRUE;
                continue;
            }
            return ( int ) code;
        }
        logOutcome( crashed ? L"The launcher crashed" : L"The launcher exited before showing a window", code, stage );
        format1( body, TEXT_CHARS, S( S_CRASHED_BODY ), hex );
        showFailure( S( S_CRASHED_HEADING ), body, details, FALSE );
        return ( int ) code;
    }
}
