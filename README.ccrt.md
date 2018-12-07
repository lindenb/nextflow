# Motivation

the `pl_cng` branch is a fork of nextflow running with the cea/ccrt infrastructure, sending the jobs using `ccc_msub`.

# Compiling

```
$ git clone https://github.com/lindenb/nextflow
$ cd nextflow
$ git checkout pl_cng
$ make
```

The fat jar will be in `build/libs/nextflow-*-SNAPSHOT-all.jar`

# Specifying the CCRT executor

The following terms are equivalent to set the executor ( https://www.nextflow.io/docs/latest/executor.html )

  * 'cng' 
  * 'ccc'
  * 'ccrt'
  * 'cea'

# Environment Variables

Those variables are set on the java command line using `-D` . e.g: `-Dccc.queue=broadwell`

  * **ccc.queue** : the queue (default: `broadwell`)
  * **ccc.project** : the project (option `-A` of ccc_msub). REQUIRED
  * **ccc.study** : the study (option `-s` of ccc_msub). REQUIRED
  * **ccc.time** : the time (option `-T` of ccc_msub). Overrided the by value of `task.config.getTime().toSeconds()` otherwise use the default value `86400`.

# Example

The nextflow file. 


```
secs = Channel.from(10,30,60)

process waitAndCreateFile {
    executor 'ccrt'
    tag "wait ${sec} seconds"
    input:
	val sec from secs
    output:
    	file("jeter${sec}.txt") into out1
    script:
    """
    sleep ${sec} && echo "Hello ${sec}" > jeter${sec}.txt
    """
}

process collectAndEnd {
    executor 'ccrt'
    tag "collect ${files.size()} file(s)"
    input:
	val files  from out1.collect()
    output:
	file("done.txt") into out2
    script:
    """
	echo "${files}" > done.txt
    """
   }

```

invoke:

```
$ java -Dccc.project=fg0073 -jar nextflow-18.12.0-SNAPSHOT-all.jar run -resume test01.nf

N E X T F L O W  ~  version 18.11.0-edge
Launching `test01.nf` [drunk_minsky] - revision: fe6fe74d56
[warm up] executor > ccrt
[49/b7d211] Submitted process > waitAndCreateFile (wait 10 seconds)
[77/dfafc7] Submitted process > waitAndCreateFile (wait 30 seconds)
[dd/ac9f96] Submitted process > waitAndCreateFile (wait 60 seconds)
[52/951fe1] Submitted process > collectAndEnd (collect 3 file(s))
```

check the final file

```
$ cat work/52/951fe1f36f6aeb045f25f69511451b/done.txt
[/ccc/scratch/cont007/fg0073/lindenbp/NEXTFLOW/work/49/b7d211c79dfaeb11c574d4a9011a76/jeter10.txt, /ccc/scratch/cont007/fg0073/lindenbp/NEXTFLOW/work/77/dfafc76bd1edffa83e425ebbea600b/jeter30.txt, /ccc/scratch/cont007/fg0073/lindenbp/NEXTFLOW/work/dd/ac9f96619b6b3f791d06bea9b5392c/jeter60.txt]
```

while it's running, check the status...

```
BATCHID  NAME     USER       PROJECT          QUEUE     QOS     PRIO   SUBHOST      EXEHOST      STA    TUSED     TLIM    MLIM   CLIM
-------  ----     ----       -------          -----     ------  ------ -------      -------      --- -------- -------- ------- ------
3193446  nf_waitA lindenbp   fg0073@broadwell broadwell normal  276947 cobalt172    cobalt1053   R01        8    86400    4390      1
3193447  nf_waitA lindenbp   fg0073@broadwell broadwell normal  276947 cobalt172    cobalt1053   R01        8    86400    4390      1
3193448  nf_waitA lindenbp   fg0073@broadwell broadwell normal  276947 cobalt172    cobalt1053   R01        8    86400    4390      1
```

re-run, processes are cached (nothing to do...)

```
$ java -Dccc.project=fg0073 -jar nextflow.jar run -resume test01.nf

N E X T F L O W  ~  version 18.11.0-edge
Launching `test01.nf` [crazy_brenner] - revision: fe6fe74d56
[warm up] executor > ccrt
[dd/ac9f96] Cached process > waitAndCreateFile (wait 60 seconds)
[49/b7d211] Cached process > waitAndCreateFile (wait 10 seconds)
[77/dfafc7] Cached process > waitAndCreateFile (wait 30 seconds)
[52/951fe1] Cached process > collectAndEnd (collect 3 file(s))
```

test killing

```
$ java -Dccc.project=fg0073 -jar nextflow.jar run  test01.nf 
N E X T F L O W  ~  version 18.11.0-edge
Launching `test01.nf` [hopeful_austin] - revision: fe6fe74d56
[warm up] executor > ccrt
[68/f503dd] Submitted process > waitAndCreateFile (wait 10 seconds)
[67/14d125] Submitted process > waitAndCreateFile (wait 60 seconds)
[3a/57c49b] Submitted process > waitAndCreateFile (wait 30 seconds)
^C
WARN: Killing pending tasks (3)

$ ccc_mstat -u lindenbp
BATCHID  NAME     USER       PROJECT          QUEUE     QOS     PRIO   SUBHOST      EXEHOST      STA    TUSED     TLIM    MLIM   CLIM
-------  ----     ----       -------          -----     ------  ------ -------      -------      --- -------- -------- ------- ------
```




# Author

Pierre Lindenbaum

