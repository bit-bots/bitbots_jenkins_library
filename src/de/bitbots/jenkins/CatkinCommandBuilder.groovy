package de.bitbots.jenkins;


class CatkinCommandBuilder implements Serializable {

    static String linkWorkspace(String workspace) {
        return String.format("ln -s %s /catkin_ws/src", workspace);
    }

    static String build(String makeTarget) {
        if (makeTarget == "")
            return "cd /catkin_ws; catkin build --no-status --summarize";
        else {
            return String.format("cd /catkin_ws; catkin build --no-status --summarize --make-args %s", makeTarget);
        }
    }

}
