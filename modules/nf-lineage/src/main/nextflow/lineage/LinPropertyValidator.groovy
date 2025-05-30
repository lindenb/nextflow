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

import groovy.transform.CompileStatic
import nextflow.lineage.model.Annotation
import nextflow.lineage.model.Checksum
import nextflow.lineage.model.DataPath
import nextflow.lineage.model.Parameter
import nextflow.lineage.model.TaskOutput
import nextflow.lineage.model.TaskRun
import nextflow.lineage.model.Workflow
import nextflow.lineage.model.WorkflowOutput
import nextflow.lineage.model.WorkflowRun

/**
 * Class to validate if the string refers to a property in the classes of the Lineage Metadata model.
 *
 * @author Jorge Ejarque <jorge.ejarque@seqera.io>
 */
@CompileStatic
class LinPropertyValidator {

    private static final List<Class> LIN_MODEL_CLASSES = [
        Annotation,
        Checksum,
        DataOutput,
        DataPath,
        Parameter,
        TaskOutput,
        TaskRun,
        Workflow,
        WorkflowOutput,
        WorkflowRun,
    ]

    private Set<String> validProperties

    LinPropertyValidator() {
        this.validProperties = new HashSet<String>()
        for( Class clazz : LIN_MODEL_CLASSES ) {
            for( MetaProperty field : clazz.metaClass.getProperties() ) {
                validProperties.add( field.name)
            }
        }
    }

    void validate(Collection<String> properties) {
        for( String property : properties ) {
            if( property !in this.validProperties ) {
                def msg = "Property '$property' doesn't exist in the lineage model."
                final matches = this.validProperties.closest(property)
                if( matches )
                    msg += " -- Did you mean one of these?" + matches.collect { "  $it"}.join(', ')
                throw new IllegalArgumentException(msg)
            }
        }
    }

    void validateQueryParams(Map<String, String> params) {
        for( String key : params.keySet() ) {
           validate(key.tokenize('.'))
        }
    }

}
