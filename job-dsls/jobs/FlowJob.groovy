// definition of parameters (will change with each branch)

def javadk="jdk1.8"
def jaydekay="JDK1_8"
def mvnToolEnv="APACHE_MAVEN_3_3_9"
def mvnVersion="apache-maven-3.3.9"
def mvnHome="${mvnToolEnv}_HOME"
def mvnOpts="-Xms1g -Xmx3g"
def kieMainBranch="master"
def erraiBranch="master"
def erraiVersionOld="4.1.3-SNAPSHOT"
def organization="kiegroup"

// definition of flow script

def flowJob ='''def erraiVersionOld = params["erraiVersionOld"]
def kieMainBranch =params["kieMainBranch"]
def erraiBranch=params["erraiBranch"]
def organization=params["organization"]

erraiVersionNew = build.environment.get("erraiVersionNew")
appformerVersion = build.environment.get("appformerVersion")
kieVersion = build.environment.get("kieVersion")

ignore(UNSTABLE) {
    build("errai-kieAllBuild-${kieMainBranch}", erraiVersionNew: "$erraiVersionNew", erraiVersionOld: "$erraiVersionOld", erraiBranch: "$erraiBranch")
}
ignore(UNSTABLE) {
    build("kieAllBuild-${kieMainBranch}", kieVersion: "$kieVersion", appformerVersion: "$appformerVersion", erraiVersionNew: "$erraiVersionNew", kieMainBranch: "$kieMainBranch")
}


parallel (
        {
            build("jbpmTestCoverageMatrix-kieAllBuild-${kieMainBranch}", kieVersion: "$kieVersion")
        },
        {
            build("jbpmTestContainerMatrix-kieAllBuild-${kieMainBranch}", kieVersion: "$kieVersion")
        },
        {
            build("kieWbTestsMatrix-kieAllBuild-${kieMainBranch}", kieVersion: "$kieVersion")
        },
        {
            build("kieServerMatrix-kieAllBuild-${kieMainBranch}", kieVersion: "$kieVersion")
        },
                {
            build("kie-docker-ci-images-${kieMainBranch}", kieVersion: "$kieVersion")
        }
)'''

// +++++++++++++++++++++++++++++++++++++++ Trigger job  ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// flowJob (the build flow text can also be read from a file): triggers all other jobs

buildFlowJob("trigger-kieAllBuild-${kieMainBranch}") {
    description("Flow that describes and runs the KIE build pipeline for ${kieMainBranch} branch.<br> IMPORTANT: we don't know the reason but when executet the very first time please go to the <br> configuration and press SAVE - so the dynamic Reference Parameter works")

    parameters {
        stringParam("erraiVersionOld", "${erraiVersionOld}", "edit old errai -SNAPSHOT version")
        stringParam("erraiBranch", "${erraiBranch}", "edit errai branch")
        stringParam("kieMainBranch", "${kieMainBranch}", "edit kie branch")
        stringParam("organization","${organization}","edit organization")
    }

    environmentVariables{
        groovy('''def date = new Date().format( 'yyyyMMdd-hhMMss' )
    def kieVersionPre = "7.6.0."
    def appformerVersionPre = "2.1.0."
    def erraiVersionNewPre = "4.1.3."
    def sourceProductTag = ""
    def targetProductBuild = ""

    return [kieVersion: kieVersionPre + date, appformerVersion: appformerVersionPre + date, erraiVersionNew:erraiVersionNewPre +date, \
 cutOffDate: date, reportDate: date,  sourceProductTag: sourceProductTag, targetProductBuild: targetProductBuild] \
 ''')

    }

    buildFlow("${flowJob}")

    buildNeedsWorkspace(needsWorkspace = true)

    label("master")

    logRotator {
        numToKeep(10)
    }

    jdk("${javadk}")

    triggers {
        cron("H 20 * * *")
    }

    publishers {
        buildDescription ("KIE version ([^\\s]*)")
    }

}


// ++++++++++++++++++++++++++++++++++++++++++ Build and deploys errai ++++++++++++++++++++++++++++++++++++++++++++++++++

// definition of errai script
def erraiVersionBuild='''#!/bin/bash -e
# removing UF and errai artifacts from local maven repo (basically all possible SNAPSHOTs)
if [ -d $MAVEN_REPO_LOCAL ]; then
rm -rf $MAVEN_REPO_LOCAL/org/jboss/errai/
       fi
# clone the Errai repository
git clone https://github.com/errai/errai.git -b $erraiBranch --depth 100
# checkout the release branch
cd errai
git checkout -b $erraiVersionNew $erraiBranch
# update versions
sh updateVersions.sh $erraiVersionOld $erraiVersionNew
# build the repos & deploy into local dir (will be later copied into staging repo)
deployDir=$WORKSPACE/deploy-dir
# (1) do a full build, but deploy only into local dir
# we will deploy into remote staging repo only once the whole build passed (to save time and bandwith)
mvn -U -B -e clean deploy -T2 -Dfull -Drelease -DaltDeploymentRepository=local::default::file://$deployDir -s $SETTINGS_XML_FILE\\
 -Dmaven.test.failure.ignore=true -Dgwt.compiler.localWorkers=3
# (2) upload the content to remote staging repo
cd $deployDir
mvn -B -e org.sonatype.plugins:nexus-staging-maven-plugin:1.6.5:deploy-staged-repository -DnexusUrl=https://repository.jboss.org/nexus -DserverId=jboss-releases-repository -DrepositoryDirectory=$deployDir\\
 -s $SETTINGS_XML_FILE -DstagingProfileId=15c3321d12936e -DstagingDescription="errai $erraiVersionNew" -DstagingProgressTimeoutMinutes=30'''


job("errai-kieAllBuild-${kieMainBranch}") {
    description("Upgrades and builds the errai version")
    parameters{
        stringParam("erraiVersionNew", "errai version", "Version of Errai. This will be usually set automatically by the parent trigger job. ")
        stringParam("erraiVersionOld", "old errai version", "Version of Errai. This will be usually set automatically by the parent trigger job. ")
        stringParam("erraiBranch", "errai branch", "Branch of errai. This will be usually set automatically by the parent trigger job. ")
    }

    label("rhel7&&mem16g")

    logRotator {
        numToKeep(10)
    }

    jdk("${javadk}")

    wrappers {
        timeout {
            absolute(60)
        }
        timestamps()
        colorizeOutput()
        toolenv("${mvnToolEnv}", "${jaydekay}")
        preBuildCleanup()
        configFiles {
            mavenSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1434468480404"){
                variable("SETTINGS_XML_FILE")
            }
        }
    }

    publishers {
        wsCleanup()
        archiveJunit("**/target/*-reports/TEST-*.xml")
        mailer('mbiarnes@redhat.com', false, false)
    }

    configure { project ->
        project / 'buildWrappers' << 'org.jenkinsci.plugins.proccleaner.PreBuildCleanup' {
            cleaner(class: 'org.jenkinsci.plugins.proccleaner.PsCleaner') {
                killerType 'org.jenkinsci.plugins.proccleaner.PsAllKiller'
                killer(class: 'org.jenkinsci.plugins.proccleaner.PsAllKiller')
                username 'jenkins'
            }
        }
    }

    steps {
        environmentVariables {
            envs(MAVEN_OPTS : "${mvnOpts}", MAVEN_HOME : "\$${mvnHome}", MAVEN_REPO_LOCAL : "/home/jenkins/.m2/repository", PATH : "\$${mvnHome}/bin:\$PATH")
        }
        shell(erraiVersionBuild)
    }

}

// +++++++++++++++++++++++++++++++++++++++++++ Build and deploy kie ++++++++++++++++++++++++++++++++++++++++++++++++++++

// definition of kie build  script
def kieVersionBuild='''#!/bin/bash -e
# removing KIE artifacts from local maven repo (basically all possible SNAPSHOTs)
if [ -d $MAVEN_REPO_LOCAL ]; then
    rm -rf $MAVEN_REPO_LOCAL/org/jboss/dashboard-builder/
    rm -rf $MAVEN_REPO_LOCAL/org/kie/
    rm -rf $MAVEN_REPO_LOCAL/org/drools/
    rm -rf $MAVEN_REPO_LOCAL/org/jbpm/
    rm -rf $MAVEN_REPO_LOCAL/org/optaplanner/
    rm -rf $MAVEN_REPO_LOCAL/org/guvnor/
fi
# clone the build-bootstrap that contains the other build scripts
git clone https://github.com/kiegroup/droolsjbpm-build-bootstrap.git --branch $kieMainBranch --depth 100
# clone rest of the repos
./droolsjbpm-build-bootstrap/script/git-clone-others.sh --branch $kieMainBranch --depth 100
# checkout to release branches
./droolsjbpm-build-bootstrap/script/git-all.sh checkout -b $kieVersion $kieMainBranch

# upgrade version kiegroup 
./droolsjbpm-build-bootstrap/script/release/update-version-all.sh $kieVersion $appformerVersion productized
echo "errai version:" $erraiVersionNew
echo "appformer version:" $appformerVersion
echo "kie version" $kieVersion
# change properties via sed as they don't update automatically
# appformer
cd appformer
sed -i "$!N;s/<version.org.kie>.*.<\\/version.org.kie>/<version.org.kie>$kieVersion<\\/version.org.kie>/;P;D" pom.xml
sed -i "$!N;s/<version.org.jboss.errai>.*.<\\/version.org.jboss.errai>/<version.org.jboss.errai>$erraiVersionNew<\\/version.org.jboss.errai>/;P;D" pom.xml
cd ..
#droolsjbpm-build-bootstrap
cd droolsjbpm-build-bootstrap
sed -i "$!N;s/<version.org.kie>.*.<\\/version.org.kie>/<version.org.kie>$kieVersion<\\/version.org.kie>/;P;D" pom.xml
sed -i "$!N;s/<version.org.uberfire>.*.<\\/version.org.uberfire>/<version.org.uberfire>$appformerVersion<\\/version.org.uberfire>/;P;D" pom.xml
sed -i "$!N;s/<version.org.jboss.errai>.*.<\\/version.org.jboss.errai>/<version.org.jboss.errai>$erraiVersionNew<\\/version.org.jboss.errai>/;P;D" pom.xml
sed -i "$!N;s/<latestReleasedVersionFromThisBranch>.*.<\\/latestReleasedVersionFromThisBranch>/<latestReleasedVersionFromThisBranch>$kieVersion<\\/latestReleasedVersionFromThisBranch>/;P;D" pom.xml
cd ..

# build the repos & deploy into local dir (will be later copied into staging repo)
deployDir=$WORKSPACE/deploy-dir

cat > "$WORKSPACE/clean-up.sh" << EOT
baseDir=\\$1
rm -rf \\`find \\$baseDir -type d -wholename "*/target/*wildfly*Final"\\`
rm -rf \\`find \\$baseDir -type d -wholename "*/target/cargo"\\`
rm -rf \\`find \\$baseDir -type f -name "*war"\\`
rm -rf \\`find \\$baseDir -type f -name "*jar"\\`
rm -rf \\`find \\$baseDir -type f -name "*zip"\\`
rm -rf \\`find \\$baseDir -type d -name "gwt-unitCache"\\`
EOT

# (1) do a full build, but deploy only into local dir
# we will deploy into remote staging repo only once the whole build passed (to save time and bandwith)
./droolsjbpm-build-bootstrap/script/mvn-all.sh -B -e clean deploy -T2 -Dfull -Drelease -DaltDeploymentRepository=local::default::file://$deployDir -s $SETTINGS_XML_FILE\\
 -Dkie.maven.settings.custom=$SETTINGS_XML_FILE -Dmaven.test.redirectTestOutputToFile=true -Dmaven.test.failure.ignore=true -Dgwt.compiler.localWorkers=1\\
 -Dgwt.memory.settings="-Xmx4g -Xms1g -Xss1M" --clean-up-script="$WORKSPACE/clean-up.sh"

# (2) upload the content to remote staging repo
mvn -B -e org.sonatype.plugins:nexus-staging-maven-plugin:1.6.5:deploy-staged-repository -DnexusUrl=https://repository.jboss.org/nexus -DserverId=jboss-releases-repository\\
 -DrepositoryDirectory=$deployDir -s $SETTINGS_XML_FILE -DstagingProfileId=15c3321d12936e -DstagingDescription="kie $kieVersion" -DstagingProgressTimeoutMinutes=40
# creates a file (list) of the last commit hash of each repository as handover for production
./droolsjbpm-build-bootstrap/script/git-all.sh log -1 --pretty=oneline >> git-commit-hashes.txt
echo $kieVersion > $WORKSPACE/version.txt
# creates JSON file for prod
# resultant sed extraction files
./droolsjbpm-build-bootstrap/script/git-all.sh log -1 --format=%H  >> sedExtraction_1.txt
sed -e '1d;2d' -e '/Total/d' -e '/====/d' -e 's/Repository: //g' -e 's/^/"/; s/$/"/;' -e '/""/d' sedExtraction_1.txt >> sedExtraction_2.txt
sed -e '0~2 a\\' sedExtraction_2.txt >> sedExtraction_3.txt
sed -e '1~2 s/$/,/g' sedExtraction_3.txt >> sedExtraction_4.txt
sed -e '1~2 s/^/"repo": /' sedExtraction_4.txt >> sedExtraction_5.txt
sed -e '2~2 s/^/"commit": /' sedExtraction_5.txt >> sedExtraction_6.txt
sed -e '0~2 s/$/\\n },{/g' sedExtraction_6.txt >> sedExtraction_7.txt
sed -e '$d' sedExtraction_7.txt >> sedExtraction_8.txt
cat sedExtraction_8.txt
cutOffDate=$(date +"%m-%d-%Y %H:%M")
reportDate=$(date +"%m-%d-%Y")
fileToWrite=$reportDate.json
commitHash=$(cat sedExtraction_8.txt)
cat <<EOF > int.json
{
   "handover" : {
   "cut_off_date" : "$cutOffDate",
   "report_date": "$reportDate",
   "repos" : [
      {
         $commitHash
      }   
    ],
   "source_product_tag":"$sourceProductTag",
   "target_product_build":"$targetProductBuild" 
   }
}
EOF
# indent json
python -m json.tool int.json >> $fileToWrite
# remove sed extraction and int files
rm sedExtraction*
rm int.json
'''

job("kieAllBuild-${kieMainBranch}") {
    description("Upgrades and builds the kie version")
    parameters{
        stringParam("erraiVersionNew", "errai version", "Version of errai. This will be usually set automatically by the parent trigger job. ")
        stringParam("appformerVersion", "appformer version (former uberfire version)", "Version of appformer. This will be usually set automatically by the parent trigger job. ")
        stringParam("kieVersion", "kie version", "Version of kie. This will be usually set automatically by the parent trigger job. ")
        stringParam("kieMainBranch", "appformer branch", "branch of kie. This will be usually set automatically by the parent trigger job. ")
    }

    label("linux&&rhel7&&mem16g")

    logRotator {
        numToKeep(10)
    }

    jdk("${javadk}")

    wrappers {
        timeout {
            absolute(300)
        }
        timestamps()
        colorizeOutput()
        toolenv("${mvnToolEnv}", "${jaydekay}")
        preBuildCleanup()
        configFiles {
            mavenSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1434468480404"){
                variable("SETTINGS_XML_FILE")
            }
        }
    }

    publishers {
        wsCleanup()
        archiveJunit("**/target/*-reports/TEST-*.xml")
        archiveArtifacts{
            onlyIfSuccessful(false)
            allowEmpty(true)
            pattern("**/git-commit-hashes.txt, version.txt, **/hs_err_pid*.log, **/target/*.log, **/*.json")
        }
        mailer('bsig@redhat.com', false, false)
    }

    configure { project ->
        project / 'buildWrappers' << 'org.jenkinsci.plugins.proccleaner.PreBuildCleanup' {
            cleaner(class: 'org.jenkinsci.plugins.proccleaner.PsCleaner') {
                killerType 'org.jenkinsci.plugins.proccleaner.PsAllKiller'
                killer(class: 'org.jenkinsci.plugins.proccleaner.PsAllKiller')
                username 'jenkins'
            }
        }
    }

    steps {
        environmentVariables {
            envs(MAVEN_OPTS : "${mvnOpts}", MAVEN_HOME : "\$${mvnHome}", MAVEN_REPO_LOCAL : "/home/jenkins/.m2/repository", PATH : "\$${mvnHome}/bin:\$PATH")
        }
        shell(kieVersionBuild)
    }
}

// +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// definition of jbpmTestCoverageMatrix test
def jbpmTestCoverage='''#!/bin/bash -e
STAGING_REP=kie-internal-group
echo "KIE version: $kieVersion"
# wget the tar.gz sources
wget -q https://repository.jboss.org/nexus/content/repositories/$STAGING_REP/org/jbpm/jbpm/$kieVersion/jbpm-$kieVersion-project-sources.tar.gz -O sources.tar.gz
tar xzf sources.tar.gz
mv jbpm-$kieVersion/* .
rmdir jbpm-$kieVersion
'''

matrixJob("jbpmTestCoverageMatrix-kieAllBuild-${kieMainBranch}") {
    description("This job: <br> - Test coverage Matrix for jbpm <br> IMPORTANT: Created automatically by Jenkins job DSL plugin. Do not edit manually! The changes will get lost next time the job is generated.")
    parameters {
        stringParam("kieVersion", "kie version", "please edit the version of the KIE release <br> i.e. typically <b> major.minor.micro.<extension> </b>7.1.0.Beta1 for <b> community </b>or <b> major.minor.micro.<yyymmdd>-productized </b>(7.1.0.20170514-productized) for <b> productization </b> <br> Version to test. Will be supplied by the parent job. <br> Normally the KIE_VERSION will be supplied by parent job <br> ******************************************************** <br> ")
    }

    axes {
        labelExpression("label-exp","linux&&mem8g")
        jdk("${javadk}")
    }

    logRotator {
        numToKeep(10)
    }

    wrappers {
        timeout {
            absolute(120)
        }
        timestamps()
        colorizeOutput()
        preBuildCleanup()
    }

    publishers {
        wsCleanup()
        archiveJunit("**/TEST-*.xml")
        mailer('mbiarnes@redhat.com', false, false)
    }

    configure { project ->
        project / 'buildWrappers' << 'org.jenkinsci.plugins.proccleaner.PreBuildCleanup' {
            cleaner(class: 'org.jenkinsci.plugins.proccleaner.PsCleaner') {
                killerType 'org.jenkinsci.plugins.proccleaner.PsAllKiller'
                killer(class: 'org.jenkinsci.plugins.proccleaner.PsAllKiller')
                username 'jenkins'
            }
        }
    }

    steps {
        shell(jbpmTestCoverage)
        maven{
            mavenInstallation("${mvnVersion}")
            goals("clean verify -e -B -Dmaven.test.failure.ignore=true -Dintegration-tests")
            rootPOM("jbpm-test-coverage/pom.xml")
            mavenOpts("-Xmx3g")
            providedSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905")
        }
    }
}

// +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// run additional test: jbpmContainerTestMatrix test
def jbpmContainerTest='''#!/bin/bash -e
echo "KIE version $kieVersion"
# wget the tar.gz sources
wget -q https://repository.jboss.org/nexus/content/repositories/kie-internal-group/org/jbpm/jbpm/$kieVersion/jbpm-$kieVersion-project-sources.tar.gz -O sources.tar.gz
tar xzf sources.tar.gz
mv jbpm-$kieVersion/* .
rmdir jbpm-$kieVersion
'''

matrixJob("jbpmTestContainerMatrix-kieAllBuild-${kieMainBranch}") {
    description("Version to test. Will be supplied by the parent job. Also used to donwload proper sources. <br> IMPORTANT: Created automatically by Jenkins job DSL plugin. Do not edit manually! The changes will get lost next time the job is generated.")
    parameters {
        stringParam("kieVersion", "kie version", "please edit the version of the KIE release <br> i.e. typically <b> major.minor.micro.<extension> </b>7.1.0.Beta1 for <b> community </b>or <b> major.minor.micro.<yyymmdd>-productized </b>(7.1.0.20170514-productized) for <b> productization </b> <br> Version to test. Will be supplied by the parent job. <br> Normally the KIE_VERSION will be supplied by parent job <br> ******************************************************** <br> ")
    }

    axes {
        labelExpression("label-exp","rhel7&&mem8g")
        jdk("${javadk}")
        text("container", "tomcat8", "wildfly10")
    }

    logRotator {
        numToKeep(10)
    }

    childCustomWorkspace("\${SHORT_COMBINATION}")

    wrappers {
        timeout {
            absolute(120)
        }
        timestamps()
        colorizeOutput()
        preBuildCleanup()
        configFiles {
            mavenSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905"){
                variable("SETTINGS_XML_FILE")
            }
        }
    }

    publishers {
        wsCleanup()
        archiveJunit("**/TEST-*.xml")
        mailer('mbiarnes@redhat.com', false, false)
    }

    configure { project ->
        project / 'buildWrappers' << 'org.jenkinsci.plugins.proccleaner.PreBuildCleanup' {
            cleaner(class: 'org.jenkinsci.plugins.proccleaner.PsCleaner') {
                killerType 'org.jenkinsci.plugins.proccleaner.PsAllKiller'
                killer(class: 'org.jenkinsci.plugins.proccleaner.PsAllKiller')
                username 'jenkins'
            }
        }
    }

    steps {
        shell(jbpmContainerTest)
        maven{
            mavenInstallation("${mvnVersion}")
            goals("-e -B clean install")
            rootPOM("jbpm-container-test/pom.xml")
            mavenOpts("-Xmx3g")
            providedSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905")
            properties("maven.test.failure.ignore": true)
            properties("container.profile":"\$container")
            properties("org.apache.maven.user-settings":"\$SETTINGS_XML_FILE")
        }
    }
}

// +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//  run additional test: kieWbTestsMatrix
def kieWbTest='''#!/bin/bash -e
echo "KIE version $kieVersion"
# wget the tar.gz sources
wget -q https://repository.jboss.org/nexus/content/repositories/kie-internal-group/org/kie/kie-wb-distributions/$kieVersion/\\
kie-wb-distributions-$kieVersion-project-sources.tar.gz -O sources.tar.gz
tar xzf sources.tar.gz
mv kie-wb-distributions-$kieVersion/* .
rmdir kie-wb-distributions-$kieVersion'''

matrixJob("kieWbTestsMatrix-kieAllBuild-${kieMainBranch}") {
    description("This job: <br> - Runs the KIE Server integration tests on mutiple supported containers and JDKs <br> IMPORTANT: Created automatically by Jenkins job DSL plugin. Do not edit manually! The changes will get lost next time the job is generated. ")

    parameters {
        stringParam("kieVersion", "kie version", "please edit the version of the KIE release <br> i.e. typically <b> major.minor.micro.<extension> </b>7.1.0.Beta1 for <b> community </b>or <b> major.minor.micro.<yyymmdd>-productized </b>(7.1.0.20170514-productized) for <b> productization </b> <br> Version to test. Will be supplied by the parent job. <br> Normally the KIE_VERSION will be supplied by parent job <br> ******************************************************** <br> ")
    }

    axes {
        jdk("${javadk}")
        text("container", "wildfly10", "eap7", "tomcat8")
        text("war","kie-wb","kie-drools-wb")
        labelExpression("label_exp", "linux&&mem4g&&gui-testing")
        text("browser","firefox")
    }

    childCustomWorkspace("\${SHORT_COMBINATION}")

    logRotator {
        numToKeep(10)
    }

    properties{
        rebuild{
            autoRebuild()
        }
    }

    throttleConcurrentBuilds {
        maxPerNode(1)
        maxTotal(5)
        throttleMatrixConfigurations()
    }

    wrappers {
        timeout {
            absolute(120)
        }
        timestamps()
        colorizeOutput()
        preBuildCleanup()
        configFiles {
            mavenSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905") {
                variable("SETTINGS_XML_FILE")
            }
        }
        xvnc{
            useXauthority()
        }
    }

    configure { project ->
        project / 'buildWrappers' << 'org.jenkinsci.plugins.proccleaner.PreBuildCleanup' {
            cleaner(class: 'org.jenkinsci.plugins.proccleaner.PsCleaner') {
                killerType 'org.jenkinsci.plugins.proccleaner.PsAllKiller'
                killer(class: 'org.jenkinsci.plugins.proccleaner.PsAllKiller')
                username 'jenkins'
            }
        }
    }

    publishers {
        wsCleanup()
        archiveJunit("**/target/failsafe-reports/TEST-*.xml")
        mailer('mbiarnes@redhat.com', false, false)
    }

    steps {
        shell(kieWbTest)
        maven{
            mavenInstallation("${mvnVersion}")
            goals("-nsu -B -e -fae clean verify -P\$container,\$war")
            rootPOM("kie-wb-tests/pom.xml")
            properties("maven.test.failure.ignore": true)
            properties("deployment.timeout.millis":"240000")
            properties("container.startstop.timeout.millis":"240000")
            properties("webdriver.firefox.bin":"/opt/tools/firefox-38esr/firefox-bin")
            properties("eap7.download.url":"http://download.devel.redhat.com/released/JBEAP-7/7.1.0/jboss-eap-7.1.0.zip")
            mavenOpts("-Xms1024m -Xmx1536m")
            providedSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905")
        }
    }
}

// +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//  run additional test: kieServerMatrix
def kieServerTest='''#!/bin/bash -e
echo "KIE version $kieVersion"
# wget the tar.gz sources
wget -q https://repository.jboss.org/nexus/content/repositories/kie-internal-group/org/drools/droolsjbpm-integration/$kieVersion/\\
droolsjbpm-integration-$kieVersion-project-sources.tar.gz -O sources.tar.gz
tar xzf sources.tar.gz
mv droolsjbpm-integration-$kieVersion/* .
rmdir droolsjbpm-integration-$kieVersion'''

matrixJob("kieServerMatrix-kieAllBuild-${kieMainBranch}") {
    description("This job: <br> - Runs the KIE Server integration tests on mutiple supported containers and JDKs <br> IMPORTANT: Created automatically by Jenkins job DSL plugin. Do not edit manually! The changes will get lost next time the job is generated. ")

    // Label which specifies which nodes this job can run on.
    label("master")

    parameters {
        stringParam("kieVersion", "kie version", "please edit the version of the KIE release <br> i.e. typically <b> major.minor.micro.<extension> </b>7.1.0.Beta1 for <b> community </b>or <b> major.minor.micro.<yyymmdd>-productized </b>(7.1.0.20170514-productized) for <b> productization </b> <br> Version to test. Will be supplied by the parent job. <br> Normally the KIE_VERSION will be supplied by parent job <br> ******************************************************** <br> ")
    }

    axes {
        jdk("${jaydekay}")
        text("container", "wildfly10", "eap7", "tomcat8")
        labelExpression("label_exp", "linux&&mem8g")
    }

    childCustomWorkspace("\${SHORT_COMBINATION}")

    logRotator {
        numToKeep(10)
    }

    wrappers {
        timeout {
            absolute(120)
        }
        timestamps()
        colorizeOutput()
        preBuildCleanup()
        configFiles {
            mavenSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905") {
                variable("SETTINGS_XML_FILE")
            }
        }
    }

    configure { project ->
        project / 'buildWrappers' << 'org.jenkinsci.plugins.proccleaner.PreBuildCleanup' {
            cleaner(class: 'org.jenkinsci.plugins.proccleaner.PsCleaner') {
                killerType 'org.jenkinsci.plugins.proccleaner.PsAllKiller'
                killer(class: 'org.jenkinsci.plugins.proccleaner.PsAllKiller')
                username 'jenkins'
            }
        }
    }

    publishers {
        wsCleanup()
        archiveJunit("**/target/failsafe-reports/TEST-*.xml")
        mailer('mbiarnes@redhat.com', false, false)
    }

    steps {
        shell(kieServerTest)
        maven{
            mavenInstallation("${mvnVersion}")
            goals("-B -e -fae -nsu clean verify -P\$container")
            rootPOM("kie-server-parent/kie-server-tests/pom.xml")
            properties("kie.server.testing.kjars.build.settings.xml":"\$SETTINGS_XML_FILE")
            properties("maven.test.failure.ignore": true)
            properties("deployment.timeout.millis":"240000")
            properties("container.startstop.timeout.millis":"240000")
            properties("eap7.download.url":"http://download.devel.redhat.com/released/JBEAP-7/7.1.0/jboss-eap-7.1.0.zip")
            mavenOpts("-Xms1024m -Xmx1536m")
            providedSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905")
        }
    }
}

// +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//  run additional test: kieAlBuild-windows

def windowsTests='''set repo_list=droolsjbpm-build-bootstrap droolsjbpm-knowledge drools optaplanner jbpm droolsjbpm-integration droolsjbpm-tools kie-appformer-extensions guvnor kie-wb-common jbpm-form-modeler drools-wb jbpm-designer jbpm-console-ng optaplanner-wb kie-wb-distributions
for %%x in (%repo_list%) do (
    if "%%x" == "kie-wb-common" (
        rem clone the kie-wb-common into directory with shortest name possible to avoid long path issues
        git clone --depth 10 https://github.com/kiegroup/%%x.git k
    ) else (
        git clone --depth 10 https://github.com/kiegroup/%%x.git
    )
)
for %%x in (%repo_list%) do (
    if "%%x" == "kie-wb-common" (
        c:\\tools\\apache-maven-3.2.5\\bin\\mvn.bat -U -e -B -f k\\pom.xml clean install -Dfull -T2 -Dmaven.test.failure.ignore=true -Dgwt.memory.settings="-Xmx2g -Xms1g -XX:MaxPermSize=256m -Xss1M" -Dgwt.compiler.localWorkers=1 || exit \\b
    ) else (
        c:\\tools\\apache-maven-3.2.5\\bin\\mvn.bat -U -e -B -f %%x\\pom.xml clean install -Dfull -T2 -Dmaven.test.failure.ignore=true -Dgwt.memory.settings="-Xmx2g -Xms1g -XX:MaxPermSize=256m -Xss1M" -Dgwt.compiler.localWorkers=1 -Dgwt.compiler.skip=true || exit \\b
    )
)'''

job("windows-kieAllBuild-${kieMainBranch}") {
    disabled ()
    description("Builds all repos specified in\n" +
            "<a href=\"https://github.com/droolsjbpm/droolsjbpm-build-bootstrap/blob/master/script/repository-list.txt\">repository-list.txt</a> (master branch) on Windows machine.\n" +
            "It does not deploy the artifacts to staging repo (or any other remote). It just checks our repositories can be build and tested on Windows, so that \n" +
            "contributors do not hit issues when using Windows machines for development.<br/>\n" +
            "<br/>\n" +
            "<b>Important:</b> the workspace is under c:\\x, instead of c:\\jenkins\\workspace\\kie-all-build-windows-master. This is to decrease the path prefix as much as possible\n" +
            "to avoid long path issues on Windows (limit there is 260 chars).")

    label("windows")

    logRotator {
        numToKeep(10)
    }

    jdk("${javadk}")

    wrappers {
        timeout {
            absolute(300)
        }
        timestamps()
        colorizeOutput()
        preBuildCleanup()
    }

    triggers {
        cron("H 22 * * *")
    }

    publishers {
        wsCleanup()
        archiveJunit("**/target/*-reports/TEST-*.xml")
        mailer('mbiarnes@redhat.com', false, false)
    }

    configure { project ->
        project / 'buildWrappers' << 'org.jenkinsci.plugins.proccleaner.PreBuildCleanup' {
            cleaner(class: 'org.jenkinsci.plugins.proccleaner.PsCleaner') {
                killerType 'org.jenkinsci.plugins.proccleaner.PsAllKiller'
                killer(class: 'org.jenkinsci.plugins.proccleaner.PsAllKiller')
                username 'jenkins'
            }
        }
    }

    steps {
        environmentVariables {
            envs(MAVEN_OPTS : "-Xms2g -Xmx3g")
        }
        shell(windowsTests)
    }


}
// *****************************************************************************************************
// definition of kieDockerCi  script

def kieDockerCi='''
sh scripts/docker-clean.sh $kieVersion
sh scripts/update-versions.sh $kieVersion -s "$SETTINGS_XML"'''

job("kie-docker-ci-images-${kieMainBranch}") {
    description("Builds CI Docker images for master branch. <br> IMPORTANT: Created automatically by Jenkins job DSL plugin. Do not edit manually! The changes will get lost next time the job is generated. ")

    parameters {
        stringParam("kieVersion", "7.6.0-SNAPSHOT", "Please edit the version of the kie release <br> i.e. typically <b> major.minor.micro.EXT </b>i.e. 8.0.0.Beta1<br> Normally the kie version will be supplied by parent job <br> ******************************************************** <br> ")
    }

    scm {
        git {
            remote {
                github("${organization}/kie-docker-ci-images")
            }
            branch ("${kieMainBranch}")
        }
    }

    label("kieci-02")

    logRotator {
        numToKeep(10)
    }

    jdk("${javadk}")

    wrappers {
        timeout {
            absolute(120)
        }
        timestamps()
        colorizeOutput()
        preBuildCleanup()
        configFiles {
            mavenSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905"){
                targetLocation("\$WORKSPACE/settings.xml")
                variable("SETTINGS_XML")
            }
        }
    }

    publishers {
        wsCleanup()
        mailer('psiroky@redhat.com mbiarnes@redhat.com', false, false)
    }

    configure { project ->
        project / 'buildWrappers' << 'org.jenkinsci.plugins.proccleaner.PreBuildCleanup' {
            cleaner(class: 'org.jenkinsci.plugins.proccleaner.PsCleaner') {
                killerType 'org.jenkinsci.plugins.proccleaner.PsAllKiller'
                killer(class: 'org.jenkinsci.plugins.proccleaner.PsAllKiller')
                username 'jenkins'
            }
        }
    }

    steps {
        environmentVariables {
            envs(MAVEN_HOME : "/opt/tools/${mvnVersion}", PATH : "/opt/tools/${mvnVersion}/bin:\$PATH")
        }
        shell(kieDockerCi)
        maven{
            mavenInstallation("${mvnVersion}")
            goals("-e -B -U clean install")
            providedSettings("org.jenkinsci.plugins.configfiles.maven.MavenSettingsConfig1438340407905")
            properties("kie.artifacts.deploy.path":"/home/docker/kie-artifacts/\$kieVersion")
        }
    }
}

// **************************** VIEW to create on JENKINS CI *******************************************

listView("kieAllBuild-${kieMainBranch}"){
    description("all scripts needed for building a ${kieMainBranch} kieAll build")
    jobs {
        name("trigger-kieAllBuild-${kieMainBranch}")
        name("errai-kieAllBuild-${kieMainBranch}")
        name("kieAllBuild-${kieMainBranch}")
        name("jbpmTestCoverageMatrix-kieAllBuild-${kieMainBranch}")
        name("jbpmTestContainerMatrix-kieAllBuild-${kieMainBranch}")
        name("kieWbTestsMatrix-kieAllBuild-${kieMainBranch}")
        name("kieServerMatrix-kieAllBuild-${kieMainBranch}")
        name("windows-kieAllBuild-${kieMainBranch}")
        }
	columns {
            status()
            weather()
            name()
            lastSuccess()
            lastFailure()
        }
}

