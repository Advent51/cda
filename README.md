# Community Data Access

**CDA** is a Data Access tool that offers an abstraction layer to accessing data within CDF and CDE dashboards

**CDA** is one of the _tools_ of the **CTools** family and it is shipped within Pentaho BA Server

This is a maven project, and to build it use the following command
```
mvn clean install
```
The build result will be a Pentaho Plugin located in *assemblies/cda/target/cda-**.zip*. Then, this package can be dropped inside your system folder.

Additionally, **CDA** build environment requires some configuration on your maven *settings.xml* file.
The file is located under your .m2 directory on your home folder. Please make sure the following configuration is added:
```
<!-- profiles -->
<profile>
  <id>pentaho</id>
  <activation>
    <activeByDefault>true</activeByDefault>
  </activation>
  <repositories>
    <repository>
      <id>pentaho-nexus</id>
      <name>Nexus Internal</name>
      <url>http://nexus.pentaho.org/content/groups/omni</url>
    </repository>
  </repositories>
  <pluginRepositories>
    <pluginRepository>
      <id>pentaho-nexus</id>
      <name>Nexus Internal</name>
      <url>http://nexus.pentaho.org/content/groups/omni</url>
    </pluginRepository>
  </pluginRepositories>
</profile>

<!-- mirrors -->
<mirror>
  <id>pentaho-internal-repository</id>
  <url>http://nexus.pentaho.org/content/groups/omni</url>
  <mirrorOf>*</mirrorOf>
</mirror>
```

For issue tracking and bug report please use http://jira.pentaho.com/browse/CDA. Its master branch is built upon commit merges in Jenkins Continuous Integration located in http://ci.pentaho.com/job/cda-plugin/
