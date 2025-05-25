package org.prime4j.strguard

import groovy.transform.CompileStatic
import org.prime4j.strguard.api.HardCodeKeyGenerator
import org.prime4j.strguard.api.IkeyGenerator

@CompileStatic
class StrGuardExtension {

    IkeyGenerator keyGenerator = new HardCodeKeyGenerator()

    boolean stringGuard = true

    boolean v9StringConcatEnabled=true

    boolean generateMappings = false

    boolean consoleOutput =false

    boolean removeMetadata = false


    String[] stringGuardPackages = []

    String[] keepStringPackages = []

    String[] keepMetadataPackages = []

    String[] removeMetadataPackages = []
}
