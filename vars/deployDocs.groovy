def call(String pkg, String version = "latest") {
    dir (pkg) {
        sh "mkdir -p /srv/data/doku.bit-bots.de/package/" + pkg + "/" + version + "/";
        sh "rsync --delete -v -r ./docs/_out/ /srv/data/doku.bit-bots.de/package/" + pkg + "/" + version + "/";
    }
}
