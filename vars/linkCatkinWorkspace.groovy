def call(String name, String path) {
    sh "ln -s ${WORKSPACE}/${path} /catkin_ws/src/${name}"
}
