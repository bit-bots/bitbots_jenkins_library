def call(String p) {
    lock("shared_catkin_install_space") {
        catkinBuild(p, "", "install")
    }
}
