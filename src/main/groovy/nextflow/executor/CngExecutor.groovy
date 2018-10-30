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
        result << '-o' << task.workDir.resolve(TaskRun.CMD_OUTFILE)
        result << '-e' << task.workDir.resolve(TaskRun.CMD_ERRFILE)
	
	
	boolean got_config = false;
	boolean got_project = false;
	boolean got_queue = false;
	if( task.config.clusterOptions ) {
		final List<String> opts = task.config.getClusterOptionsAsList();
		int i=0;
		for(i=0;i+1 < opts.size();i+=2)
			{
			if(!got_config && opts.get(i).equals("study"))
				{
				result << '-s' <<  opts.get(i+1);
				got_config = true;
				}
			else if(!got_project && opts.get(i).equals("project"))
				{
				result << '-A' <<  opts.get(i+1);
				got_project = true;
				}
			else if(!got_queue && opts.get(i).equals("queue"))
				{
				result << '-q' <<  opts.get(i+1);
				got_queue = true;
				}
			}
		
		}
	
	if(!got_project)
		{
		result << '-A' << "undefined"
		}
	
	if(!got_queue)
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

        ['ccc_msub', scriptFile.getName()]

    }
    
   @Override
    protected Pattern  getSubmitRegex() {
    	return 	CNG_SUBMIT_REGEX;
    }

    @Override
    protected List<String> getKillCommand() { ['ccc_mdel'] }

}
