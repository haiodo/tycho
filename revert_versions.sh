#!/bin/bash
mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion="1.2.0-SNAPSHOT"
mvn -Dtycho.mode=maven org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion="1.2.0-SNAPSHOT" -f ../tycho.extras
