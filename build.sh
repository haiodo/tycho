#!/bin/bash
rm -rf ./repository

# Do a build
# 
mvn clean deploy -DaltDeploymentRepository=snapshot-repo::default::file:./repository
mvn clean deploy -f ../tycho.extras -DaltDeploymentRepository=snapshot-repo::default::file:./repository
