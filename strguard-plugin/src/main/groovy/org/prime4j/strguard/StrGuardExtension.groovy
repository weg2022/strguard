package org.prime4j.strguard

import groovy.transform.CompileStatic
import org.prime4j.strguard.api.HardCodeKeyGenerator
import org.prime4j.strguard.api.IkeyGenerator

@CompileStatic
class StrGuardExtension {

    IkeyGenerator keyGenerator = new HardCodeKeyGenerator()

    boolean enabled = true

    boolean debug = false

    boolean log=false

    boolean keepMetadata = true

    String[] guardPackages = []

    String[] notGuardPackages = []
}
