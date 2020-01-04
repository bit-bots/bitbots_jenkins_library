def call(String p) {
    lock("shared_catkin_install_space") {
        sh "cd /catkin_ws; catkin clean -b -y"
        catkinBuild(p, "", "install")
        sh "cd /catkin_ws; catkin clean -b -y"
    }
}
