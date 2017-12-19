About Tycho
===========

This repository is a fork for a Tycho project, it is a bit modified to perform resolution in parallel and have few fixes for caching.

This repository contains a Tycho 1.1.0-SNAPSHOT builds in folder `repository`.

Please refer to original tycho project as reference: 

  * [Project Homepage](http://www.eclipse.org/tycho/)


Please following in your pom.xml to try this enhancements.

```
<pluginRepositories>
  <pluginRepository>
    <id>tycho-enhancements-snapshots</id>
    <name>Tycho Enhancements Snapshots repository</name>
    <snapshots>
      <updatePolicy>always</updatePolicy>
    </snapshots>
    <url>https://haiodo.github.io/tycho/repository/</url>
  </pluginRepository>
</pluginRepositories> 
```

And specify tycho version 1.1.0-SNAPSHOT