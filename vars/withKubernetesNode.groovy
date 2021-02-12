def call(Closure body) {
    podTemplate(yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: builder
      image: registry.bit-bots.de/bitbots_builder
      tty: true
      command:
      - cat
""") {
        node(POD_LABEL) {
            body()
        }
    }
}