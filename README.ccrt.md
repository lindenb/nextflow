# Motivation

the pl_cng branch is a fork of nextflow running with the cea/ccrt infrastructure, sending the jobs using `ccc_msub`.

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

# Author

Pierre Lindenbaum

