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

package nextflow.lineage.serde

import groovy.transform.CompileStatic
import nextflow.lineage.model.FileOutput
import nextflow.lineage.model.TaskOutput
import nextflow.lineage.model.TaskRun
import nextflow.lineage.model.Workflow
import nextflow.lineage.model.WorkflowOutput
import nextflow.lineage.model.WorkflowRun
import nextflow.serde.gson.GsonEncoder
import nextflow.serde.gson.RuntimeTypeAdapterFactory

/**
 * Implements a JSON encoder for lineage model objects
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class LinEncoder extends GsonEncoder<LinSerializable> {

    LinEncoder() {
        withTypeAdapterFactory(newLidTypeAdapterFactory())
        // enable rendering of null values
        withSerializeNulls(true)
    }

    static RuntimeTypeAdapterFactory newLidTypeAdapterFactory(){
        RuntimeTypeAdapterFactory.of(LinSerializable.class, "type")
            .registerSubtype(WorkflowRun, WorkflowRun.simpleName)
            .registerSubtype(WorkflowOutput, WorkflowOutput.simpleName)
            .registerSubtype(Workflow, Workflow.simpleName)
            .registerSubtype(TaskRun, TaskRun.simpleName)
            .registerSubtype(TaskOutput, TaskOutput.simpleName)
            .registerSubtype(FileOutput, FileOutput.simpleName)
    }

}
