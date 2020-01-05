def call(String pkg, String version = "latest", String localPath = null) {
    if (localPath == null)
        localPath = pkg
    
    dir(localPath) {
        sh "mkdir -p /srv/data/doku.bit-bots.de/package/${pkg}/${version}/"
        sh "rsync --delete -v -r ./docs/_out/ /srv/data/doku.bit-bots.de/package/${pkg}/${version}/"
    }
}
