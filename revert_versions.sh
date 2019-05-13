#!/bin/bash
mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.4.0-SNAPSHOT"
mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:1.0.0:set-version -DnewVersion="1.4.0-SNAPSHOT" -f ../tycho.extras
