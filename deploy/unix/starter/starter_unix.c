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
 * Native starter for the macOS and Linux launcher; the counterpart of
 * deploy/windows/starter/starter.c, with the same job: a launch must never end in silence.
 *
 * Installed as the launcher's executable ("<app>.app/Contents/MacOS/<name>", or
 * "/opt/<pkg>/bin/<name>"), it starts the jpackage launcher beside it ("<name> Core"):
 *
 *   1. It checks the files the JVM cannot start without; if any is missing it says which and
 *      offers to download the launcher again.
 *   2. It keeps the tail of stderr (and of stdout when it has none of its own), which the JVM
 *      uses to explain a startup failure, while passing everything through.
 *   3. It reads how far startup got from the state file named in MMCL_STARTER_STATE (written by
 *      StarterHandshake.java: main, ready, handled) and reports what the launcher could not.
 *
 * Linux: the starter stays the parent, so it gets the exit code or crash signal and forwards
 * termination signals. macOS: Launch Services delivers mmcl:// links and Dock activity to the
 * process it started, so the starter must BE the launcher: it forks a small watcher and then
 * execs the jpackage launcher in its own process. The watcher sees the exit through kqueue but
 * not its status, so it decides on the stage alone; macOS reports real crashes itself.
 *
 * Dialogs: CoreFoundation's CFUserNotification on macOS; GTK 3, loaded at run time (JavaFX needs
 * it on Linux anyway), with zenity as a fallback. Everything is also written to
 * ~/.MicaMinecraftLauncher[DEV]/logs/startup.log. Text comes from the starter.* bundle keys via
 * deploy/starter/GenerateStarterStrings.java.
 */

#define _GNU_SOURCE
#include <ctype.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifdef __APPLE__
#include <CoreFoundation/CoreFoundation.h>
#include <mach-o/dyld.h>
#include <sys/event.h>
#endif

#include "starter_build.h"
/* The generated strings are T("...") literals; here they are UTF-8. */
#define STARTER_CHAR char
#define T( s ) s
#include "starter_strings.h"

extern char **environ;

#define TAIL_BYTES 8192
#define TEXT 8192

enum Stage { STAGE_LAUNCHING, STAGE_MAIN, STAGE_READY, STAGE_HANDLED };

static char g_self[ PATH_MAX ];
static char g_core[ PATH_MAX ];
static char g_cfg[ PATH_MAX ];
static char g_appDir[ PATH_MAX ];   /* what the .cfg calls $APPDIR */
static char g_modules[ PATH_MAX ];
static char g_jvm[ PATH_MAX ];
static char g_dataDir[ PATH_MAX ];  /* ~/.MicaMinecraftLauncher[DEV] */
static char g_state[ PATH_MAX ];
static const StarterLocale *g_locale;

static pthread_mutex_t g_tailLock = PTHREAD_MUTEX_INITIALIZER;
static char g_tail[ TAIL_BYTES ];
static size_t g_tailLen;
static int g_pumpsRunning;   /* guarded by g_tailLock */

/* Internal argument that starts the macOS watcher (see runMac). */
#define WATCHER_ARG "--mmcl-starter-watcher"

static int usableOutput( int fd );

/* ---------------------------------------------------------------------------------------- */
/*  Strings, paths, startup log, locale                                                      */
/* ---------------------------------------------------------------------------------------- */

static const char *S( int id )
{
    const char *s = g_locale != NULL ? g_locale->s[ id ] : NULL;
    return s != NULL ? s : STARTER_LOCALES[ 0 ].s[ id ];
}

/* Fills a template's {0}. Bundle text uses plain apostrophes, so nothing else needs unescaping. */
static void format1( char *out, size_t cap, const char *tmpl, const char *arg )
{
    size_t o = 0;
    for ( const char *p = tmpl; *p != 0 && o + 1 < cap; p++ ) {
        if ( p[ 0 ] == '{' && p[ 1 ] == '0' && p[ 2 ] == '}' ) {
            for ( const char *a = arg; *a != 0 && o + 1 < cap; a++ ) {
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

static void parentDir( char *path )
{
    char *slash = strrchr( path, '/' );
    if ( slash != NULL ) {
        *slash = 0;
    }
}

static void resolvePaths( void )
{
    char raw[ PATH_MAX ] = "";
#ifdef __APPLE__
    uint32_t size = sizeof raw;
    _NSGetExecutablePath( raw, &size );
#else
    ssize_t n = readlink( "/proc/self/exe", raw, sizeof raw - 1 );
    raw[ n > 0 ? n : 0 ] = 0;
#endif
    if ( realpath( raw, g_self ) == NULL ) {
        snprintf( g_self, sizeof g_self, "%s", raw );
    }
    /* <dir>/<name>  ->  <dir>/<name> Core */
    snprintf( g_core, sizeof g_core, "%s Core", g_self );
    const char *base = strrchr( g_self, '/' );
    base = base != NULL ? base + 1 : g_self;

    char root[ PATH_MAX ];
    snprintf( root, sizeof root, "%s", g_self );
    parentDir( root );   /* Contents/MacOS  or  /opt/<pkg>/bin */
    parentDir( root );   /* Contents        or  /opt/<pkg>     */
#ifdef __APPLE__
    snprintf( g_appDir, sizeof g_appDir, "%s/app", root );
    snprintf( g_modules, sizeof g_modules, "%s/runtime/Contents/Home/lib/modules", root );
    snprintf( g_jvm, sizeof g_jvm, "%s/runtime/Contents/Home/lib/server/libjvm.dylib", root );
#else
    snprintf( g_appDir, sizeof g_appDir, "%s/lib/app", root );
    snprintf( g_modules, sizeof g_modules, "%s/lib/runtime/lib/modules", root );
    snprintf( g_jvm, sizeof g_jvm, "%s/lib/runtime/lib/server/libjvm.so", root );
#endif
    snprintf( g_cfg, sizeof g_cfg, "%s/%s Core.cfg", g_appDir, base );

    const char *home = getenv( "HOME" );
    snprintf( g_dataDir, sizeof g_dataDir, "%s/%s", home != NULL ? home : "", MMCL_DATA_FOLDER );
}

static void mkdirs( const char *path )
{
    char tmp[ PATH_MAX ];
    snprintf( tmp, sizeof tmp, "%s", path );
    for ( char *p = tmp + 1; *p != 0; p++ ) {
        if ( *p == '/' ) {
            *p = 0;
            mkdir( tmp, 0755 );
            *p = '/';
        }
    }
    mkdir( tmp, 0755 );
}

static void logsDir( char *out, size_t cap )
{
    snprintf( out, cap, "%s/logs", g_dataDir );
}

/* Appends a line to startup.log in the launcher's own format. Never fails loudly. */
static void startupLog( const char *fmt, ... )
{
    char dir[ PATH_MAX ];
    char file[ PATH_MAX + 16 ];
    logsDir( dir, sizeof dir );
    mkdirs( dir );
    snprintf( file, sizeof file, "%s/startup.log", dir );

    char message[ TEXT ];
    va_list args;
    va_start( args, fmt );
    vsnprintf( message, sizeof message, fmt, args );
    va_end( args );
    for ( char *p = message; *p != 0; p++ ) {
        if ( *p == '\n' || *p == '\r' ) {
            *p = ' ';
        }
    }
    struct timespec ts;
    clock_gettime( CLOCK_REALTIME, &ts );
    struct tm local;
    localtime_r( &ts.tv_sec, &local );
    FILE *f = fopen( file, "a" );
    if ( f != NULL ) {
        fprintf( f, "%04d-%02d-%02d %02d:%02d:%02d.%03ld [pid %ld] Starter: %s\n", local.tm_year + 1900,
                 local.tm_mon + 1, local.tm_mday, local.tm_hour, local.tm_min, local.tm_sec,
                 ts.tv_nsec / 1000000, ( long ) getpid(), message );
        fclose( f );
    }
}

static int sameLanguage( const char *a, const char *b )
{
    for ( ;; a++, b++ ) {
        char x = *a == '_' ? '-' : ( char ) tolower( ( unsigned char ) *a );
        char y = *b == '_' ? '-' : ( char ) tolower( ( unsigned char ) *b );
        int endA = x == 0 || x == '-';
        int endB = y == 0 || y == '-';
        if ( endA || endB ) {
            return endA && endB;
        }
        if ( x != y ) {
            return 0;
        }
    }
}

static const StarterLocale *findLocale( const char *raw )
{
    if ( raw == NULL || raw[ 0 ] == 0 || strcmp( raw, "C" ) == 0 || strcmp( raw, "POSIX" ) == 0 ) {
        return NULL;
    }
    /* "pt_BR.UTF-8@euro" -> "pt-BR" */
    char tag[ 64 ];
    size_t n = 0;
    for ( ; raw[ n ] != 0 && raw[ n ] != '.' && raw[ n ] != '@' && n + 1 < sizeof tag; n++ ) {
        tag[ n ] = raw[ n ] == '_' ? '-' : raw[ n ];
    }
    tag[ n ] = 0;
    for ( int i = 0; i < STARTER_LOCALE_COUNT; i++ ) {
        if ( strcasecmp( STARTER_LOCALES[ i ].tag, tag ) == 0 ) {
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
static void readLocaleOverride( char *out, size_t cap )
{
    out[ 0 ] = 0;
    char file[ PATH_MAX + 32 ];
    snprintf( file, sizeof file, "%s/config/configuration.json", g_dataDir );
    FILE *f = fopen( file, "r" );
    if ( f == NULL ) {
        return;
    }
    static char json[ 1 << 20 ];
    size_t read = fread( json, 1, sizeof json - 1, f );
    fclose( f );
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
        out[ o++ ] = *p++;
    }
    out[ o ] = 0;
}

static void pickLocale( void )
{
    char chosen[ 64 ];
    readLocaleOverride( chosen, sizeof chosen );
    g_locale = findLocale( chosen );
#ifdef __APPLE__
    CFArrayRef langs = CFLocaleCopyPreferredLanguages();
    for ( CFIndex i = 0; langs != NULL && g_locale == NULL && i < CFArrayGetCount( langs ); i++ ) {
        char tag[ 64 ];
        if ( CFStringGetCString( CFArrayGetValueAtIndex( langs, i ), tag, sizeof tag, kCFStringEncodingUTF8 ) ) {
            g_locale = findLocale( tag );
        }
    }
    if ( langs != NULL ) {
        CFRelease( langs );
    }
#else
    const char *language = getenv( "LANGUAGE" );   /* a colon-separated preference list */
    if ( g_locale == NULL && language != NULL ) {
        char list[ 256 ];
        snprintf( list, sizeof list, "%s", language );
        char *save = NULL;
        for ( char *l = strtok_r( list, ":", &save ); l != NULL && g_locale == NULL; l = strtok_r( NULL, ":", &save ) ) {
            g_locale = findLocale( l );
        }
    }
    const char *vars[] = { "LC_ALL", "LC_MESSAGES", "LANG" };
    for ( int i = 0; i < 3 && g_locale == NULL; i++ ) {
        g_locale = findLocale( getenv( vars[ i ] ) );
    }
#endif
    if ( g_locale == NULL ) {
        g_locale = &STARTER_LOCALES[ 0 ];
    }
}

/* ---------------------------------------------------------------------------------------- */
/*  Self-check: the files the JVM cannot start without                                       */
/* ---------------------------------------------------------------------------------------- */

static void noteMissing( char *list, size_t cap, const char *path )
{
    struct stat st;
    if ( stat( path, &st ) != 0 || !S_ISREG( st.st_mode ) ) {
        strncat( list, "\n", cap - strlen( list ) - 1 );
        strncat( list, path, cap - strlen( list ) - 1 );
    }
}

/* Adds each jar on the .cfg's app.classpath ($APPDIR = the app folder) that is not there. */
static void checkClasspath( char *list, size_t cap )
{
    FILE *f = fopen( g_cfg, "r" );
    if ( f == NULL ) {
        return;
    }
    char line[ 4096 ];
    while ( fgets( line, sizeof line, f ) != NULL ) {
        if ( strncmp( line, "app.classpath=", 14 ) != 0 ) {
            continue;
        }
        line[ strcspn( line, "\r\n" ) ] = 0;
        char *save = NULL;
        for ( char *entry = strtok_r( line + 14, ":", &save ); entry != NULL; entry = strtok_r( NULL, ":", &save ) ) {
            char path[ PATH_MAX ];
            if ( strncmp( entry, "$APPDIR", 7 ) == 0 ) {
                snprintf( path, sizeof path, "%s%s", g_appDir, entry + 7 );
            }
            else {
                snprintf( path, sizeof path, "%s", entry );
            }
            noteMissing( list, cap, path );
        }
    }
    fclose( f );
}

/* One missing file per line (each starting with \n); empty when the install is intact. */
static void findMissingFiles( char *list, size_t cap )
{
    list[ 0 ] = 0;
    noteMissing( list, cap, g_core );
    noteMissing( list, cap, g_cfg );
    noteMissing( list, cap, g_jvm );
    noteMissing( list, cap, g_modules );
    checkClasspath( list, cap );
}

/* ---------------------------------------------------------------------------------------- */
/*  Output capture and the state file                                                        */
/* ---------------------------------------------------------------------------------------- */

struct Pump { int from; int to; };

static void *pump( void *arg )
{
    struct Pump *p = arg;
    char buf[ 4096 ];
    ssize_t n;
    while ( ( n = read( p->from, buf, sizeof buf ) ) > 0 || ( n < 0 && errno == EINTR ) ) {
        if ( n <= 0 ) {
            continue;
        }
        if ( p->to >= 0 && write( p->to, buf, ( size_t ) n ) < 0 ) {
            p->to = -1;
        }
        /* One read is at most sizeof buf, less than the tail, so it always fits after a drop. */
        pthread_mutex_lock( &g_tailLock );
        size_t len = ( size_t ) n;
        if ( g_tailLen + len > TAIL_BYTES ) {
            size_t drop = g_tailLen + len - TAIL_BYTES;
            memmove( g_tail, g_tail + drop, g_tailLen - drop );
            g_tailLen -= drop;
        }
        memcpy( g_tail + g_tailLen, buf, len );
        g_tailLen += len;
        pthread_mutex_unlock( &g_tailLock );
    }
    pthread_mutex_lock( &g_tailLock );
    g_pumpsRunning--;
    pthread_mutex_unlock( &g_tailLock );
    return NULL;
}

/* Starts a detached pump copying from one fd to another (or nowhere) and into the tail. */
static void startPump( int from, int to )
{
    if ( from < 0 ) {
        return;
    }
    struct Pump *p = malloc( sizeof *p );
    p->from = from;
    p->to = to;
    pthread_t thread;
    pthread_mutex_lock( &g_tailLock );
    g_pumpsRunning++;
    pthread_mutex_unlock( &g_tailLock );
    if ( pthread_create( &thread, NULL, pump, p ) != 0 ) {
        pthread_mutex_lock( &g_tailLock );
        g_pumpsRunning--;
        pthread_mutex_unlock( &g_tailLock );
        return;
    }
    pthread_detach( thread );
}

/* Waits up to ~750 ms for the pumps to drain: processes the launcher started may keep a pipe. */
static void waitForPumps( void )
{
    for ( int i = 0; i < 75; i++ ) {
        pthread_mutex_lock( &g_tailLock );
        int running = g_pumpsRunning;
        pthread_mutex_unlock( &g_tailLock );
        if ( running == 0 ) {
            return;
        }
        usleep( 10000 );
    }
}

/*
 * Output capture for one run. stderr always goes through a pipe the starter reads. stdout does
 * too, except for the --mcp relay, whose stdout is a JSON-RPC channel that must stay untouched:
 * the JVM prints "Error occurred during initialization of VM" there. Each pipe is forwarded to
 * the starter's own stream when that is a real destination, and both feed the tail.
 */
struct Capture { int out[ 2 ]; int err[ 2 ]; int realOut; int realErr; };

static void openCapture( struct Capture *c, int captureStdout )
{
    c->out[ 0 ] = c->out[ 1 ] = c->err[ 0 ] = c->err[ 1 ] = -1;
    if ( pipe( c->err ) != 0 ) {
        c->err[ 0 ] = c->err[ 1 ] = -1;
    }
    if ( captureStdout && pipe( c->out ) != 0 ) {
        c->out[ 0 ] = c->out[ 1 ] = -1;
    }
    c->realOut = c->out[ 0 ] >= 0 && usableOutput( STDOUT_FILENO ) ? dup( STDOUT_FILENO ) : -1;
    c->realErr = usableOutput( STDERR_FILENO ) ? dup( STDERR_FILENO ) : -1;
}

/* In the process that becomes the launcher: point stdout/stderr at the pipes, keep nothing else. */
static void attachCapture( struct Capture *c )
{
    if ( c->err[ 1 ] >= 0 ) {
        dup2( c->err[ 1 ], STDERR_FILENO );
    }
    if ( c->out[ 1 ] >= 0 ) {
        dup2( c->out[ 1 ], STDOUT_FILENO );
    }
    int fds[] = { c->out[ 0 ], c->out[ 1 ], c->err[ 0 ], c->err[ 1 ], c->realOut, c->realErr };
    for ( int i = 0; i < 6; i++ ) {
        if ( fds[ i ] > STDERR_FILENO ) {
            close( fds[ i ] );
        }
    }
}

#ifndef __APPLE__
/* In the process that reads: drop the write ends, so the pipes close when the launcher exits.
   (The macOS watcher gets its ends through exec instead; see runWatcher.) */
static void readCapture( struct Capture *c )
{
    if ( c->out[ 1 ] >= 0 ) {
        close( c->out[ 1 ] );
    }
    if ( c->err[ 1 ] >= 0 ) {
        close( c->err[ 1 ] );
    }
    startPump( c->out[ 0 ], c->realOut );
    startPump( c->err[ 0 ], c->realErr );
}
#endif

/* Whether fd is a real destination (a terminal, file, pipe or socket), not closed or /dev/null. */
static int usableOutput( int fd )
{
    struct stat st;
    struct stat nul;
    if ( fstat( fd, &st ) != 0 ) {
        return 0;
    }
    if ( S_ISCHR( st.st_mode ) && stat( "/dev/null", &nul ) == 0 && st.st_rdev == nul.st_rdev ) {
        return 0;
    }
    return 1;
}

static void createStateFile( void )
{
    const char *tmp = getenv( "TMPDIR" );
    snprintf( g_state, sizeof g_state, "%s/mmcl-starter-%ld-XXXXXX", tmp != NULL && tmp[ 0 ] != 0 ? tmp : "/tmp",
              ( long ) getpid() );
    int fd = mkstemp( g_state );
    if ( fd < 0 ) {
        g_state[ 0 ] = 0;
        return;
    }
    if ( write( fd, "launching", 9 ) < 0 ) {
        /* Nothing to do: the stage then reads as launching anyway. */
    }
    close( fd );
    setenv( "MMCL_STARTER_STATE", g_state, 1 );
}

static enum Stage readStage( void )
{
    char word[ 16 ] = "";
    FILE *f = g_state[ 0 ] != 0 ? fopen( g_state, "r" ) : NULL;
    if ( f != NULL ) {
        size_t n = fread( word, 1, sizeof word - 1, f );
        word[ n ] = 0;
        fclose( f );
    }
    if ( g_state[ 0 ] != 0 ) {
        unlink( g_state );
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

/* The last ~2500 bytes of captured output, starting on a line. */
static void outputTail( char *out, size_t cap )
{
    pthread_mutex_lock( &g_tailLock );
    size_t start = g_tailLen > 2500 ? g_tailLen - 2500 : 0;
    while ( start > 0 && start < g_tailLen && g_tail[ start - 1 ] != '\n' ) {
        start++;
    }
    size_t len = g_tailLen - start;
    if ( len >= cap ) {
        len = cap - 1;
    }
    memcpy( out, g_tail + start, len );
    out[ len ] = 0;
    pthread_mutex_unlock( &g_tailLock );
}

/* ---------------------------------------------------------------------------------------- */
/*  Dialogs                                                                                  */
/* ---------------------------------------------------------------------------------------- */

static void spawnDetached( const char *program, const char *arg )
{
    char *argv[] = { ( char * ) program, ( char * ) arg, NULL };
    pid_t pid;
    if ( posix_spawnp( &pid, program, NULL, NULL, argv, environ ) == 0 ) {
        waitpid( pid, NULL, WNOHANG );
    }
}

static void openThing( const char *what )
{
#ifdef __APPLE__
    spawnDetached( "/usr/bin/open", what );
#else
    spawnDetached( "xdg-open", what );
#endif
}

static void openLogFolder( void )
{
    char dir[ PATH_MAX ];
    logsDir( dir, sizeof dir );
    mkdirs( dir );
    openThing( dir );
}

enum Choice { CHOICE_CLOSE, CHOICE_DOWNLOAD, CHOICE_LOGS };

#ifdef __APPLE__

static CFStringRef cf( const char *s )
{
    return CFStringCreateWithCString( NULL, s, kCFStringEncodingUTF8 );
}

static enum Choice ask( const char *heading, const char *body, const char *details, int offerDownload )
{
    char message[ TEXT * 2 ];
    snprintf( message, sizeof message, "%s%s%s", body, details[ 0 ] != 0 ? "\n\n" : "", details );
    CFStringRef h = cf( heading );
    CFStringRef m = cf( message );
    CFStringRef download = cf( S( S_BUTTON_DOWNLOAD ) );
    CFStringRef logs = cf( S( S_BUTTON_LOGS ) );
    CFStringRef close = cf( S( S_BUTTON_CLOSE ) );
    CFOptionFlags response = 0;
    /* Buttons: default, alternate, other. */
    CFUserNotificationDisplayAlert( 0, kCFUserNotificationStopAlertLevel, NULL, NULL, NULL, h, m,
                                    offerDownload ? download : close, logs, offerDownload ? close : NULL, &response );
    CFRelease( h );
    CFRelease( m );
    CFRelease( download );
    CFRelease( logs );
    CFRelease( close );
    switch ( response & 0x3 ) {
        case kCFUserNotificationDefaultResponse:
            return offerDownload ? CHOICE_DOWNLOAD : CHOICE_CLOSE;
        case kCFUserNotificationAlternateResponse:
            return CHOICE_LOGS;
        default:
            return CHOICE_CLOSE;
    }
}

#else

/* GTK 3, loaded at run time so the starter needs nothing JavaFX does not already need. */
typedef void *( *NewDialogFn )( void *, int, int, int, const char *, ... );
typedef void ( *SecondaryFn )( void *, const char *, ... );
typedef void *( *AddButtonFn )( void *, const char *, int );
typedef int ( *IntOfPtrFn )( void * );
typedef void ( *VoidOfPtrFn )( void * );
typedef void ( *VoidPtrStrFn )( void *, const char * );
typedef void ( *VoidPtrIntFn )( void *, int );
typedef void ( *VoidPtrPtrFn )( void *, void * );
typedef void *( *PtrOfStrFn )( const char * );
typedef void *( *PtrOfPtrPtrFn )( void *, void * );
typedef void *( *PtrOfPtrFn )( void * );
typedef int ( *InitCheckFn )( int *, char *** );
typedef int ( *VoidToIntFn )( void );
typedef void ( *BoxPackFn )( void *, void *, int, int, unsigned );

static int gtkAsk( const char *heading, const char *body, const char *details, int offerDownload, enum Choice *choice )
{
    static void *gtk;
    static int initialized;
    if ( gtk == NULL ) {
        gtk = dlopen( "libgtk-3.so.0", RTLD_NOW | RTLD_GLOBAL );
    }
    if ( gtk == NULL ) {
        return 0;
    }
#define SYM( type, name ) type name = ( type ) dlsym( gtk, #name ); if ( name == NULL ) return 0
    SYM( InitCheckFn, gtk_init_check );
    SYM( NewDialogFn, gtk_message_dialog_new );
    SYM( SecondaryFn, gtk_message_dialog_format_secondary_text );
    SYM( AddButtonFn, gtk_dialog_add_button );
    SYM( IntOfPtrFn, gtk_dialog_run );
    SYM( VoidOfPtrFn, gtk_widget_destroy );
    SYM( VoidPtrStrFn, gtk_window_set_title );
    SYM( VoidPtrIntFn, gtk_window_set_keep_above );
    SYM( PtrOfPtrFn, gtk_message_dialog_get_message_area );
    SYM( PtrOfStrFn, gtk_expander_new );
    SYM( PtrOfPtrPtrFn, gtk_scrolled_window_new );
    SYM( VoidPtrIntFn, gtk_scrolled_window_set_min_content_height );
    SYM( PtrOfStrFn, gtk_label_new );
    SYM( VoidPtrIntFn, gtk_label_set_selectable );
    SYM( VoidPtrIntFn, gtk_label_set_line_wrap );
    SYM( VoidPtrPtrFn, gtk_container_add );
    SYM( BoxPackFn, gtk_box_pack_start );
    SYM( VoidOfPtrFn, gtk_widget_show_all );
    SYM( VoidToIntFn, gtk_events_pending );
    SYM( VoidToIntFn, gtk_main_iteration );
#undef SYM
    if ( !initialized ) {
        if ( !gtk_init_check( NULL, NULL ) ) {
            return 0;   /* no display */
        }
        initialized = 1;
    }
    const int GTK_MESSAGE_ERROR = 3;
    const int GTK_BUTTONS_NONE = 0;
    void *dialog = gtk_message_dialog_new( NULL, 0, GTK_MESSAGE_ERROR, GTK_BUTTONS_NONE, "%s", heading );
    gtk_window_set_title( dialog, MMCL_APP_NAME );
    gtk_window_set_keep_above( dialog, 1 );
    gtk_message_dialog_format_secondary_text( dialog, "%s", body );
    if ( details[ 0 ] != 0 ) {
        void *expander = gtk_expander_new( S( S_DETAILS_SHOW ) );
        void *scroll = gtk_scrolled_window_new( NULL, NULL );
        gtk_scrolled_window_set_min_content_height( scroll, 220 );
        void *label = gtk_label_new( details );
        gtk_label_set_selectable( label, 1 );
        gtk_label_set_line_wrap( label, 1 );
        gtk_container_add( scroll, label );
        gtk_container_add( expander, scroll );
        gtk_box_pack_start( gtk_message_dialog_get_message_area( dialog ), expander, 1, 1, 0 );
    }
    if ( offerDownload ) {
        gtk_dialog_add_button( dialog, S( S_BUTTON_DOWNLOAD ), CHOICE_DOWNLOAD );
    }
    gtk_dialog_add_button( dialog, S( S_BUTTON_LOGS ), CHOICE_LOGS );
    gtk_dialog_add_button( dialog, S( S_BUTTON_CLOSE ), CHOICE_CLOSE );
    gtk_widget_show_all( dialog );
    int response = gtk_dialog_run( dialog );
    gtk_widget_destroy( dialog );
    while ( gtk_events_pending() ) {
        gtk_main_iteration();
    }
    *choice = response == CHOICE_DOWNLOAD ? CHOICE_DOWNLOAD : response == CHOICE_LOGS ? CHOICE_LOGS : CHOICE_CLOSE;
    return 1;
}

/* zenity, when GTK cannot be loaded here: a plain error box, no buttons beyond OK. */
static int zenityAsk( const char *heading, const char *body )
{
    char text[ TEXT * 2 ];
    snprintf( text, sizeof text, "%s\n\n%s", heading, body );
    char *argv[] = { "zenity", "--error", "--no-markup", "--title", MMCL_APP_NAME, "--text", text, NULL };
    pid_t pid;
    if ( posix_spawnp( &pid, "zenity", NULL, NULL, argv, environ ) != 0 ) {
        return 0;
    }
    int status = 0;
    waitpid( pid, &status, 0 );
    return WIFEXITED( status ) && WEXITSTATUS( status ) != 127;
}

static enum Choice ask( const char *heading, const char *body, const char *details, int offerDownload )
{
    enum Choice choice = CHOICE_CLOSE;
    if ( gtkAsk( heading, body, details, offerDownload, &choice ) ) {
        return choice;
    }
    if ( !zenityAsk( heading, body ) ) {
        startupLog( "No dialog could be shown (no display, GTK 3 or zenity)" );
    }
    return CHOICE_CLOSE;
}

#endif

/* Shows a failure until the user closes it; Download and the log folder keep it open. */
static void report( const char *heading, const char *body, const char *details, int offerDownload )
{
    for ( int shown = 0; shown < 10; shown++ ) {
        enum Choice choice = ask( heading, body, details, offerDownload );
        if ( choice == CHOICE_DOWNLOAD ) {
            openThing( MMCL_DOWNLOAD_URL );
            return;
        }
        if ( choice == CHOICE_LOGS ) {
            openLogFolder();
            continue;
        }
        return;
    }
}

static void buildDetails( char *out, size_t cap, const char *exitDescription )
{
    out[ 0 ] = 0;
    if ( exitDescription != NULL ) {
        format1( out, cap, S( S_DETAILS_EXIT_CODE ), exitDescription );
    }
    char tail[ 3000 ];
    outputTail( tail, sizeof tail );
    if ( tail[ 0 ] != 0 ) {
        snprintf( out + strlen( out ), cap - strlen( out ), "%s%s\n%s", out[ 0 ] != 0 ? "\n\n" : "",
                  S( S_DETAILS_OUTPUT ), tail );
    }
}

/* What a run ended with, decided as on Windows; exitDescription is NULL when unknown (macOS). */
static void reportOutcome( enum Stage stage, int crashed, const char *exitDescription )
{
    static const char *const names[] = { "launching", "main", "ready", "handled" };
    char tail[ 700 ];
    outputTail( tail, sizeof tail );
    if ( stage >= STAGE_READY && !crashed ) {
        return;
    }
    char details[ TEXT ];
    char body[ TEXT ];
    buildDetails( details, sizeof details, exitDescription );
    if ( stage == STAGE_LAUNCHING ) {
        startupLog( "The launcher exited before Java started (exit %s, stage %s); output: %s",
                    exitDescription != NULL ? exitDescription : "unknown", names[ stage ], tail );
        report( S( S_FAILED_HEADING ), S( S_REINSTALL_FAILED_BODY ), details, 1 );
        return;
    }
    startupLog( "The launcher %s (exit %s, stage %s); output: %s",
                crashed ? "crashed" : "exited before showing a window",
                exitDescription != NULL ? exitDescription : "unknown", names[ stage ], tail );
    if ( exitDescription != NULL ) {
        format1( body, sizeof body, S( S_CRASHED_BODY ), exitDescription );
    }
    else {
        snprintf( body, sizeof body, "%s", S( S_CRASHED_BODY_NO_CODE ) );
    }
    report( S( S_CRASHED_HEADING ), body, details, 0 );
}

/* ---------------------------------------------------------------------------------------- */
/*  Launch                                                                                   */
/* ---------------------------------------------------------------------------------------- */

/* argv for the jpackage launcher: its own path, then everything we were given. */
static char **coreArgv( int argc, char **argv )
{
    char **args = calloc( ( size_t ) argc + 1, sizeof *args );
    args[ 0 ] = g_core;
    for ( int i = 1; i < argc; i++ ) {
        args[ i ] = argv[ i ];
    }
    return args;
}

#ifndef __APPLE__

static volatile pid_t g_child;

static void forwardSignal( int sig )
{
    if ( g_child > 0 ) {
        kill( g_child, sig );
    }
}

static int runLinux( int argc, char **argv, int captureStdout )
{
    struct Capture capture;
    openCapture( &capture, captureStdout );

    pid_t child = fork();
    if ( child == 0 ) {
        attachCapture( &capture );
        execv( g_core, coreArgv( argc, argv ) );
        fprintf( stderr, "Could not start %s: %s\n", g_core, strerror( errno ) );
        _exit( 127 );
    }
    if ( child < 0 ) {
        startupLog( "fork failed: %s", strerror( errno ) );
        return 1;
    }
    g_child = child;
    struct sigaction sa;
    memset( &sa, 0, sizeof sa );
    sa.sa_handler = forwardSignal;
    sigaction( SIGTERM, &sa, NULL );
    sigaction( SIGINT, &sa, NULL );
    sigaction( SIGHUP, &sa, NULL );
    sigaction( SIGQUIT, &sa, NULL );

    readCapture( &capture );
    int status = 0;
    while ( waitpid( child, &status, 0 ) < 0 && errno == EINTR ) {
    }
    waitForPumps();
    enum Stage stage = readStage();

    char description[ 64 ];
    if ( WIFSIGNALED( status ) ) {
        int sig = WTERMSIG( status );
        /* Being told to stop (logout, kill, Ctrl+C) is not a crash. */
        int asked = sig == SIGTERM || sig == SIGINT || sig == SIGHUP || sig == SIGKILL || sig == SIGQUIT;
        snprintf( description, sizeof description, "signal %d (%s)", sig, strsignal( sig ) );
        if ( !asked || stage < STAGE_READY ) {
            reportOutcome( stage, !asked, description );
        }
        return 128 + sig;
    }
    int code = WIFEXITED( status ) ? WEXITSTATUS( status ) : 1;
    if ( code != 0 ) {
        snprintf( description, sizeof description, "%d", code );
        reportOutcome( stage, 0, description );
    }
    return code;
}

#else

/*
 * The watcher, started by runMac in a fresh process (exec'd, not just forked: CoreFoundation,
 * which the dialog needs, refuses to run in a forked child). Reads the launcher's output from
 * the inherited pipes, waits for the launcher to exit, and reports what it could not.
 */
static int runWatcher( void )
{
    setsid();   /* not part of the app's session: a Ctrl+C or logout there is the app's */
    signal( SIGHUP, SIG_IGN );
    signal( SIGINT, SIG_IGN );
    const char *state = getenv( "MMCL_STARTER_STATE" );
    snprintf( g_state, sizeof g_state, "%s", state != NULL ? state : "" );
    pid_t launcher = ( pid_t ) atol( getenv( "MMCL_STARTER_WATCH_PID" ) );
    const char *fds[] = { "MMCL_STARTER_WATCH_OUT", "MMCL_STARTER_WATCH_REAL_OUT",
                          "MMCL_STARTER_WATCH_ERR", "MMCL_STARTER_WATCH_REAL_ERR" };
    int fd[ 4 ];
    for ( int i = 0; i < 4; i++ ) {
        const char *v = getenv( fds[ i ] );
        fd[ i ] = v != NULL ? atoi( v ) : -1;
    }
    startPump( fd[ 0 ], fd[ 1 ] );
    startPump( fd[ 2 ], fd[ 3 ] );

    int kq = kqueue();
    struct kevent change;
    EV_SET( &change, launcher, EVFILT_PROC, EV_ADD | EV_ONESHOT, NOTE_EXIT, 0, NULL );
    struct kevent event;
    if ( kq >= 0 && kevent( kq, &change, 1, NULL, 0, NULL ) == 0 ) {
        while ( kevent( kq, NULL, 0, &event, 1, NULL ) < 0 && errno == EINTR ) {
        }
    }
    /* else: ESRCH, the launcher is already gone. */
    waitForPumps();
    reportOutcome( readStage(), 0, NULL );
    return 0;
}

/*
 * macOS: start the watcher, then become the launcher. Launch Services, the Dock and Apple events
 * (mmcl:// links, opened files) all track this process, so it must stay the launcher's.
 */
static int runMac( int argc, char **argv, int captureStdout )
{
    struct Capture capture;
    openCapture( &capture, captureStdout );
    pid_t launcher = getpid();

    pid_t watcher = fork();
    if ( watcher == 0 ) {
        if ( capture.out[ 1 ] >= 0 ) {
            close( capture.out[ 1 ] );
        }
        if ( capture.err[ 1 ] >= 0 ) {
            close( capture.err[ 1 ] );
        }
        char value[ 32 ];
        snprintf( value, sizeof value, "%ld", ( long ) launcher );
        setenv( "MMCL_STARTER_WATCH_PID", value, 1 );
        const char *names[] = { "MMCL_STARTER_WATCH_OUT", "MMCL_STARTER_WATCH_REAL_OUT",
                                "MMCL_STARTER_WATCH_ERR", "MMCL_STARTER_WATCH_REAL_ERR" };
        int fds[] = { capture.out[ 0 ], capture.realOut, capture.err[ 0 ], capture.realErr };
        for ( int i = 0; i < 4; i++ ) {
            snprintf( value, sizeof value, "%d", fds[ i ] );
            setenv( names[ i ], value, 1 );
        }
        execl( g_self, g_self, WATCHER_ARG, ( char * ) NULL );
        _exit( 127 );
    }
    if ( watcher > 0 ) {
        attachCapture( &capture );
    }
    else {
        /* No watcher, nobody to read the pipes: run uncaptured rather than block on them. */
        int fds[] = { capture.out[ 0 ], capture.out[ 1 ], capture.err[ 0 ], capture.err[ 1 ],
                      capture.realOut, capture.realErr };
        for ( int i = 0; i < 6; i++ ) {
            if ( fds[ i ] > STDERR_FILENO ) {
                close( fds[ i ] );
            }
        }
    }
    execv( g_core, coreArgv( argc, argv ) );
    fprintf( stderr, "Could not start %s: %s\n", g_core, strerror( errno ) );
    return 127;
}

#endif

int main( int argc, char **argv )
{
    resolvePaths();
    pickLocale();
#ifdef __APPLE__
    if ( argc == 2 && strcmp( argv[ 1 ], WATCHER_ARG ) == 0 && getenv( "MMCL_STARTER_WATCH_PID" ) != NULL ) {
        return runWatcher();
    }
#endif

    char missing[ TEXT ];
    findMissingFiles( missing, sizeof missing );
    if ( missing[ 0 ] != 0 ) {
        startupLog( "Not starting, files are missing:%s", missing );
        char body[ TEXT ];
        format1( body, sizeof body, S( S_REINSTALL_MISSING_BODY ), missing + 1 );
        report( S( S_FAILED_HEADING ), body, "", 1 );
        return 1;
    }

    createStateFile();
    setenv( "MMCL_STARTER_PATH", g_self, 1 );
    /* The --mcp relay's stdout is a JSON-RPC channel: never route it through a pipe of ours. */
    int captureStdout = !( argc > 1 && strcmp( argv[ 1 ], "--mcp" ) == 0 );
#ifdef __APPLE__
    return runMac( argc, argv, captureStdout );
#else
    return runLinux( argc, argv, captureStdout );
#endif
}
