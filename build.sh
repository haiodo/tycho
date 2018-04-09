#!/bin/bash
rm -rf ./repository

# Do a build
mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.2.0.han2"
mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.2.0.han2" -f ../tycho.extras

mvn clean deploy -DaltDeploymentRepository=snapshot-repo::default::file:./repository
mvn clean deploy -Preleng -f tycho-releng/pom.xml
mvn clean deploy -f ../tycho.extras -DaltDeploymentRepository=snapshot-repo::default::file:./repository

mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.2.0-SNAPSHOT"
mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.2.0-SNAPSHOT" -f ../tycho.extras
