def call(String p) {
    lock("shared_catkin_install_space") {
        sh "cp -r /catkin_ws/install/${p}/lib /srv/catkin_install"
        sh "cp -r /catkin_ws/install/${p}/lib /srv/catkin_install"
    }
}
