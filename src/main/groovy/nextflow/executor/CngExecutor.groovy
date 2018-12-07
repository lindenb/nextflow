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
import nextflow.exception.ProcessSubmitException

@Slf4j
class CngExecutor extends SlurmExecutor {
    static private Pattern CNG_SUBMIT_REGEX = ~/Submitted Batch Session (\d+)/
    
    
    String getEnvOrDefault(String key,String defaultValue) {
    	return System.getEnv(key,defaultValue);
    	}
    String getRequiredEnv(String key) {
    	String s= getEnvOrDefault(key,null);
    	if( s == null || s.trim().isEmpty()) {
    		String message = "Failed to submit ccrt process because java property '"+key+"' is not defined or is empty. "+
	    		"You can set if on the command line with -D"+key+"=xxxx  .";
	    	log.error(message);
	    	throw new ProcessSubmitException(message);
    		}
    	return s;
    	}
    	
    /**
     * Gets the directives to submit the specified task to the cluster for execution
     *
     * @param task A {@link TaskRun} to be submitted
     * @param result The {@link List} instance to which add the job directives
     * @return A {@link List} containing all directive tokens and values.
     */
    protected List<String> getDirectives(TaskRun task, List<String> result) {
	//-E \"extra_parameters...\" : extra parameters to pass directly to the underlying batch system
        result << '-E' << ('\"-D ' + task.workDir + ' --no-requeue\"')
        result << '-r' << getJobNameFor(task).replaceAll("[^A-Za-z_0-9]+","_")
        result << '-o' << task.workDir.resolve(TaskRun.CMD_OUTFILE)
        result << '-e' << task.workDir.resolve(TaskRun.CMD_ERRFILE)
	
	result << '-A' << getRequiredEnv("ccc.project")
	
	if(getEnvOrDefault("ccc.study",null)!=null) {
		result << '-s' << getEnvOrDefault("ccc.study",null);
		}
	result << '-q' << getEnvOrDefault("ccc.queue","broadwell");

        if( task.config.cpus > 1 ) {
            result << '-c' << task.config.cpus.toString()
        }

        if( task.config.time ) {
            	result << '-T' << task.config.getTime().toSeconds()
        	}
        else
        	{
        	result << '-T' <<  getEnvOrDefault("ccc.time","86400")
        	}

        if( task.config.getMemory() ) {
            result << '-M' << task.config.getMemory().toMega().toString()
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
    protected Pattern  getSubmitRegex() {
    	return 	CNG_SUBMIT_REGEX;
    }

    @Override
    protected List<String> getKillCommand() { ['ccc_mdel'] }



    @Override
    protected List<String> queueStatusCommand(Object queue) {

        final result = ['sacct','--noheader','-o','jobid%-14s,State%-20s']

        //if( queue )
        //    result << '-p' << queue.toString()

        final user = System.getProperty('user.name')
        if( user )
            result << '-u' << user
        else
            log.debug "[CNGExecutor]:queueStatusCommand Cannot retrieve current user"

        return result
    }

 
   private QueueStatus decodeQueueStatus(final String s)
   	{
   	if(s.equals("COMPLETED")) {
   		return QueueStatus.DONE;
   		}
   	else if(s.equals("RUNNING")) {
   		return QueueStatus.RUNNING;
   		}
   	else if(s.equals("FAILED")) {
   		return QueueStatus.ERROR;
   		}
   	else if(s.equals("PENDING")) {
   		return QueueStatus.PENDING;
   		}
   	else if(s.equals("CANCELLED")) {
   		return QueueStatus.ERROR;
   		}
   	else
   		{
   		 log.error "[CNG Executor] invalid status identifier for Status: `$s`"
   		return QueueStatus.ERROR;
   		}
   	}


    @Override
    protected Map<String, QueueStatus> parseQueueStatus(String text) {

        def result = [:]

        text.eachLine { String line ->
            def cols = line.split(/\s+/)
            if( cols.size() == 2 ) {
                result.put( cols[0], this.decodeQueueStatus(cols[1]) )
            }
            else {
                log.error "[CNG Executor] invalid status line: `$line`"
            }
        }

        return result
    }


}
