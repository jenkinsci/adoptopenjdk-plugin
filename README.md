# Eclipse Temurin :tm: JDK installer plugin

Provides an installer for the JDK tool that downloads the Eclipse Temurin :tm: build based upon OpenJDK from the [Adoptium Working Group](https://adoptium.net/).

## Usage recommendations

We want to warn that this plugin is **NOT** a good practice for production environments. As it relies on
Adoptium's website to do the job, it's highly likely to stop working. It could happen because Adoptium's website
changes or even if Adoptium bans our downloads due to excessive bandwidth usage or some other reason.
Currently Adoptium is using GitHub for hosting release archives and GitHub could also stop working due to similar
reasons.

The recommended and preferred approach is to download the JDK distribution using other installers, for example downloading it from a
well known URL (preferably hosted on your own network) with _ZIP Tool Installer_, having it pre-installed in agent
docker images, or executing a script to do the job.

## Configure plugin with [Configuration as Code](https://plugins.jenkins.io/configuration-as-code/)

The [configuration as code plugin](https://plugins.jenkins.io/configuration-as-code/) allows administrators to automate Jenkins configuration.
The sample configuration below defines tool installers based on agent labels, and uses

* locally hosted Java installers for Linux and Windows agents (assuming local domain in `example.com`)
* locally installed Java on agents with the `Alpine` label
* locally installed Java on agents with the `cloud` label
* locally installed Java on agents with the `freebsd` label

If none of those installers are selected, then as a fallback, the agent will download the specified Java version from the Eclipse Temurin :tm: project.

The example shows the preference to first use locally available zip files and local installations of the JDK.
The JDK will be downloaded from the Eclipse Temurin project only in cases where the local installation is not available or does not apply.

```yaml
tool:
  jdk:
    installations:
    - name: "jdk11"
      properties:
      - installSource:
          installers:
          - zip:
              label: "linux && amd64 && !Alpine && !cloud"
              subdir: "jdk-11.0.25+9"
              url: "https://example.com/jdk/11/OpenJDK11U-jdk_x64_linux_hotspot_11.0.25_9.tar.gz"
          - zip:
              label: "windows && amd64"
              subdir: "jdk-11.0.25+9"
              url: "https://example.com/jdk/11/OpenJDK11U-jdk_x64_windows_hotspot_11.0.25_9.zip"
          - command:
              command: "true"
              label: "cloud"
              toolHome: "/home/jenkins/tools/jdk-11.0.25+9"
          - command:
              command: "true"
              label: "freebsd"
              toolHome: "/usr/local/openjdk11"
          - adoptOpenJdkInstaller:
              id: "jdk-11.0.25+9"
    - name: "jdk17"
      properties:
      - installSource:
          installers:
          - zip:
              label: "linux && amd64 && !Alpine && !cloud"
              subdir: "jdk-17.0.13+11"
              url: "https://example.com/jdk/17/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz"
          - zip:
              label: "windows && amd64"
              subdir: "jdk-17.0.13+11"
              url: "https://example.com/jdk/17/OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip"
          - command:
              command: "true"
              label: "cloud"
              toolHome: "/home/jenkins/tools/jdk-17.0.13+11"
          - command:
              command: "true"
              label: "freebsd"
              toolHome: "/usr/local/openjdk17"
          - adoptOpenJdkInstaller:
              id: "jdk-17.0.13+11"
    - name: "jdk21"
      properties:
      - installSource:
          installers:
          - zip:
              label: "linux && amd64 && !Alpine && !cloud"
              subdir: "jdk-21.0.5+11"
              url: "https://example.com/jdk/21/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz"
          - zip:
              label: "windows && amd64"
              subdir: "jdk-21.0.5+11"
              url: "https://example.com/jdk/21/OpenJDK21U-jdk_x64_windows_hotspot_21.0.5_11.zip"
          - command:
              command: "true"
              label: "cloud"
              toolHome: "/home/jenkins/tools/jdk-21.0.5+11"
          - adoptOpenJdkInstaller:
              id: "jdk-21.0.5+11"
```

## Configure plugin via Groovy script

Either automatically upon [Jenkins post-initialization](https://www.jenkins.io/doc/book/managing/groovy-hook-scripts/) or through
[Jenkins script console](https://www.jenkins.io/doc/book/managing/script-console/), example:

```groovy
#!/usr/bin/env groovy
import hudson.model.JDK
import hudson.tools.InstallSourceProperty
import io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller
import jenkins.model.Jenkins

final versions = [
        'jdk8' : 'jdk8u392-b08',
        'jdk11': 'jdk-11.0.25+9',
        'jdk17': 'jdk-17.0.13+11',
        'jdk21': 'jdk-21.0.5+11',
]

Jenkins.instance.getDescriptor(hudson.model.JDK).with {
    installations = versions.collect {
        new JDK(it.key, '', [
                new InstallSourceProperty([
                        new AdoptOpenJDKInstaller(it.value)
                ])
        ])
    } as JDK[]

}
```

## Changelog

Changes in each release are described in [GitHub releases](https://github.com/jenkinsci/adoptopenjdk-plugin/releases).
