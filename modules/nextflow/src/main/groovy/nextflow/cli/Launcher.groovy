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

package nextflow.cli

import static nextflow.Const.*

import java.lang.reflect.Field

import com.beust.jcommander.DynamicParameter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.beust.jcommander.Parameters
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.BuildInfo
import nextflow.exception.AbortOperationException
import nextflow.exception.AbortRunException
import nextflow.exception.ConfigParseException
import nextflow.exception.ScriptCompilationException
import nextflow.exception.ScriptRuntimeException
import nextflow.secret.SecretsLoader
import nextflow.util.Escape
import nextflow.util.LoggerHelper
import nextflow.util.ProxyConfig
import nextflow.util.SpuriousDeps
import org.eclipse.jgit.api.errors.GitAPIException

import static nextflow.util.SysHelper.dumpThreads

/**
 * Main application entry point. It parses the command line and
 * launch the pipeline execution.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class Launcher {

    /**
     * Create the application command line parser
     *
     * @return An instance of {@code CliBuilder}
     */

    private JCommander jcommander

    private CliOptions options

    private boolean fullVersion

    private CmdBase command

    private String cliString

    private List<CmdBase> allCommands

    private List<String> normalizedArgs

    private boolean daemonMode

    private String colsString

    /**
     * Create a launcher object and parse the command line parameters
     *
     * @param args The command line arguments provided by the user
     */
    Launcher() {
        init()
    }

    protected void init() {
        allCommands = (List<CmdBase>)[
                new CmdClean(),
                new CmdClone(),
                new CmdConsole(),
                new CmdFs(),
                new CmdInfo(),
                new CmdList(),
                new CmdLog(),
                new CmdPull(),
                new CmdRun(),
                new CmdKubeRun(),
                new CmdDrop(),
                new CmdConfig(),
                new CmdNode(),
                new CmdView(),
                new CmdHelp(),
                new CmdSelfUpdate(),
                new CmdPlugin(),
                new CmdInspect(),
                new CmdLint(),
                new CmdLineage()
        ]

        if(SecretsLoader.isEnabled())
            allCommands.add(new CmdSecret())

        // legacy command
        final cmdCloud = SpuriousDeps.cmdCloud()
        if( cmdCloud )
            allCommands.add(cmdCloud)

        options = new CliOptions()
        jcommander = new JCommander(options)
        for( CmdBase cmd : allCommands ) {
            cmd.launcher = this;
            jcommander.addCommand(cmd.name, cmd, aliases(cmd))
        }
        jcommander.setProgramName( APP_NAME )
    }

    private static final String[] EMPTY = new String[0]

    private static String[] aliases(CmdBase cmd) {
        final aliases = cmd.getClass().getAnnotation(Parameters)?.commandNames()
        return aliases ?: EMPTY
    }

    /**
     * Create the Jcommander 'interpreter' and parse the command line arguments
     */
    @PackageScope
    Launcher parseMainArgs(String... args) {
        this.cliString = makeCli(System.getenv('NXF_CLI'), args)
        this.colsString = System.getenv('COLUMNS')

        def cols = getColumns()
        if( cols )
            jcommander.setColumnSize(cols)

        normalizedArgs = normalizeArgs(args)
        jcommander.parse( normalizedArgs as String[] )
        fullVersion = '-version' in normalizedArgs
        command = allCommands.find { it.name == jcommander.getParsedCommand()  }
        // whether is running a daemon
        daemonMode = command instanceof CmdNode
        // set the log file name
        checkLogFileName()

        return this
    }

    protected String makeCli(String cli, String... args) {
        if( !cli )
            cli = 'nextflow'
        if( !args )
            return cli
        def cmd = ' ' + args[0]
        int p = cli.indexOf(cmd)
        if( p!=-1 )
            cli = cli.substring(0,p)
        if( cli.endsWith('nextflow') )
            cli = 'nextflow'
        cli += ' ' + Escape.cli(args)
        return cli
    }

    private void checkLogFileName() {
        if( !options.logFile ) {
            if( isDaemon() )
                options.logFile = System.getenv('NXF_LOG_FILE') ?: '.node-nextflow.log'
            else if( command instanceof CmdRun || options.debug || options.trace )
                options.logFile = System.getenv('NXF_LOG_FILE') ?: ".nextflow.log"
        }
    }

    private short getColumns() {
        if( !colsString ) {
            return 0
        }

        try {
            colsString.toShort()
        }
        catch( Exception e ) {
            log.debug "Unexpected terminal \$COLUMNS value: $colsString"
            return 0
        }
    }

    CliOptions getOptions() { options }

    List<String> getNormalizedArgs() { normalizedArgs }

    String getCliString() { cliString }

    boolean isDaemon() { daemonMode }

    /**
     * normalize the command line arguments to handle some corner cases
     */
    @PackageScope
    List<String> normalizeArgs( String ... args ) {

        List<String> normalized = []
        int i=0
        while( true ) {
            if( i==args.size() ) { break }

            def current = args[i++]
            normalized << current

            // when the first argument is a file, it's supposed to be a script to be executed
            if( i==1 && !allCommands.find { it.name == current } && new File(current).isFile()  ) {
                normalized.add(0,CmdRun.NAME)
            }

            else if( current == '-resume' ) {
                if( i<args.size() && !args[i].startsWith('-') && (args[i]=='last' || args[i] =~~ /[0-9a-f]{8}\-[0-9a-f]{4}\-[0-9a-f]{4}\-[0-9a-f]{4}\-[0-9a-f]{8}/) ) {
                    normalized << args[i++]
                }
                else {
                    normalized << 'last'
                }
            }
            else if( current == '-test' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '%all'
            }

            else if( current == '-dump-hashes' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-cloudcache' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-trace' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-report' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-timeline' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-dag' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-docker' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-podman' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-singularity' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-apptainer' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-charliecloud' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-conda' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-spack' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-weblog' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-tower' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-with-wave' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '-'
            }

            else if( current == '-ansi-log' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << 'true'
            }

            else if( (current == '-stub' || current == '-stub-run') && (i==args.size() || args[i].startsWith('-'))) {
                normalized << 'true'
            }

            else if( (current == '-N' || current == '-with-notification') && (i==args.size() || args[i].startsWith('-'))) {
                normalized << 'true'
            }

            else if( current == '-with-fusion' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << 'true'
            }

            else if( current == '-syslog' && (i==args.size() || args[i].startsWith('-') || allCommands.find { it.name == args[i] } )) {
                normalized << 'localhost'
            }

            else if( current == '-dump-channels' && (i==args.size() || args[i].startsWith('-'))) {
                normalized << '*'
            }

            else if( current ==~ /^\-\-[a-zA-Z\d].*/ && !current.contains('=') ) {
                current += '='
                current += ( i<args.size() && isValue(args[i]) ? args[i++] : 'true' )
                normalized[-1] = current
            }

            else if( current ==~ /^\-process\..+/ && !current.contains('=')) {
                current += '='
                current += ( i<args.size() && isValue(args[i]) ? args[i++] : 'true' )
                normalized[-1] = current
            }

            else if( current ==~ /^\-cluster\..+/ && !current.contains('=')) {
                current += '='
                current += ( i<args.size() && isValue(args[i]) ? args[i++] : 'true' )
                normalized[-1] = current
            }

            else if( current ==~ /^\-executor\..+/ && !current.contains('=')) {
                current += '='
                current += ( i<args.size() && isValue(args[i]) ? args[i++] : 'true' )
                normalized[-1] = current
            }

            else if( current == 'run' && i<args.size() && args[i] == '-' ) {
                i++
                normalized << '-stdin'
            }
        }

        return normalized
    }

    static private boolean isValue( String x ) {
        if( !x ) return false                   // an empty string -> not a value
        if( x.size() == 1 ) return true         // a single char is not an option -> value true
        !x.startsWith('-') || x.isNumber() || x.contains(' ')
    }

    CmdBase findCommand( String cmdName ) {
        allCommands.find { it.name == cmdName }
    }

    /**
     * Print the usage string for the given command - or -
     * the main program usage string if not command is specified
     *
     * @param command The command for which get help or {@code null}
     * @return The usage string
     */
    void usage(String command = null ) {

        if( command ) {
            def exists = allCommands.find { it.name == command } != null
            if( !exists ) {
                println "Asking help for unknown command: $command"
                return
            }

            jcommander.usage(command)
            return
        }

        println "Usage: nextflow [options] COMMAND [arg...]\n"
        printOptions(CliOptions)
        printCommands(allCommands)
    }

    @CompileDynamic
    protected void printOptions(Class clazz) {
        List params = []
        for( Field f : clazz.getDeclaredFields() ) {
            def p = f.getAnnotation(Parameter)
            if(!p)
                p = f.getAnnotation(DynamicParameter)

            if( p && !p.hidden() && p.description() && p.names() )
                params.add(p)

        }

        params.sort(true) { it -> it.names()[0] }

        println "Options:"
        for( def p : params ) {
            println "  ${p.names().join(', ')}"
            println "     ${p.description()}"
        }
    }

    protected void printCommands(List<CmdBase> commands) {
        println "\nCommands:"

        int len = 0
        def all = new TreeMap<String,String>()
        new ArrayList<CmdBase>(commands).each {
            def description = it.getClass().getAnnotation(Parameters)?.commandDescription()
            if( description ) {
                all[it.name] = description
                if( it.name.size()>len ) len = it.name.size()
            }
        }

        all.each { String name, String desc ->
            print '  '
            print name.padRight(len)
            print '   '
            println desc
        }
        println ''
    }

    Launcher command( String[] args ) {
        /*
         * CLI argument parsing
         */
        try {
            parseMainArgs(args)
            LoggerHelper.configureLogger(this)
        }
        catch( ParameterException e ) {
            // print command line parsing errors
            // note: use system.err.println since if an exception is raised
            //       parsing the cli params the logging is not configured
            System.err.println "${e.getMessage()} -- Check the available commands and options and syntax with 'help'"
            System.exit(1)

        }
        catch ( AbortOperationException e ) {
            final msg = e.message ?: "Unknown abort reason"
            System.err.println(LoggerHelper.formatErrMessage(msg, e))
            System.exit(1)
        }
        catch( Throwable e ) {
            e.printStackTrace(System.err)
            System.exit(1)
        }
        return this
    }

    protected void checkForHelp() {
        if( options.help || !command || command.help ) {
            if( command instanceof UsageAware ) {
                (command as UsageAware).usage()
                // reset command to null to skip default execution
                command = null
                return
            }

            // replace the current command with the `help` command
            def target = command?.name
            command = allCommands.find { it instanceof CmdHelp }
            if( target ) {
                (command as CmdHelp).args = [target]
            }
        }

    }

    /**
     * Launch the pipeline execution
     */
    int run() {

        /*
         * setup environment
         */
        setupEnvironment()

        /*
         * Real execution starts here
         */
        try {
            log.debug '$> ' + cliString

            // -- print out the version number, then exit
            if ( options.version ) {
                println getVersion(fullVersion)
                return 0
            }

            // -- print out the program help, then exit
            checkForHelp()

            // launch the command
            command?.run()

            if( log.isTraceEnabled())
                log.trace "Exit\n" + dumpThreads()
            return 0
        }

        catch( AbortRunException e ) {
            return(1)
        }

        catch ( AbortOperationException e ) {
            def message = e.getMessage()
            if( message )
                System.err.println(LoggerHelper.formatErrMessage(message,e))
            log.debug ("Operation aborted", e.cause ?: e)
            return(1)
        }

        catch ( GitAPIException e ) {
            System.err.println e.getMessage() ?: e.toString()
            log.debug ("Operation aborted", e.cause ?: e)
            return(1)
        }

        catch( ConfigParseException e )  {
            def message = e.message
            if( e.cause?.message ) {
                message += "\n\n${e.cause.message.toString().indent('  ')}"
            }
            log.error(message, e.cause ?: e)
            return(1)
        }

        catch( ScriptCompilationException e ) {
            log.error(e.message, e)
            return(1)
        }

        catch ( ScriptRuntimeException | IllegalArgumentException e) {
            log.error(e.message, e)
            return(1)
        }

        catch( IOException e ) {
            log.error(e.message, e)
            return(1)
        }

        catch( Throwable fail ) {
            log.error("@unknown", fail)
            return(1)
        }

    }

    /**
     * set up environment and system properties. It checks the following
     * environment variables:
     * <li>http_proxy</li>
     * <li>https_proxy</li>
     * <li>ftp_proxy</li>
     * <li>HTTP_PROXY</li>
     * <li>HTTPS_PROXY</li>
     * <li>FTP_PROXY</li>
     * <li>NO_PROXY</li>
     */
    private void setupEnvironment() {

        final env = System.getenv()
        setProxy('HTTP',env)
        setProxy('HTTPS',env)
        setProxy('FTP',env)

        setProxy('http',env)
        setProxy('https',env)
        setProxy('ftp',env)

        setNoProxy(env)

        setHttpClientProperties(env)
    }

    static void setHttpClientProperties(Map<String,String> env) {
        // Set the httpclient connection pool timeout to 10 seconds.
        // This required because the default is 20 minutes, which cause the error
        // "HTTP/1.1 header parser received no bytes" when in some circumstances
        // https://github.com/nextflow-io/nextflow/issues/3983#issuecomment-1702305137
        System.setProperty("jdk.httpclient.keepalive.timeout", env.getOrDefault("NXF_JDK_HTTPCLIENT_KEEPALIVE_TIMEOUT","10"))
        if( env.get("NXF_JDK_HTTPCLIENT_CONNECTIONPOOLSIZE") )
            System.setProperty("jdk.httpclient.connectionPoolSize", env.get("NXF_JDK_HTTPCLIENT_CONNECTIONPOOLSIZE"))
    }

    /**
     * Set no proxy property if defined in the launching env
     *
     * See for details
     * https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html
     *
     * @param env
     */
    @PackageScope
    static void setNoProxy(Map<String,String> env) {
        final noProxy = env.get('NO_PROXY') ?: env.get('no_proxy')
        if(noProxy) {
            System.setProperty('http.nonProxyHosts', noProxy.tokenize(',').join('|'))
        }
    }


    /**
     * Setup proxy system properties and optionally configure the network authenticator
     *
     * See:
     * http://docs.oracle.com/javase/6/docs/technotes/guides/net/proxies.html
     * https://github.com/nextflow-io/nextflow/issues/24
     *
     * @param qualifier Either {@code http/HTTP} or {@code https/HTTPS}.
     * @param env The environment variables system map
     */
    @PackageScope
    static void setProxy(String qualifier, Map<String,String> env ) {
        assert qualifier in ['http','https','ftp','HTTP','HTTPS','FTP']
        def str = null
        def var = "${qualifier}_" + (qualifier.isLowerCase() ? 'proxy' : 'PROXY')

        // -- setup HTTP proxy
        try {
            final proxy = ProxyConfig.parse(str = env.get(var.toString()))
            if( proxy ) {
                // set the expected protocol
                proxy.protocol = qualifier.toLowerCase()
                log.debug "Setting $qualifier proxy: $proxy"
                System.setProperty("${qualifier.toLowerCase()}.proxyHost", proxy.host)
                if( proxy.port )
                    System.setProperty("${qualifier.toLowerCase()}.proxyPort", proxy.port)
                if( proxy.authenticator() ) {
                    log.debug "Setting $qualifier proxy authenticator"
                    Authenticator.setDefault(proxy.authenticator())
                }
            }
        }
        catch ( MalformedURLException e ) {
            log.warn "Not a valid $qualifier proxy: '$str' -- Check the value of variable `$var` in your environment"
        }

    }

    /**
     * Hey .. Nextflow starts here!
     *
     * @param args The program options as specified by the user on the CLI
     */
    static void main(String... args)  {
        LoggerHelper.bootstrapLogger()
        final status = new Launcher() .command(args) .run()
        if( status )
            System.exit(status)
    }


    /**
     * Print the application version number
     * @param full When {@code true} prints full version number including build timestamp
     * @return The version number string
     */
    static String getVersion(boolean full = false) {

        if ( full ) {
            SPLASH
        }
        else {
            "${APP_NAME} version ${BuildInfo.version}.${BuildInfo.buildNum}"
        }

    }

    /*
     * The application 'logo'
     */
    /*
     * The application 'logo'
     */
    static public final String SPLASH =

"""
      N E X T F L O W
      version ${BuildInfo.version} build ${BuildInfo.buildNum}
      created ${BuildInfo.timestampUTC} ${BuildInfo.timestampDelta}
      cite doi:10.1038/nbt.3820
      http://nextflow.io
"""

}
