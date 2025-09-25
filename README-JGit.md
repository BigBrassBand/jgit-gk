## Description

Repository access and algorithms.

# 1. Changes in BigBrassBand/jiragitcloud project

## 1.1 Accessing the Github Packages

To do so, follow these steps:

1. generate a PAT for your Github user
2. edit your local `~/.m2/settings.xml`
3. add a `<repository>` and `<server>` sections like below:

```xml
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/BigBrassBand/jira-git-plugin</url>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
```
```xml
  <server>
    <id>github</id>
    <username>GITHUB-USERNAME</username>
    <password>GITHUB-PAT</password>
  </server>
```

## 1.2 Change JGit version number

Change version number of JGit in jiragitcloud/appserver/pom.xml file.  
For example,  
`<jgit.version>7.3.0.202506031305-r-cloud.1</jgit.version>`


# 2. BigBrassBand/jgit-gk project (generation and deployment new JGit packages)
Changes in appropriate new branch from last JGit release in BigBrassBand/jgit-gk fork.

## 2.1 Update, resolve conflicts, etc..

## 2.2 Change version number of JGit
For example, current version of JGit release is   
v7.3.0.202506031305-r  
so version of our changes is   
 7.3.0.202506031305-r-cloud.1

To change version in our branch of JGit run the commands below from root directory of the project. version.sh generates version number from tag name.

`git tag -a 7.3.0.202506031305-r-cloud.1 -m "JGit version with cloud changes"`    
`./tools/version.sh --release`

## 2.3 Building & Installing Locally

build library
`mvn package`

install locally
`mvn install`


## 2.4 Deploying updated packages of JGit fork to Github repository

Deploy the new JGit packages to https://maven.pkg.github.com/BigBrassBand/jira-git-plugin maven repository:

with tests  
`mvn -pl '!org.eclipse.jgit.benchmarks' -DaltDeploymentRepository=github::https://maven.pkg.github.com/BigBrassBand/jira-git-plugin clean deploy`  
or skip all tests  
`mvn -pl '!org.eclipse.jgit.benchmarks' -DaltDeploymentRepository=github::https://maven.pkg.github.com/BigBrassBand/jira-git-plugin -Dmaven.test.skip=true clean deploy`

[Github Documentation](https://docs.github.com/en/packages/using-github-packages-with-your-projects-ecosystem/configuring-apache-maven-for-use-with-github-packages).
