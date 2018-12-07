secs = Channel.from(1,2,3,4,5,6,7,8,9,10,20,30,40,50,60)

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

