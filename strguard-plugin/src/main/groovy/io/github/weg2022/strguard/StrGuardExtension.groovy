package io.github.weg2022.strguard

import groovy.transform.CompileStatic
import io.github.weg2022.strguard.api.HardCodeKeyGenerator
import io.github.weg2022.strguard.api.IkeyGenerator

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
