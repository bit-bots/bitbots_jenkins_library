package de.bitbots.jenkins

class PackagePipelineSettings implements Serializable {
    final PackageDefinition pkg
    boolean doBuild
    boolean doDocument
    boolean doPublish

    PackagePipelineSettings(PackageDefinition pkg, boolean doBuild = true, boolean doDocument = true, boolean doPublish = true) {
        this.pkg = pkg
        this.doBuild = doBuild
        this.doDocument = doDocument
        this.doPublish = doPublish
    }

    PackagePipelineSettings withBuild() {
        this.doBuild = true
        return this
    }

    PackagePipelineSettings withoutBuild() {
        this.doBuild = false
        return this
    }

    PackagePipelineSettings withDocumentation() {
        this.doDocument = true
        return this
    }

    PackagePipelineSettings withoutDocumentation() {
        this.doDocument = false
        return this
    }

    PackagePipelineSettings withPublishing() {
        this.doPublish = true
        return this
    }

    PackagePipelineSettings withoutPublishing() {
        this.doPublish = false
        return this
    }
}
