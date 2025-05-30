/*
 * Copyright 2013-2025, Seqera Labs
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

package nextflow.lineage

import java.time.ZoneId

import nextflow.lineage.model.Checksum
import nextflow.lineage.model.DataPath
import nextflow.lineage.model.Parameter
import nextflow.lineage.model.Workflow
import nextflow.lineage.model.WorkflowOutput
import nextflow.lineage.model.WorkflowRun
import nextflow.lineage.config.LineageConfig
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class LinUtilsTest extends Specification{

    @TempDir
    Path tempDir

    Path storeLocation
    LineageConfig config

    def setup() {
        storeLocation = tempDir.resolve("store")
        def configMap = [enabled: true, store: [location: storeLocation.toString()]]
        config = new LineageConfig(configMap)
    }

    def 'should convert to Date'(){
        expect:
        LinUtils.toDate(FILE_TIME) == DATE
        where:
        FILE_TIME                   | DATE
        null                        | null
        FileTime.fromMillis(1234)   | Instant.ofEpochMilli(1234).atZone(ZoneId.systemDefault())?.toOffsetDateTime()
    }

    def 'should convert to FileTime'(){
        expect:
        LinUtils.toFileTime(DATE) == FILE_TIME
        where:
        FILE_TIME                   | DATE
        null                        | null
        FileTime.fromMillis(1234)   | OffsetDateTime.ofInstant(Instant.ofEpochMilli(1234), ZoneOffset.UTC)
    }


    def 'should query'() {
        given:
        def uniqueId = UUID.randomUUID()
        def mainScript = new DataPath("file://path/to/main.nf", new Checksum("78910", "nextflow", "standard"))
        def workflow = new Workflow([mainScript], "https://nextflow.io/nf-test/", "123456")
        def key = "testKey"
        def value1 = new WorkflowRun(workflow, uniqueId.toString(), "test_run", [new Parameter("String", "param1", "value1"), new Parameter("String", "param2", "value2")])
        def outputs1 = new WorkflowOutput(OffsetDateTime.now(), "lid://testKey", [new Parameter( "String", "output", "name")] )
        def lidStore = new DefaultLinStore()
        lidStore.open(config)
        lidStore.save(key, value1)
        lidStore.save("$key#output", outputs1)

        when:
        List<Object> params = LinUtils.query(lidStore, new URI('lid://testKey#params'))
        then:
        params.size() == 1
        params[0] instanceof List<Parameter>
        (params[0] as List<Parameter>).size() == 2

        when:
        List<Object> outputs = LinUtils.query(lidStore, new URI('lid://testKey#output'))
        then:
        outputs.size() == 1
        outputs[0] instanceof List<Parameter>
        def param = (outputs[0] as List)[0] as Parameter
        param.name == "output"

        when:
        LinUtils.query(lidStore, new URI('lid://testKey#no-exist'))
        then:
        thrown(IllegalArgumentException)

        when:
        LinUtils.query(lidStore, new URI('lid://testKey#outputs.no-exist'))
        then:
        thrown(IllegalArgumentException)

        when:
        LinUtils.query(lidStore, new URI('lid://no-exist#something'))
        then:
        thrown(IllegalArgumentException)
    }

    def "should parse children elements form Fragment string"() {
        expect:
        LinUtils.parseChildrenFromFragment(FRAGMENT) == EXPECTED as String[]

        where:
        FRAGMENT                | EXPECTED
        "workflow"              | ["workflow"]
        "workflow.repository"   | ["workflow", "repository"]
        null                    | []
        ""                      | []
    }

    def "should parse a query string as Map"() {
        expect:
        LinUtils.parseQuery(QUERY_STRING) == EXPECTED

        where:
        QUERY_STRING                | EXPECTED
        "type=value1&taskRun=value2"   | ["type": "value1", "taskRun": "value2"]
        "type=val with space"        | ["type": "val with space"]
        ""                          | [:]
        null                        | [:]
    }

    def "should check params in an object"() {
        given:
        def obj = [ "type": "value", "workflow": ["repository": "subvalue"], "output" : [ ["path":"/to/file"],["path":"file2"] ] ]

        expect:
        LinUtils.checkParams(obj, PARAMS) == EXPECTED

        where:
        PARAMS                                  | EXPECTED
        ["type": "value"]                       | true
        ["type": "wrong"]                       | false
        ["workflow.repository": "subvalue"]     | true
        ["workflow.repository": "wrong"]        | false
        ["output.path": "wrong"]                | false
        ["output.path": "/to/file"]             | true
        ["output.path": "file2"]                | true

    }

    def 'should parse query' (){
        expect:
        LinUtils.parseQuery(PARAMS) == EXPECTED
        where:
        PARAMS                              | EXPECTED
        "type=value"                        | ["type": "value"]
        "workflow.repository=subvalue"      | ["workflow.repository": "subvalue"]
        ""                                  | [:]
        null                                | [:]
    }

    def "should navigate in object params"() {
        given:
        def obj = [
            "key1": "value1",
            "nested": [
                "subkey": "subvalue"
            ]
        ]

        expect:
        LinUtils.navigate(obj, PATH) == EXPECTED

        where:
        PATH             | EXPECTED
        "key1"           | "value1"
        "nested.subkey"  | "subvalue"
        "wrongKey"       | null
    }

    def "should add objects matching parameters"() {
        given:
        def results = []

        when:
        LinUtils.treatObject(OBJECT, PARAMS, results)

        then:
        results == EXPECTED

        where:
        OBJECT                                                                  | PARAMS                            | EXPECTED
        ["field": "value"]                                                      | ["field": "value"]                | [["field": "value"]]
        ["field": "wrong"]                                                      | ["field": "value"]                | []
        [["field": "value"], ["field": "x"]]                                    | ["field": "value"]                | [["field": "value"]]
        "string"                                                                | [:]                               | ["string"]
        ["nested": ["subfield": "match"]]                                       | ["nested.subfield": "match"]      | [["nested": ["subfield": "match"]]]
        ["nested": ["subfield": "nomatch"]]                                     | ["nested.subfield": "match"]      | []
        [["nested": ["subfield": "match"]], ["nested": ["subfield": "other"]]]  | ["nested.subfield": "match"]      | [["nested": ["subfield": "match"]]]
    }

    def "Should search path"() {
        given:
        def uniqueId = UUID.randomUUID()
        def mainScript = new DataPath("file://path/to/main.nf", new Checksum("78910", "nextflow", "standard"))
        def workflow = new Workflow([mainScript], "https://nextflow.io/nf-test/", "123456")
        def key = "testKey"
        def value1 = new WorkflowRun(workflow, uniqueId.toString(), "test_run", [new Parameter("String", "param1", "value1"), new Parameter("String", "param2", "value2")])
        def lidStore = new DefaultLinStore()
        lidStore.open(config)
        lidStore.save(key, value1)
        when:
        def result = LinUtils.searchPath(lidStore, key, ["name":"param1"], ["params"] as String[])

        then:
        result == [new Parameter("String", "param1", "value1")]
    }

    def 'should navigate' (){
        def uniqueId = UUID.randomUUID()
        def mainScript = new DataPath("file://path/to/main.nf", new Checksum("78910", "nextflow", "standard"))
        def workflow = new Workflow([mainScript], "https://nextflow.io/nf-test/", "123456")
        def wfRun = new WorkflowRun(workflow, uniqueId.toString(), "test_run", [new Parameter("String", "param1", [key: "value1"]), new Parameter("String", "param2", "value2")])

        expect:
            LinUtils.navigate(wfRun, "workflow.commitId") == "123456"
            LinUtils.navigate(wfRun, "params.name") == ["param1", "param2"]
            LinUtils.navigate(wfRun, "params.value.key") == "value1"
            LinUtils.navigate(wfRun, "params.value.no-exist") == null
            LinUtils.navigate(wfRun, "params.no-exist") == null
            LinUtils.navigate(wfRun, "no-exist") == null
            LinUtils.navigate(null, "something") == null
    }

}
