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
    static private Pattern CNG_SUBMIT_REGEX = ~/Submitted Batch Session (\d+)/
    /**
     * Gets the directives to submit the specified task to the cluster for execution
     *
     * @param task A {@link TaskRun} to be submitted
     * @param result The {@link List} instance to which add the job directives
     * @return A {@link List} containing all directive tokens and values.
     */
    protected List<String> getDirectives(TaskRun task, List<String> result) {
	//-E \"extra_parameters...\" : extra parameters to pass directly to the underlying batch system
        result << '-E' << ('-D ' + task.workDir + ' --no-requeue')
        result << '-r' << getJobNameFor(task)
        result << '-o' << task.workDir.resolve(TaskRun.CMD_LOG)     // -o OUTFILE and no -e option => stdout and stderr merged to stdout/OUTFILE
        result << '--no-requeue' << '' // note: directive need to be returned as pairs
	
	
	
	if( task.config.containsKey('study') ) {
		result << '-s' << quote(task.config.study)
		}
	
	if( task.config.containsKey('project') ) {
		result << '-A' << task.config.project
		}
	if( task.config.containsKey('queue') ) {
		result << '-q' << task.config.queue
		}
	else
		{
		result << '-q' << 'broadwell'
		}

        if( task.config.cpus > 1 ) {
            result << '-c' << task.config.cpus.toString()
        }

        if( task.config.time ) {
            	result << '-T' << task.config.getTime().toSeconds()
        	}
        else
        	{
        	result << '-T' << '86400'
        	}

        if( task.config.getMemory() ) {
            result << '-M' << task.config.getMemory().toMega().toString()
        }



        // -- at the end append the command script wrapped file name
        if( task.config.clusterOptions ) {
            result << task.config.clusterOptions.toString() << ''
        }
	java.lang.System.err.println(result);
        return result
    }

    String getHeaderToken() { '#MSUB' }

    
    @Override
    List<String> getSubmitCommandLine(TaskRun task, Path scriptFile ) {

        ['ccc_mprun', scriptFile.getName()]

    }
    
   @Override
    protected Pattern  getSubmitRegex() {
    	return 	CNG_SUBMIT_REGEX;
    }

    @Override
    protected List<String> getKillCommand() { ['ccc_mdel'] }

}
