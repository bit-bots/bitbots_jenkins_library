package de.bitbots.jenkins

class PackageDefinition implements Serializable {
    String name
    String relativePath

    PackageDefinition(String name, String relativePath = null) {
        if (relativePath == null) {
            relativePath = name
        } else if (relativePath.endsWith("/")) {
            relativePath = relativePath.substring(0, relativePath.length() - 1)
        }

        this.name = name
        this.relativePath = relativePath
    }

    PackagePipelineSettings forPipeline() {
        return new PackagePipelineSettings(this)
    }
}
