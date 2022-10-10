Jenkins Eclipse Temurin installer Plugin
=====================================

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/adoptopenjdk.svg)](https://plugins.jenkins.io/adoptopenjdk)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/adoptopenjdk.svg?color=blue)](https://plugins.jenkins.io/adoptopenjdk)

Provides an installer for the JDK tool that downloads the JDK from https://adoptium.net/

Usage recommendations
---------------------

We want to warn that this plugin is **NOT** a good practice for production environments. As it relies on
Adoptium's website to do the job, it's highly likely to stop working. It could happen because Adoptium's website
changes or even if Adoptium bans our downloads due to excessive bandwidth usage or some other reason).
Currently Adoptium is using GitHub for hosting release archives and GitHub could also stop working due to similar
reasons.

The recommended approach is to download the JDK distribution using other installers, for example downloading it from a
well known URL (preferably hosted on your own network) with _ZIP Tool Installer_, having it pre-installed in agent
docker images, or executing a script to do the job.

Configure plugin via Groovy script
---------
Either automatically upon [Jenkins post-initialization](https://wiki.jenkins.io/display/JENKINS/Post-initialization+script) or through [Jenkins script console](https://wiki.jenkins.io/display/JENKINS/Jenkins+Script+Console), example:

```groovy
#!/usr/bin/env groovy
import hudson.model.JDK
import hudson.tools.InstallSourceProperty
import io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller
import jenkins.model.Jenkins

final versions = [
        'jdk8': 'jdk8u222-b10'
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


Changelog
---------
[Changelog](CHANGELOG.md)

Maintainers
===========

* Mads Mohr Christensen

License
-------

	(The MIT License)

	Copyright (C) 2016 - 2019 Mads Mohr Christensen

	Permission is hereby granted, free of charge, to any person obtaining
	a copy of this software and associated documentation files (the
	'Software'), to deal in the Software without restriction, including
	without limitation the rights to use, copy, modify, merge, publish,
	distribute, sublicense, and/or sell copies of the Software, and to
	permit persons to whom the Software is furnished to do so, subject to
	the following conditions:

	The above copyright notice and this permission notice shall be
	included in all copies or substantial portions of the Software.

	THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND,
	EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
	MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
	IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
	CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
	TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
	SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
