package nextflow.file

import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Nextflow
import nextflow.Session
import nextflow.exception.ProcessStageException
/**
 * Move foreign (ie. remote) files to the staging work area
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class FilePorter {

    static protected ExecutorService executor

    static Random rnd = Random.newInstance()

    /**
     * Given a map of files, copies all the ones stored in a foreign file system
     * and store them in the current working directory
     *
     * @param filesMap A map of files
     * @return A new files map in which all foreign {@link Path} are replaced with local paths
     */
    Map<String,Path> stageForeignFiles(Map<String, Path> filesMap) {
        List<Callable<NamePathPair>> actions = []
        List<Path> paths = []

        // check for foreign file to copy
        for( Map.Entry<String,Path> entry : filesMap ) {
            def name = entry.getKey()
            def path = entry.getValue()
            if( path.fileSystem == FileHelper.workDirFileSystem )
                continue
            // copy the path with a thread pool
            actions << { new NamePathPair(name, stageForeignFile(path)) } as Callable<NamePathPair>
            paths << path
        }

        // no foreign file to copy, just return the original map
        if( !actions )
            return filesMap

        log.trace "Stage foreign files: $paths"
        def result = new HashMap(filesMap)

        def futures = getExecutor().invokeAll(actions)
        log.trace "Stage foreign files completes: $paths"

        try {
            for( Future<NamePathPair> fut : futures ) {
                final pair = fut.get()
                result.put( pair.name, pair.path )
            }
        }
        catch( ExecutionException e ) {
            throw e.cause ?: e
        }
        return result
    }

    /**
     * Download a foreign file (ie. remote) storing it the current pipeline execution directory
     *
     * @param filePath The {@link Path} of the remote file to copy
     * @return The path of a local copy of the remote file
     */
    protected Path stageForeignFile(Path filePath) {
        int max = getMaxRetries()
        int count = 0
        while( true ) {
            try {
                return stageForeignFile0(filePath)
            }
            catch( IOException e ) {
                if( count++ < max && !(e instanceof NoSuchFileException )) {
                    log.warn "Unable to stage foreign file: ${filePath.toUriString()} (try ${count}) -- Cause: $e.message"
                    sleep (10 + rnd.nextInt(300))
                    continue
                }

                throw new ProcessStageException(fmtError(filePath,e), e)
            }
        }
    }

    private String fmtError(Path filePath, Exception e) {
        def message = "Can't stage file ${filePath.toUri().toString()}"
        if( e instanceof NoSuchFileException )
            message += " -- file does not exist"
        else if( e.message )
            message += " -- reason: ${e.message}"
        return message
    }

    private Path stageForeignFile0(Path source) {
        final target = Nextflow.tempDir().resolve(source.getName())
        log.debug "Copying foreign file ${source.toUriString()} to work dir: ${target.toUriString()}"
        return FileHelper.copyPath(source, target)
    }

    @Canonical
    static private class NamePathPair {
        String name
        Path path

        String toString() {
            "name=$name,path=$path"
        }
    }

    /**
     * @return
     *      The maximum number of threads used for parallel files copy.
     *      This value can be defined by using the {@code filePorter.maxThreads} configuration setting
     *      (default: <number of avail CPU cores>)
     */
    static protected int getMaxThreads() {
        Integer result = Global.session.config.navigate('filePorter.maxThreads') as Integer
        if( !result )
            result = Runtime.runtime.availableProcessors()
        log.debug "filePorter.maxThreads=$result"
        return result
    }

    /**
     * @return
     *      The maximum number of time the copy process is retried in case of an unexpected error.
     *      This value can be defined by using the {@code filePorter.maxRetries} configuration setting (default: 3).
     */
    static int getMaxRetries() {
        Integer result = Global.session.config.navigate('filePorter.maxRetries') as Integer
        if( !result )
            result = 3
        log.debug "filePorter.maxThreads=$result"
        return result
    }

    /**
     * @return Creates lazily the executor service used to stage remote files
     */
    static synchronized private ExecutorService getExecutor() {
        if( !executor ) {
            executor = new ThreadPoolExecutor(
                    0,
                    getMaxThreads(),
                    60L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(),
                    new DownloaderThreadFactory() )

            // register the shutdown on termination
            def session = Global.session as Session
            if( session ) {
                session.onShutdown {
                    executor.shutdown()
                    executor.awaitTermination(1,TimeUnit.MINUTES)
                }
            }
        }
        return executor
    }

    /**
     * Custom thread factory
     */
    static private class DownloaderThreadFactory implements ThreadFactory {
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix = "FilePorter-"

        DownloaderThreadFactory() {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
            if (t.isDaemon())
                t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}
