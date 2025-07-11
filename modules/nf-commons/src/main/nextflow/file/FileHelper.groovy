/*
 * Copyright 2013-2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.file

import java.lang.reflect.Field
import java.nio.file.CopyOption
import java.nio.file.FileSystem
import java.nio.file.FileSystemLoopException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern

import com.google.common.hash.HashCode
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.SysEnv
import nextflow.extension.Bolts
import nextflow.extension.FilesEx
import nextflow.plugin.Plugins
import nextflow.util.CacheHelper
import nextflow.util.Escape
import nextflow.util.StringUtils
/**
 * Provides some helper method handling files
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class FileHelper {

    static final public Pattern URL_PROTOCOL = ~/^([a-zA-Z][a-zA-Z0-9]*):\\/\\/.+/

    static final public Pattern INVALID_URL_PREFIX = ~/^(?!file)([a-zA-Z][a-zA-Z0-9]*):\\/[^\\/].+/

    static final private Pattern BASE_URL = ~/(?i)((?:[a-z][a-zA-Z0-9]*)?:\/\/[^:|\/]+(?::\d*)?)(?:$|\/.*)/

    static final private Path localTempBasePath

    static private Random rndGen = new Random()

    static final public char[] ALPHA = ('a'..'z') as char[]

    private static List<String> UNSUPPORTED_GLOB_WILDCARDS = ['http','https','ftp','ftps']

    private static LinkOption[] NO_FOLLOW_LINKS = [LinkOption.NOFOLLOW_LINKS] as LinkOption[]

    private static LinkOption[] FOLLOW_LINKS = [] as LinkOption[]

    static {
        def temp = System.getenv('NXF_TEMP')
        if( temp ) {
            localTempBasePath = Paths.get(temp)
            log.debug "Using NXF_TEMP=${localTempBasePath}"
        }
        else {
            temp = System.getProperty('java.io.tmpdir')
            if( !temp ) throw new IllegalStateException("Missing system temporary directory -- You can specify it using the NXF_TEMP environment variable")
            localTempBasePath = Paths.get(temp)
        }

        // make sure the path exists
        if( !Files.exists(localTempBasePath) )
            Files.createDirectories(localTempBasePath)
    }

    static String normalizePath( String path ) {

        if( path == '~' ) {
            path = System.properties['user.home']
        }
        else if ( path.startsWith('~/') ) {
            path = System.properties['user.home'].toString() + path[1..-1]
        }


        return path
    }


    /**
     * Creates a random string with the number of character specified
     * e.g. {@code s8hm2nxt3}
     *
     * @param len The len of the final random string
     * @param alphabet The set of characters allowed in the random string
     * @return The generated random string
     */
    static String randomString( int len, char[] alphabet ) {
        assert alphabet.size() > 0

        StringBuilder result = new StringBuilder()
        final max = alphabet.size()

        len.times {
            def index = rndGen.nextInt(max)
            result.append( alphabet[index] )
        }

        return result.toString()
    }

    static String randomString( int len ) {
        randomString(len, ALPHA)
    }

    static List nameParts( String name ) {
        assert name

        if( name.isLong() )  {
            return ['', name.toLong()]
        }

        def matcher = name =~ /^(\S*)?(\D)(\d+)$/
        if( matcher.matches() ) {
            def entries = (String[])matcher[0]
            return [ entries[1] + entries[2], entries[3].toString().toInteger() ]
        }
        else {
            return [name,0]
        }

    }

    /**
     * Check whenever a file or a directory is empty
     *
     * @param file The file path to
     */
    static boolean empty( File file ) {
        assert file
        empty(file.toPath())
    }

    static boolean empty( Path path ) {

        def attrs
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class)
        }
        catch (IOException e) {
            return true;
        }

        if ( attrs.isDirectory() ) {
            def stream = Files.newDirectoryStream(path)
            try {
                Iterator<Path> itr = stream.iterator()
                return !itr.hasNext()
            }
            finally {
                stream.close()
            }
        }
        else {
            return attrs.size() == 0
        }

    }

    /**
     * Defines a cacheable path for the specified {@code HashCode} instance.
     *
     * @param hash
     * @return
     */
    final static Path getWorkFolder(Path bashPath, HashCode hash) {
        assert bashPath
        assert hash

        def str = hash.toString()
        def bucket = str.substring(0,2)
        def folder = bashPath.resolve("${bucket}/${str.substring(2)}")

        return folder.toAbsolutePath()
    }

    final static Path createTempFolder(Path basePath) {
        assert basePath

        int count = 0
        while( true ) {
            def hash = CacheHelper.hasher(rndGen.nextLong()).hash().toString()
            def bucket = hash.substring(0,2)
            def result = basePath.resolve( "tmp/$bucket/${hash.substring(2)}" )

            if( FilesEx.exists(result) ) {
                if( ++count > 100 ) { throw new IOException("Unable to create a unique temporary path: $result") }
                continue
            }
            if( !FilesEx.mkdirs(result) ) {
                throw new IOException("Unable to create temporary directory: $result -- Make sure a file with the same name doesn't already exist and you have write permissions")
            }

            return result.toAbsolutePath()
        }
    }


    final static Path createLocalDir(String prefix = 'nxf-') {
        Files.createTempDirectory(localTempBasePath, prefix)
    }

    final static Path getLocalTempPath() {
        return localTempBasePath
    }

    static boolean isGlobAllowed( Path path ) {
        return !(path.getFileSystem().provider().scheme in UNSUPPORTED_GLOB_WILDCARDS)
    }

    static Path toPath(value) {
        if( value==null )
            return null

        Path result = null
        if( value instanceof String || value instanceof GString ) {
            result = asPath(value.toString())
        }
        else if( value instanceof Path ) {
            result = (Path)value
        }
        else {
            throw new IllegalArgumentException("Unexpected path value: '$value' [${value.getClass().getName()}]")
        }
        return result
    }

    static Path toCanonicalPath(value) {
        if( value==null )
            return null

        Path result = toPath(value)

        if( result.fileSystem != FileSystems.default ) {
            // remote file paths are expected to be absolute by definition
            return result
        }

        Path base
        if( !result.isAbsolute() && (base=fileRootDir()) ) {
            result = base.resolve(result.toString())
        }

        return result.toAbsolutePath().normalize()
    }

    /**
     * Remove consecutive slashes in a URI path. Ignore by design any slash in the hostname and after the `?`
     *
     * @param uri The input URI as string
     * @return The normalised URI string
     */
    protected static String normalisePathSlashes0(String uri) {
       if( !uri )
           return uri
        final SLASH = '/' as char
        final QMARK = '?' as char
        final scheme = getUrlProtocol(uri)
        if( scheme==null || scheme=='file') {
            // ignore for local files
            return uri
        }

        // find first non-slash
        final clean = scheme ? uri.substring(scheme.size()+1) : uri
        final start = clean.findIndexOf(it->it!='/')
        final result = new StringBuilder()
        if( scheme )
            result.append(scheme+':')
        if( start )
            result.append(clean.substring(0,start))
        for( int i=start; i<clean.size(); i++ ) {
            final ch = clean.charAt(i)
            if( (ch!=SLASH) || i+1==clean.size() || (clean.charAt(i+1)!=SLASH)) {
                if( ch==QMARK ) {
                    result.append(clean.substring(i))
                    break
                }
                else
                    result.append(clean.charAt(i))
            }
        }
        return result.toString()
    }

    /**
     * Given an hierarchical file URI path returns a {@link Path} object
     * eventually creating the associated file system if required.
     * <p>
     * An hierarchical URI is either an absolute URI whose scheme-specific part begins with a slash character (e.g. {@code file:/some/file.txt}),
     * or a relative URI, that is, a URI that does not specify a scheme (e.g. {@code some/file.txt}).
     *
     * See http://docs.oracle.com/javase/7/docs/api/java/net/URI.html
     *
     * @param str A path string eventually qualified with the scheme prefix
     * @return A {@link Path} object
     */
    static Path asPath( String str ) {
        if( !str )
            throw new IllegalArgumentException("Path string cannot be empty")
        if( str != Bolts.leftTrim(str) )
            throw new IllegalArgumentException("Path string cannot start with a blank or special characters -- Offending path: '${Escape.blanks(str)}'")
        if( str != Bolts.rightTrim(str) )
            throw new IllegalArgumentException("Path string cannot ends with a blank or special characters -- Offending path: '${Escape.blanks(str)}'")

        if( !str.contains(':/') ) {
            return Paths.get(str)
        }

        // check for valid the url scheme
        if( INVALID_URL_PREFIX.matcher(str).matches() )
            throw new IllegalArgumentException("File path is prefixed with an invalid URL scheme - Offending path: '${Escape.blanks(str)}'")

        return asPath0(str)
    }

    static private Path asPath0(String str) {
        def result = FileSystemPathFactory.parse(str)
        if( result )
            return result
        // note: enclose the plugin start in a sync section to
        // prevent race conditions with the plugin status that can
        // arise with parallel tasks requesting to access the same  file system
        synchronized (FileHelper.class) {
            autoStartMissingPlugin(str)
            result = FileSystemPathFactory.parse(str)
            if( result )
                return result
        }

        return asPath(toPathURI(str))
    }

    static final private Map<String,String> PLUGINS_MAP = [s3:'nf-amazon', gs:'nf-google', az:'nf-azure']

    static final private Map<String,Boolean> SCHEME_CHECKED = new HashMap<>()

    static protected boolean autoStartMissingPlugin(String str) {
        final scheme = getUrlProtocol(str)
        if( SCHEME_CHECKED[scheme] )
            return false
        // find out the default plugin for the given scheme and try to load it
        final pluginId = PLUGINS_MAP.get(scheme)
        if( pluginId ) try {
            if( Plugins.startIfMissing(pluginId) ) {
                log.debug "Started plugin '$pluginId' required to handle file: $str"
                // return true to signal a new plugin was loaded
                return true
            }
        }
        catch (Exception e) {
            log.warn ("Unable to start plugin '$pluginId' required by $str", e)
        }
        finally {
            SCHEME_CHECKED[scheme] = true
        }
        // no change in the plugin loaded, therefore return false
        return false
    }

    /**
     * Given a {@link URI} return a {@link Path} object
     * eventually creating the associated file system if required
     *
     * @param uri A URI for identifying the requested path
     * @return A {@link Path} object
     */
    static Path asPath( URI uri ) {
        if( !uri.scheme || uri.scheme == 'file' ) {
            checkFileURI(uri)
            return FileSystems.getDefault().getPath(uri.path)
        }
        else if( uri.scheme == 'http' || uri.scheme == 'https' || uri.scheme == 'ftp' ) {
            Paths.get(uri)
        }
        else if( uri.scheme in PLUGINS_MAP.keySet() ) {
            throw new IllegalStateException("Missing plugin '${PLUGINS_MAP[uri.scheme]}' required to read file: $uri")
        }
        else {
            getOrCreateFileSystemFor(uri).provider().getPath(uri)
        }
    }


    private static checkFileURI(URI uri) {
        if( uri.scheme ) {
            if( uri.authority )
                throw new IllegalArgumentException("Malformed file URI: $uri -- It must start either with a `file:/` or `file:///` prefix")

            if( !uri.path )
                throw new IllegalArgumentException("Malformed file URI: $uri -- Make sure it starts with an absolute path prefix i.e. `file:/`")
        }
        else if( !uri.path ) {
            throw new IllegalArgumentException("URI path cannot be empty")
        }
    }

    /**
     * Helper method that converts a file path to an hierarchical URI {@link URI} object.
     * <p>
     * An hierarchical URI is either an absolute URI whose scheme-specific part begins with a slash character (e.g. {@code file:/some/file.txt}),
     * or a relative URI, that is, a URI that does not specify a scheme (e.g. {@code some/file.txt}).
     * <p>
     * The main difference between this method and Java API {@link URI#create(java.lang.String)} is that the former
     * handle the query and fragments special characters (respectively `?` and `#`) as valid path chars. This because
     * question mark is required to be used as glob wildcard in path handling.
     * <p>
     * As side effect this method always returns a null {@link URI#query} and {@link URI#fragment} components.
     *
     * @param str A string representing a URI file path
     * @return A {@link URI} for the given string
     */

    @PackageScope
    static URI toPathURI( String str ) {

        // normalise 'igfs' path
        if( str.startsWith('igfs://') && str[7]!='/' ) {
            str = "igfs:///${str.substring(7)}"
        }

        // note: this URI constructor parse the path parameter and extract the `scheme` and `authority` components
        new URI(null,null,str,null,null)
    }

    /**
     * NOTE: this cannot be accessed by a remote system
     *
     * @return The file system defined by the {@code Session#workDir} attribute
     */
    @Memoized
    @Deprecated
    static FileSystem getWorkDirFileSystem() {
        def result = Global.session?.workDir?.getFileSystem()
        if( !result ) {
            log.warn "Session working directory file system not defined -- fallback on JVM default file system"
            result = FileSystems.getDefault()
        }
        result
    }

    /**
     * Check if the specified path is a NFS mount
     *
     * @param path The path to verify
     * @return The {@code true} when the path is a NFS mount {@code false} otherwise
     */
    @Memoized
    static boolean isPathSharedFS(Path path) {
        assert path
        if( path.getFileSystem() != FileSystems.getDefault() )
            return false

        final type = getPathFsType(path)
        final result = type == 'nfs' || type == 'lustre'
        log.debug "FS path type ($result): $path"
        return result
    }

    @Memoized
    static String getPathFsType(Path path)  {
        final os = System.getProperty('os.name')
        if( os != 'Linux' )
            return os

        final process = Runtime.runtime.exec("stat -f -c %T ${path}")
        final status = process.waitFor()
        final text = process.text?.trim()
        process.destroy()

        if( status ) {
            log.debug "Unable to determine FS type ($status): ${FilesEx.toUriString(path)}\n${Bolts.indent(text,'  ')}"
            return null
        }

        return text
    }

    /**
     * @return
     *      {@code true} when the current session working directory is a NFS mounted path
     *      {@code false otherwise}
     */
    static boolean getWorkDirIsSharedFS() {
        isPathSharedFS(Global.session.workDir)
    }

    /**
     * @return
     *      {@code true} when the current session working directory is a symlink path
     *      {@code false otherwise}
     */
    static boolean getWorkDirIsSymlink() {
        isPathSymlink(Global.session.workDir)
    }

    @Memoized
    static boolean isPathSymlink(Path path) {
        if( path.fileSystem!=FileSystems.default )
            return false
        try {
            return path != path.toRealPath()
        }
        catch (NoSuchFileException e) {
            return false
        }
        catch (IOException e) {
            log.debug "Unable to determine symlink status for path: $path - cause: ${e.message}"
            return false
        }
    }

    /**
     * Experimental. Check if a file exists making a second try on NFS mount
     * to avoid false negative.
     *
     * @param self
     * @param timeout
     * @return
     */
    @PackageScope
    static boolean safeExists(Path self, long timeout = 120_000) {

        if( Files.exists(self) )
            return true

        if( !workDirIsSharedFS )
            return false


        /*
         * When the file in a NFS folder in order to avoid false negative
         * list the content of the parent path to force refresh of NFS metadata
         * http://stackoverflow.com/questions/3833127/alternative-to-file-exists-in-java
         * http://superuser.com/questions/422061/how-to-determine-whether-a-directory-is-on-an-nfs-mounted-drive
         */

        final begin = System.currentTimeMillis()
        final path = (self.parent ?: '/').toString()
        while( true ) {
            final process = Runtime.runtime.exec("ls -la ${path}")
            final status = process.waitFor()
            log.trace "Safe exists listing: ${status} -- path: ${path}\n${Bolts.indent(process.text,'  ')}"

            if( status == 0 )
                break
            def delta = System.currentTimeMillis() -begin
            if( delta > timeout )
                break
            sleep 2_000
        }


        try {
            Files.readAttributes(self,BasicFileAttributes)
            return true
        }
        catch( IOException e ) {
            log.trace "Can't read file attributes: $self -- Cause: [${e.class.simpleName}] ${e.message}"
            return false
        }

    }

    /**
     * Lazy create and memoize this object since it will never change
     * @return A map holding the current session
     */
    @Memoized
    @Deprecated
    static protected Map envFor(String scheme) {
        envFor0(scheme, System.getenv())
    }

    @PackageScope
    @Deprecated
    static Map envFor0(String scheme, Map env) {
        def result = new LinkedHashMap(10)
        if( scheme?.toLowerCase() == 's3' ) {
            throw new UnsupportedOperationException()
        }
        else {
            if( !Global.session )
                log.debug "Session is not available while creating environment for '$scheme' file system provider"
            result.session = Global.session
        }
        return result
    }

    /**
     * Caches File system providers
     */
    @PackageScope
    static final Map<String, FileSystemProvider> providersMap = [:]

    /**
     * Returns a {@link FileSystemProvider} for a file scheme
     * @param scheme A file system scheme e.g. {@code file}, {@code s3}, {@code dxfs}, etc.
     * @return A {@link FileSystemProvider} instance for the specified scheme
     */
    static FileSystemProvider getProviderFor( String scheme ) {

        if( providersMap.containsKey(scheme) )
            return providersMap[scheme]

        synchronized (providersMap) {
            if( providersMap.containsKey(scheme) )
                return providersMap[scheme]

            for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
                if ( scheme == provider.getScheme() ) {
                    return providersMap[scheme] = provider;
                }
            }

            return null;
        }

    }

    /**
     * Get the instance of the specified {@code FileSystemProvider} class. If the provider is not
     * in the list of installed provided, it creates a new instance and add it to the list
     * <p>
     * This method has been deprecated use {@link #getOrCreateFileSystemFor(java.lang.String)} instead
     *
     * @see {@code FileSystemProvider#installedProviders}
     *
     * @param clazz A class extending {@code FileSystemProvider}
     * @return An instance of the specified class
     */
    synchronized static <T extends FileSystemProvider> T getOrInstallProvider( Class<T> clazz ) {

        FileSystemProvider result = FileSystemProvider.installedProviders().find { it.class == clazz }
        if( result )
            return (T)result

        result = (T)clazz.newInstance()

        // add it manually
        Field field = FileSystemProvider.class.getDeclaredField('installedProviders')
        field.setAccessible(true)
        List installedProviders = new ArrayList((List)field.get(null))
        installedProviders.add( result )
        field.set(this, Collections.unmodifiableList(installedProviders))
        log.debug "> Added '${clazz.simpleName}' to list of installed providers [${result.scheme}]"
        return (T)result
    }

    private static Lock _fs_lock = new ReentrantLock()

    /**
     * Acquire or create the file system for the given {@link URI}
     *
     * @param uri
     *      A {@link URI} locating a file into a file system
     * @param config
     *      A {@link Map} object representing the file system configuration. The structure of this object is entirely
     *      delegate to the file system implementation
     * @return
     *      The corresponding {@link FileSystem} object
     * @throws
     *      IllegalArgumentException if does not exist a valid provider for the given URI scheme
     */
    static FileSystem getOrCreateFileSystemFor( URI uri, Map config = null ) {
        assert uri

        /*
         * get the provider for the specified URI
         */
        def provider = getProviderFor(uri.scheme)
        if( !provider )
            throw new IllegalArgumentException("Cannot a find a file system provider for scheme: ${uri.scheme}")

        /*
         * check if already exists a file system for it
         */
        try {
            final fs = provider.getFileSystem(uri)
            if( fs )
                return fs
        }
        catch( FileSystemNotFoundException e ) {
            // fallback in following synchronised block
        }

        /*
         * since the file system does not exist, create it a protected block
         */
        return Bolts.withLock(_fs_lock) {
            FileSystem fs
            try { fs = provider.getFileSystem(uri) }
            catch( FileSystemNotFoundException e ) { fs=null }
            if( !fs ) {
                log.debug "Creating a file system instance for provider: ${provider.class.simpleName}; config=${StringUtils.stripSecrets(config)}"
                fs = provider.newFileSystem(uri, config!=null ? config : envFor(uri.scheme))
            }
            return fs
        }
    }

    static FileSystem getOrCreateFileSystemFor( String scheme, Map env = null ) {
        getOrCreateFileSystemFor(URI.create("$scheme:///"), env)
    }

    /**
     * Given a path look for an existing file with that name in the {@code cacheDir} folder.
     * If exists will return the path to it, otherwise make a copy of it in the cache folder
     * and returns to the path to it.
     *
     * @param sourcePath A {@link Path} object to an existing file or directory
     * @param cacheDir A {@link Path} to the folder containing the cached objects
     * @param sessionId An option {@link UUID} used to create the object invalidation key
     * @return A {@link Path} to the cached version of the specified object
     */
    static Path getLocalCachePath( Path sourcePath, Path cacheDir, UUID sessionId = null ) {
        assert sourcePath
        assert cacheDir

        final key = sessionId ? [sessionId, sourcePath] : sourcePath
        final hash = CacheHelper.hasher(key).hash()

        final cached = getWorkFolder(cacheDir, hash).resolve(sourcePath.getFileName().toString())
        if( Files.exists(cached) )
            return cached

        synchronized (cacheDir)  {
            if( Files.exists(cached) )
                return cached

            return FilesEx.copyTo(sourcePath, cached)
        }
    }


    /**
     * Returns a {@code PathMatcher} that performs match operations on the
     * {@code String} representation of {@link Path} objects by interpreting a
     * given pattern.
     *
     * @see FileSystem#getPathMatcher(java.lang.String)
     *
     * @param syntaxAndInput
     * @return
     */
    static PathMatcher getDefaultPathMatcher(String syntaxAndInput) {

        int pos = syntaxAndInput.indexOf(':');
        if (pos <= 0 || pos == syntaxAndInput.length())
            throw new IllegalArgumentException();

        String syntax = syntaxAndInput.substring(0, pos);
        String input = syntaxAndInput.substring(pos+1);

        String expr;
        if (syntax == 'glob') {
            expr = Globs.toUnixRegexPattern(input);
        }
        else if (syntax == 'regex' ) {
            expr = input;
        }
        else {
            throw new UnsupportedOperationException("Syntax '$syntax' not recognized");
        }

        // return matcher
        final Pattern pattern = Pattern.compile(expr);
        return new PathMatcher() {
            @Override
            public boolean matches(Path path) {
                return pattern.matcher(path.toString()).matches();
            }
        };
    }

    /**
     * Get a path matcher for the specified file system and file pattern.
     *
     * It tries to get the matcher returned by {@link FileSystem#getPathMatcher} and
     * falling back to {@link #getDefaultPathMatcher(java.lang.String)} when it return a null or an {@link UnsupportedOperationException}
     *
     * @param fileSystem
     * @param syntaxAndPattern
     * @return A {@link PathMatcher} instance for the specified file pattern
     */
    static PathMatcher getPathMatcherFor( String syntaxAndPattern, FileSystem fileSystem ) {
        assert fileSystem
        assert syntaxAndPattern

        PathMatcher matcher
        try {
            matcher = fileSystem.getPathMatcher(syntaxAndPattern)
        }
        catch( UnsupportedOperationException e ) {
            matcher = null
        }

        if( !matcher ) {
            log.trace("Path matcher not defined by '${fileSystem.class.simpleName}' file system -- using default default strategy")
            matcher = getDefaultPathMatcher(syntaxAndPattern)
        }

        return matcher
    }


    /**
     * Applies the specified action on one or more files and directories matching the specified glob pattern
     *
     * @param folder
     * @param filePattern
     * @param action
     *
     * @throws java.nio.file.NoSuchFileException if the specified {@code folder} does not exist
     */
    static void visitFiles( Map options = null, Path folder, String filePattern, Closure action ) {
        assert folder
        assert filePattern
        assert action

        if( options==null ) options = Map.of()
        final type = options.type ?: 'any'
        final walkOptions = options.followLinks == false ? EnumSet.noneOf(FileVisitOption.class) : EnumSet.of(FileVisitOption.FOLLOW_LINKS)
        final int maxDepth = getMaxDepth(options.maxDepth, filePattern)
        final includeHidden = options.hidden as Boolean ?: filePattern.startsWith('.')
        final includeDir = type in ['dir','any']
        final includeFile = type in ['file','any']
        final syntax = options.syntax ?: 'glob'
        final relative = options.relative == true

        final matcher = getPathMatcherFor("$syntax:${filePattern}", folder.fileSystem)
        final singleParam = action.getMaximumNumberOfParameters() == 1

        Files.walkFileTree(folder, walkOptions, Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {

            @Override
            FileVisitResult preVisitDirectory(Path fullPath, BasicFileAttributes attrs) throws IOException {
                final int depth = fullPath.nameCount - folder.nameCount
                final path = relativize0(folder, fullPath)
                log.trace "visitFiles > dir=$path; depth=$depth; includeDir=$includeDir; matches=${matcher.matches(path)}; isDir=${attrs.isDirectory()}"

                if (depth>0 && includeDir && matcher.matches(path) && attrs.isDirectory() && (includeHidden || !isHidden(fullPath))) {
                    def result = relative ? path : fullPath
                    singleParam ? action.call(result) : action.call(result,attrs)
                }

                return depth > maxDepth ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path fullPath, BasicFileAttributes attrs) throws IOException {
                final path = fullPath.isAbsolute()
                    ? folder.relativize(fullPath)
                    : fullPath
                log.trace "visitFiles > file=$path; includeFile=$includeFile; matches=${matcher.matches(path)}; isRegularFile=${attrs.isRegularFile()}"

                if (includeFile && matcher.matches(path) && (attrs.isRegularFile() || (options.followLinks == false && attrs.isSymbolicLink())) && (includeHidden || !isHidden(fullPath))) {
                    def result = relative ? path : fullPath
                    singleParam ? action.call(result) : action.call(result,attrs)
                }

                return FileVisitResult.CONTINUE
            }

            FileVisitResult visitFileFailed(Path currentPath, IOException e) {
                if( e instanceof FileSystemLoopException ) {
                    final path = folder.relativize(currentPath).toString()
                    final capture = FilePatternSplitter.glob().parse(filePattern).getParent()
                    final message = "Circular file path detected -- Files in the following directory will be ignored: $currentPath"
                    // show a warning message only when offending path is contained
                    // by the capture path specified by the user
                    if( capture=='./' || path.startsWith(capture) )
                        log.warn(message)
                    else
                        log.debug(message)

                    return FileVisitResult.SKIP_SUBTREE
                }
                throw  e
            }
      })

    }

    static protected Path relativize0(Path folder, Path fullPath) {
        final result = fullPath.isAbsolute()
            ? folder.relativize(fullPath)
            : fullPath
        String str
        if( folder.is(FileSystems.default) || !(str=result.toString()).endsWith('/') )
            return result
        // strip the ending slash
        def len = str.length()
        len>0 ? Paths.get(str.substring(0,str.length()-1)) : Paths.get('')
    }

    private static boolean isHidden(Path path) {
        // note: fileName can be null for root path
        def fileName = path.getFileName()
        return fileName ? fileName.toString()?.startsWith('.') : null
    }

    @PackageScope
    static int getMaxDepth( value, String filePattern ) {

        if( value != null )
            return value as int

        if( filePattern?.contains('**') )
            return Integer.MAX_VALUE

        if( filePattern?.contains('/') )
            return filePattern.split('/').findAll { it }.size()-1

        return 0
    }


    static FileSystem fileSystemForScheme(String scheme) {
        ( !scheme
                ? FileSystems.getDefault()
                : getOrCreateFileSystemFor(scheme) )
    }

    /**
     * Move a path to a target destination. It handles file or directory both to a local
     * or to a foreign file system
     *
     * @param source The source path
     * @param target The target path
     * @param options The copy options
     * @return The resulting target path
     * @throws IOException
     */
    static Path movePath(Path source, Path target, CopyOption... options)
            throws IOException
    {
        FileSystemProvider provider = source.fileSystem.provider()
        if (target.fileSystem.provider().is(provider)) {
            // same provider
            provider.move(source, target, options);
        }
        else {
            // different providers
            CopyMoveHelper.moveToForeignTarget(source, target, options);
        }
        return target;
    }

    /**
     * Copy a path to a target destination. It handles file or directory both to a local
     * or to a foreign file system.
     *
     * @param source
     * @param target
     * @param options
     * @return
     * @throws IOException
     */
    static Path copyPath(Path source, Path target, CopyOption... options)
            throws IOException
    {
        final FileSystemProvider sourceProvider = source.fileSystem.provider()
        final FileSystemProvider targetProvider = target.fileSystem.provider()
        if (targetProvider.is(sourceProvider)) {
            final linkOpts = options.contains(LinkOption.NOFOLLOW_LINKS) ? NO_FOLLOW_LINKS : FOLLOW_LINKS
            // same provider
            if( Files.isDirectory(source, linkOpts) ) {
                CopyMoveHelper.copyDirectory(source, target, options)
            }
            else {
                sourceProvider.copy(source, target, options);
            }
        }
        else {
            if( sourceProvider instanceof FileSystemTransferAware && sourceProvider.canDownload(source, target)){
                sourceProvider.download(source, target, options)
            }
            else if( targetProvider instanceof FileSystemTransferAware && targetProvider.canUpload(source, target)) {
                targetProvider.upload(source, target, options)
            }
            else {
                CopyMoveHelper.copyToForeignTarget(source, target, options)
            }
        }
        return target;
    }

    /**
     * Delete a path or a directory. If the directory is not empty
     * delete all the content of the directory.
     *
     * Note when the path is a symlink, it only remove the link without
     * following affecting the target path
     *
     * @param path
     */
    static void deletePath( Path path ) {
        def attr = readAttributes(path, LinkOption.NOFOLLOW_LINKS)
        if( !attr )
            return

        if( attr.isDirectory() ) {
            deleteDir0(path)
        }
        else {
            Files.delete(path)
        }
    }

    static private deleteDir0(Path path) {
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {

            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Files.delete(file)
                FileVisitResult.CONTINUE
            }

            FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                Files.delete(dir)
                FileVisitResult.CONTINUE
            }

        })
    }
    /**
     * List the content of a file system path
     *
     * @param path
     *      The system system directory to list
     * @return
     *      The stdout produced the execute `ls -la` command.
     *      NOTE: this output is not supposed to be exhaustive, only the first
     *      50 lines will be returned.
     */
    static String listDirectory(Path path) {

        String result = null
        Process process = null
        final target = Escape.path(path)
        try {
            process = Runtime.runtime.exec(['sh', '-c', "ls -la ${target} | head -n 50"] as String[])
            process.waitForOrKill(1_000)
            def listStatus = process.exitValue()
            if( listStatus>0 ) {
                log.debug "Can't list folder: ${target} -- Exit status: $listStatus"
            }
            else {
                result = process.text
            }
        }
        catch( IOException e ) {
            log.debug "Can't list folder: $target -- Cause: ${e.message ?: e.toString()}"
        }
        finally {
            process?.destroy()
        }

        return result
    }

    static BasicFileAttributes readAttributes(Path path, LinkOption... options) {
        try {
            Files.readAttributes(path,BasicFileAttributes,options)
        }
        catch( IOException|UnsupportedOperationException|SecurityException e ) {
            log.trace "Unable to read attributes for file: $path - cause: ${e.message}"
            return null
        }
    }

    static Path checkIfExists(Path path, Map opts) throws NoSuchFileException {

        final result = toCanonicalPath(path)
        final checkIfExists = opts?.checkIfExists as boolean
        final followLinks = opts?.followLinks == false ? [LinkOption.NOFOLLOW_LINKS] : Collections.emptyList()
        if( !checkIfExists || FilesEx.exists(result, followLinks as LinkOption[]) ) {
            return opts?.relative ? path : result
        }

        throw new NoSuchFileException(opts?.relative ? path.toString() : FilesEx.toUriString(result))
    }


    /**
     *
     * @param script
     * @return
     */
    static String getIdentifier(Path script, String prefix=null) {
        final char UNDERSCORE = '_'
        def str = FilesEx.getSimpleName(script)
        StringBuilder normalised = new StringBuilder()
        for( int i=0; i<str.length(); i++ ) {
            final char ch = str.charAt(i)
            final valid = i==0 ? Character.isJavaIdentifierStart(ch) : Character.isJavaIdentifierPart(ch)
            normalised.append( valid ? ch : UNDERSCORE )
        }

        def result = prefix ? prefix + normalised : normalised.toString()
        while(result.contains('__')) {
            result = result.replace(/__/,'_')
        }
        return result
    }

    static String getUrlProtocol(String str) {
        if( !str )
            return null
        // note: `file:/foo` is a valid file pseudo-protocol, and represents absolute
        // `/foo` path with no remote hostname specified
        if( str.startsWith('file:/'))
            return 'file'
        final m = URL_PROTOCOL.matcher(str)
        return m.matches() ? m.group(1) : null
    }

    static Path fileRootDir() {
        if( !SysEnv.get('NXF_FILE_ROOT') )
            return null
        final base = SysEnv.get('NXF_FILE_ROOT')
        if( base.startsWith('/') )
            return Paths.get(base)
        final scheme = getUrlProtocol(base)
        if( !scheme )
            throw new IllegalArgumentException("Invalid NXF_FILE_ROOT environment value - It must be an absolute path or a valid path URI - Offending value: '$base'")
        return asPath0(base)
    }

    static String baseUrl(String url) {
        if( !url )
            return null
        final m = BASE_URL.matcher(url)
        if( m.matches() )
            return m.group(1).toLowerCase()
        if( url.startsWith('file:///')) {
            return 'file:///'
        }
        if( url.startsWith('file://')) {
            return url.length()>7 ? url.substring(7).tokenize('/')[0] : null
        }
        if( url.startsWith('file:/')) {
            return 'file:/'
        }
        return null
    }

    public static HashCode getTaskHashFromPath(Path sourcePath, Path workPath) {
        assert sourcePath
        assert workPath
        if( !sourcePath.startsWith(workPath) )
            return null
        final relativePath = workPath.relativize(sourcePath)
        if( relativePath.getNameCount() < 2 )
            return null
        final bucket = relativePath.getName(0).toString()
        if( bucket.size() != 2 )
            return null
        final strHash = bucket + relativePath.getName(1).toString()
        try {
            return HashCode.fromString(strHash)
        } catch (Throwable e) {
            log.debug("String '${strHash}' is not a valid hash", e)
            return null
        }
    }
}
