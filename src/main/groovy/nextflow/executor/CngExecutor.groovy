/*
 * Copied from CngExecutor . Original file was:
 * Copyright (c) 2013-2018, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2018, Paolo Di Tommaso and the respective authors.
 * Author : Pierre Lindenbaum

 */

package nextflow.executor
import java.nio.file.Path
import java.util.regex.Pattern

import groovy.util.logging.Slf4j
import nextflow.processor.TaskRun


@Slf4j
class CngExecutor extends SlurmExecutor {

    /**
     * Gets the directives to submit the specified task to the cluster for execution
     *
     * @param task A {@link TaskRun} to be submitted
     * @param result The {@link List} instance to which add the job directives
     * @return A {@link List} containing all directive tokens and values.
     */
    protected List<String> getDirectives(TaskRun task, List<String> result) {

        result << '-D' << quote(task.workDir)
        result << '-J' << getJobNameFor(task)
        result << '-o' << quote(task.workDir.resolve(TaskRun.CMD_LOG))     // -o OUTFILE and no -e option => stdout and stderr merged to stdout/OUTFILE
        result << '--no-requeue' << '' // note: directive need to be returned as pairs

	result << '-s' << quote(task.study)
	if( task.config.project ) {
	result << '-A' << quote(task.config.project)
	}
	result << '-q' << quote(task.queuename)


        if( task.config.cpus > 1 ) {
            result << '-c' << task.config.cpus.toString()
        }

        if( task.config.time ) {
            result << '-t' << task.config.getTime().format('HH:mm:ss')
        }

        if( task.config.getMemory() ) {
            result << '--mem' << task.config.getMemory().toMega().toString()
        }

        // the requested partition (a.k.a queue) name
        if( task.config.queue ) {
            result << '-p' << (task.config.queue.toString())
        }

        // -- at the end append the command script wrapped file name
        if( task.config.clusterOptions ) {
            result << task.config.clusterOptions.toString() << ''
        }

        return result
    }

    String getHeaderToken() { '#MSUB' }

    
    @Override
    List<String> getSubmitCommandLine(TaskRun task, Path scriptFile ) {

        ['ccc_msub', scriptFile.getName()]

    }


    @Override
    protected List<String> getKillCommand() { ['ccc_mdel'] }

}
