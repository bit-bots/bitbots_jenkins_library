package de.bitbots.jenkins

class PackageDefinition {
    public String name
    public String relativePath
    public boolean document

    def PackageDefinition(String name, boolean document, String relativePath = null) {
        if (relativePath == null) {
            relativePath = name
        }

        this.name = name
        this.document = document
        this.relativePath = relativePath
    }
}
