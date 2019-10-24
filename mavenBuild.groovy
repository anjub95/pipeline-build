import groovy.transform.Field
import java.util.concurrent.TimeUnit

@Field
static argDesc = [
    name: 'mavenBuild',
    description: 'Build a project using Maven.',
    args: [
            mavenGoals: [
                    description: 'Goals to use for the Maven build.  Defaults to "clean install".',
                    default: 'clean install',
            ],
            maven: [
                    description: 'Specify maven tool name.  Ensure tool key exists in allowed-tools.json file.  Defaults to "apache-maven-3.3.9".',
                    default: 'apache-maven-3.3.9',
            ],
            java: [
                    description: 'Specify java tool name.  Ensure tool key exists in allowed-tools.json file.',
                    default: 'java-1.8.131',
            ],
            pomFileLocation: [
                    description: 'Specify the location of the pom.xml file.  Defaults to "pom.xml".',
                    default: 'pom.xml',
            ],
            appVersion: [
                    description: 'Specify the application version.  Defaults to the version read from the specified pom.xml file.  Must be provided if no version is specified in pom.xml.',
                    default: null,
            ],
            artifactBuildNumber: [
                    description: 'Specify the artifact build number.  Defaults to the Jenkins job build number.',
                    default: null,
            ],
            buildCommands: [
                    description: 'A list of commands to execute in lieu of the "mvn" command.  If not provided, "mvn" will be called with suitable arguments.',
                    default: [],
            ],
            containerBuild:[
                    description: 'Set containerBuild to true if the build process is container based. Default to false.',
                    default: false,
                    validate: { it.toBoolean() },
            ],
            downloadTo: [
                    description: 'Specify the target path in workspace to download artifacts.',
                    default: 'tools/',
            ],
            artifactoryServerURL: [
                    description: 'URL for the artifactory server.',
                    default: '',
            ],
            artifactoryCred: [
                    description: 'Credentials to use for downloading tools from artifactory.  A default is provided.',
                    default: null,
            ],
    ],
]

def call(body){
    library 'pipeline-common'
    def config = demoCommon.parseArgs(argDesc, body)

    // Check if the pom file exists
    if (!fileExists(config.pomFileLocation)) {
        currentBuild.result = 'ABORTED'
        error 'Maven build cannot find pom.xml.  Use pomFileLocation to specify its location.'
    }

    // Set the app version if necessary
    if (!config.appVersion) {
        def pomMap = readMavenPom file: config.pomFileLocation
        config.appVersion = pomMap.version
    }
    if (!config.appVersion) {
        currentBuild.result = 'ABORTED'
        error 'Maven build cannot determine app version.  Use appVersion to specify its value.'
    }

    // Set the artifactBuildNumber
    if (!config.artifactBuildNumber) {
        config.artifactBuildNumber = common.getJenkinsBuildNumber()
    }

    //report metrics build Successful false because build just started
/*    def edpBuildStartTime = System.currentTimeMillis()
    def sendMetricsArgs = [
      buildSuccessful: false,
      edpBuildStartTime: edpBuildStartTime,
    ]
    //sendMetrics(config,sendMetricsArgs)*/

    if(!config.containerBuild){
        performNonContainerBuild(config)
    }
    else{
        performContainerBuild(config)
    }

    //report metrics build successful true
    //def edpBuildEndTime = System.currentTimeMillis()
    /*sendMetricsArgs = [
      buildSuccessful: true,
      edpBuildStartTime: edpBuildStartTime,
      edpBuildEndTime: edpBuildEndTime,
    ]
    sendMetrics(config,sendMetricsArgs)*/

    def pomMap = readMavenPom file: config.pomFileLocation
    config.artifactId = pomMap.artifactId
    copyPomFile(config, config.artifactId)
}

def createFile(def scripts,def fileName){
    String fileCommands
    def fileCreated = false

    for(def int x=0; x<scripts.size(); x++){
        if(fileCommands){
            fileCommands += '\n'
            fileCommands += "${scripts[x]}"
        }else{
            fileCommands = "${scripts[x]}"
        }
    }
    if (fileCommands){
        writeFile file: fileName, text: fileCommands
        fileCreated = true
    }
    if (fileCreated){
        sh "chmod +x ${pwd()}/${fileName}"
    }
    fileCreated
}

def performNonContainerBuild(config){
    //Installing tools
    def javaHome = demoTool.downloadInstallTool(config, config.java)  // tool where is it downloaded from?
    def mavenHome = demoTool.downloadInstallTool(config, config.maven) //same as above
    // Initialize the buildCommands
    if (!config.buildCommands) {
        config.buildCommands.add('export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"')
        config.buildCommands.add("\$MVN_HOME/mvn -f ${config.pomFileLocation} -s \$MAVEN_SETTINGS ${config.mavenGoals}")
    }

    createFile(config.buildCommands,'build.sh')
    echo "Build Artifact Version ${config.appVersion}.${config.artifactBuildNumber}"
    withEnv(["JAVA_HOME=${javaHome}", "MVN_HOME=${mavenHome}/bin","APP_VERSION=${config.appVersion}","BUILD_NUMBER=${config.artifactBuildNumber}"]) {
        configFileProvider([configFile(fileId: 'snapshot-repository-settings-xml', variable: 'MAVEN_SETTINGS')]) { //change fileID
            sh 'bash build.sh'
        }
    }
}

def performContainerBuild(config) {
    // Initialize the buildCommands
    if (!config.buildCommands) {
        config.buildCommands.add("mvn -f ${config.pomFileLocation} -s \$MAVEN_SETTINGS ${config.mavenGoals}")
    }
    createFile(config.buildCommands,'build.sh')
    withEnv(["APP_VERSION=${config.appVersion}", "BUILD_NUMBER=${config.artifactBuildNumber}"]) {
        configFileProvider([configFile(fileId: 'snapshot-repository-settings-xml', variable: 'MAVEN_SETTINGS')]) { //change fileID?
            sh 'bash build.sh'
        }
    }
}

/**
 * send metrics to env.INFLUX_DB
 * Each function that reports metrics needs ability to add its own tags and fields to data sent to Influx.
 * Thus buildTags and buildFields are passed as closures to tmoMetrics.reportMetrics()
*/

//Reference to pipeline-common tmoMetrics.groovy 
/*def sendMetrics(config,sendMetricsArgs){
  try{
    def buildTags = {->
      def buildType = 'maven'
      def buildRun = true
      def tags = tmoMetrics.metricsAddCommonTags()
      tags += ",build_run=${buildRun}"
      tags += ",build_type=${buildType}"
      return tags
    }

    def buildFields = {->
      def fields = tmoMetrics.metricsAddCommonFields()
      fields += ",build_success=\\\"${sendMetricsArgs.buildSuccessful}\\\""
      if(sendMetricsArgs.edpBuildStartTime && sendMetricsArgs.edpBuildEndTime){
        fields += ",build_duration_seconds=\\\"${buildDurationSeconds(sendMetricsArgs)}\\\""
      }
      return fields
    }

    tmoMetrics.metricsGatherCommonMetrics() //must call before accesing env.INFLUX_BUILD_METRICS_MEASUREMENT becuse this method sets that value

    def reportMetricsArgs = [
      influx_mesurement: env.INFLUX_BUILD_METRICS_MEASUREMENT,
    ]

    tmoMetrics.reportMetrics(config,buildTags,buildFields,reportMetricsArgs)
  }catch(err){
    echo "error sending pipeline metrics ${err.toString()}"
  }
}*/

/**
 * edpBuildEndTime in milliseconds
 * edpBuildStartTime in milliseconds
 * buildDurationMili in milliseconds
 * convert to seconds
*/
/*def buildDurationSeconds(sendMetricsArgs){
  def buildDurationMili = sendMetricsArgs.edpBuildEndTime - sendMetricsArgs.edpBuildStartTime
  def durationSecounds = TimeUnit.SECONDS.convert(buildDurationMili, TimeUnit.MILLISECONDS) //INFLUX TIMESTAMP MUST BE EPOCH NANO SECONDS TIME
  return durationSecounds
}*/

/**
 * copyPomFile copies pom file to edp-pom directory.
 *
 * @param config Map object with config parameter.
 * @param artifactId The artifactId reading from pom file.
 */
def copyPomFile(config, artifactId) {
    def pomName = "${artifactId}-" + "${config.appVersion}.${config.artifactBuildNumber}.pom"
            sh 'mkdir demo-artifacts'
            sh "cp ${config.pomFileLocation} demo-artifacts/${pomName}"
 }