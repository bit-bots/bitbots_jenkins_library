def call(String p) {
    sh "rosdep install -y -i /catkin_ws/src -i /srv/catkin_install --from-paths /catkin_ws/src/${p}"
}
