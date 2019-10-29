import groovy.transform.Field

@Field
static argDesc = [
    name: 'sonar',
    description: 'Perform SonarQube scanning or quality gating.',
    args: [
        buildType: [
            description: 'Type of build being performed.  Can be either "MAVEN" or "JENKINS".  Defaults to "JENKINS".',
            default: 'JENKINS',
            validate: { it.toUpperCase() },
        ],
        runQualityGate: [
            description: 'Run the quality gate.  Exactly one of "runQualityGate" or "runScan" may be true.  If both are true, "runQualityGate" takes precedence.',
            default: false,
            validate: { it.toBoolean() },
        ],
        runScan: [
            description: 'Run the simple runScan.  Exactly one of "runQualityGate" or "runScan" may be true.  If both are true, "runQualityGate" takes precedence.',
            default: false,
            validate: { it.toBoolean() },
        ],
        analysisParameters: [
            description: 'Additional parameters for the Sonar analysis.',
            default: null,
        ],
        maven: [
            description: 'The version of Maven to use.  Note that the value given MUST be configured into Jenkins by EDP.  Defaults to "apache-maven-3.3.9".',
            default: 'apache-maven-3.3.9',
        ],
        pomFileLocation: [
            description: 'The location of the pom.xml file.  Defaults to "pom.xml".',
            default: 'pom.xml',
        ],
        appVersion: [
            description: 'The application version.',
            default: null,
        ],
        projectKey: [
            description: 'The project key for Sonar.  Defaults to base Jenkins job name.',
            default: null,
        ],
        projectName: [
            description: 'The Sonar project name.  Defaults to base Jenkins job name.',
            default: null,
        ],
        artifactBuildNumber: [
            description: 'The artifact build number.  Required.',
        ],
        containerBuild:[
                description: 'Set containerBuild to true if the build process is container based. Default to false.',
                default: false,
                validate: { it.toBoolean() },

        ]
    ],
]

def call(body) {
    library 'pipeline-common'
    def config = demoCommon.parseArgs(argDesc, body)
    
    if (!config.projectKey) {
        config.projectKey = getProjectName()
    }

    if (!config.projectName) {
        config.projectName = getProjectName()
    }

    def echoData = "Sonar project name ${config.projectName}"
    echoData += '\n'
    echoData += "Sonar project key ${config.projectKey}"
    echo echoData

    if (config.buildType.equalsIgnoreCase('MAVEN') && !fileExists(config.pomFileLocation)) {
        echo 'error Sonar cannot find pom.xml; scan aborted.  Use "pomFileLocation" to pass its location as a parameter.'
        return
    }

    if (config.runQualityGate) {
        qualityGate()
    } else if (config.runScan) {
        runScan(config.analysisParameters, config.buildType, config.maven, config.pomFileLocation, config.appVersion, config.artifactBuildNumber, config.projectKey, config.projectName, config.containerBuild)
        sendMetrics(config) //report sonar scan run metric
    } else {
        echo 'error Call to global sonar function not configured correctly.  Either "runQualityGate" or "runScan" must be true.'
        return
    }
}

def runScan(def analysisParameters, def buildType, def maven, def pomFileLocation, def appVersion, def artifactBuildNumber, def projectKey, def sonarProjectName,def containerBuild) {
        def sonarCommand
        def sonarParams
        switch (buildType) {
            case 'MAVEN':
                sonarParams = "-Dsonar.projectKey=${projectKey} -Dsonar.projectName=${sonarProjectName} -Dsonar.projectVersion=${artifactBuildNumber} -Dsonar.branch=${env.BRANCH_NAME}"
                if (analysisParameters) {
                    sonarParams += ' '
                    sonarParams += analysisParameters
                }
                if (!containerBuild) {
                    def mavenHome = tool name: "${maven}", type: 'maven'
                    sonarCommand = "${mavenHome}/mvn  -f ${pomFileLocation} -s \$MAVEN_SETTINGS org.sonarsource.scanner.maven:sonar-maven-plugin:sonar ${sonarParams}"
                }
                else{
                    sonarCommand = "mvn -f ${pomFileLocation} -s \$MAVEN_SETTINGS org.sonarsource.scanner.maven:sonar-maven-plugin:sonar ${sonarParams}"
                }
                break
             default:
                sonarParams = "-Dsonar.projectKey=${projectKey} -Dsonar.projectName=${sonarProjectName} -Dsonar.projectVersion=${artifactBuildNumber} -Dsonar.sources=. -Dsonar.branch=${env.BRANCH_NAME}"
                if (analysisParameters) {
                    sonarParams += ' '
                    sonarParams += analysisParameters
                }
                if (!containerBuild) {
                    def scannerHome = tool 'SonarQube Scanner 2.8'
                    sonarCommand = "${scannerHome}/bin/sonar-scanner ${sonarParams}"
                }
                else{
                    sonarCommand = "${SONAR_SCANNER_HOME}/bin/sonar-scanner ${sonarParams}"
                }
                break
        }
        withEnv(["APP_VERSION=${appVersion}", "BUILD_NUMBER=${artifactBuildNumber}"]) {
                withSonarQubeEnv('edp-sonarqube') {
                    sh "${sonarCommand}"
                }
        }
    }

def qualityGate(){
    if (env.NODE_NAME) {
        error 'runQualityGate should not be inside node block, Please correct it.'
    }
  timeout(time: 1, unit: 'HOURS') {
   def qg = waitForQualityGate()
   echo "guality gate status ${qg.status}"
   if (qg.status != 'OK') {
     currentBuild.result = 'ABORTED'
     error "Pipeline aborted due to quality gate failure: ${qg.status}"
   }
  }
}

def getProjectName(){
  def projectName = env.JOB_NAME
  if(projectName.indexOf('/') > 0){
      projectName = projectName.substring(projectName.indexOf('/')+1)
      projectName = projectName.substring(0, projectName.indexOf('/'))
  }

  //If AKMID is found, add it to the project name
  if (env.AKMID){
    projectName += "-akmid:${env.AKMID}"
  }
    projectName
}
