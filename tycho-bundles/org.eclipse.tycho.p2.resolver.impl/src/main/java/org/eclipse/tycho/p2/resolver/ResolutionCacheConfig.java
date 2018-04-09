package org.eclipse.tycho.p2.resolver;

import java.io.File;

public class ResolutionCacheConfig {

    public static boolean isUseProjectCache() {
        String cacheDir = System.getProperty("tycho.precache.dir");
        return cacheDir != null;
    }

    public static File getCacheLocation() {
        String cacheDir = System.getProperty("tycho.precache.dir");
        if (cacheDir != null) {
            return new File(cacheDir);
        }
        return null;
    }

    public static boolean isDoValidate() {
        return false;
    }

    public static boolean isDoResave() {
        return "true".equals(System.getProperty("tycho.precache.resave"));
    }

    public static boolean isDoParallelResolve() {
        return !"false".equals(System.getProperty("tycho.resolve.parallel"));
    }

}
