/*
 * Copyright 2024-2025, Seqera Labs
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

package nextflow.config.control

import java.nio.file.Path

import org.codehaus.groovy.syntax.SyntaxException
import spock.lang.Shared
import spock.lang.Specification
import test.TestUtils

import static test.TestUtils.deleteDir
import static test.TestUtils.tempDir
import static test.TestUtils.tempFile

/**
 * @see nextflow.config.control.ConfigResolveVisitor
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
class ConfigResolveTest extends Specification {

    @Shared
    ConfigParser parser

    def setupSpec() {
        parser = new ConfigParser()
    }

    List<SyntaxException> check(String contents) {
        return TestUtils.check(parser, contents)
    }

    List<SyntaxException> check(List<Path> files) {
        return TestUtils.check(parser, files)
    }

    def 'should report an error for an undefined variable' () {
        when:
        def errors = check(
            '''\
            process.clusterOptions = "--cpus ${process.cpus}"
            '''
        )
        then:
        errors.size() == 1
        errors[0].getStartLine() == 1
        errors[0].getStartColumn() == 36
        errors[0].getOriginalMessage() == '`process` is not defined'
    }

    def 'should report an error for an invalid dynamic config option' () {
        when:
        def errors = check(
            '''\
            report.file = { "report.html" }
            '''
        )
        then:
        errors.size() == 1
        errors[0].getStartLine() == 1
        errors[0].getStartColumn() == 1
        errors[0].getOriginalMessage() == 'Dynamic config options are only allowed in the `process` scope'

        when:
        errors = check(
            '''\
            process.clusterOptions = { "--cpus ${task.cpus}" }
            '''
        )
        then:
        errors.size() == 0
    }

    def 'should report an error for an invalid config include' () {
        given:
        def root = tempDir()
        def config = tempFile(root, 'nextflow.config')
        def extra = tempFile(root, 'extra.config', '')

        when:
        config.text = '''\
            profiles {
                includeConfig 'extra.config'
            }
            '''
        def errors = check([config, extra])
        then:
        errors.size() == 1
        errors[0].getStartLine() == 2
        errors[0].getStartColumn() == 17
        errors[0].getOriginalMessage() == 'Config includes are only allowed at the top-level or in a profile'

        when:
        config.text = '''\
            profiles {
                extra {
                    includeConfig 'extra.config'
                }
            }
            '''
        errors = check([config, extra])
        then:
        errors.size() == 0

        cleanup:
        deleteDir(root)
    }

}
