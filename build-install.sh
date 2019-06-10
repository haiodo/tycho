#!/bin/bash
rm -rf ./repository

# Do a build
# mvn clean install -f clean-pom.xml
# mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.4.0.han1"
# mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.4.0.han1" -f ../tycho.extras

mvn clean install -Preleng -f tycho-releng/pom.xml
mvn clean install
mvn clean install -f ../tycho.extras

# mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.4.0-SNAPSHOT"
# mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.4.0" -f ../tycho.extras
