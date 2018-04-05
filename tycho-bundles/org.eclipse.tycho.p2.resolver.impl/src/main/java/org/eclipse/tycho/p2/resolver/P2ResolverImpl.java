/*******************************************************************************
 * Copyright (c) 2008, 2015 Sonatype Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *    Rapicorp, Inc. - add support for IU type (428310)
 *******************************************************************************/
package org.eclipse.tycho.p2.resolver;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.IProvidedCapability;
import org.eclipse.equinox.p2.metadata.IRequirement;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.metadata.VersionRange;
import org.eclipse.equinox.p2.publisher.eclipse.BundlesAction;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.spi.p2.publisher.PublisherHelper;
import org.eclipse.tycho.ArtifactType;
import org.eclipse.tycho.PackagingType;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.ReactorProjectIdentities;
import org.eclipse.tycho.artifacts.DependencyResolutionException;
import org.eclipse.tycho.artifacts.IllegalArtifactReferenceException;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.ee.shared.ExecutionEnvironmentConfigurationStub;
import org.eclipse.tycho.core.shared.MavenLogger;
import org.eclipse.tycho.core.shared.MultiLineLogger;
import org.eclipse.tycho.core.shared.TargetEnvironment;
import org.eclipse.tycho.p2.impl.publisher.AuthoredIUAction;
import org.eclipse.tycho.p2.metadata.IArtifactFacade;
import org.eclipse.tycho.p2.repository.RepositoryLayoutHelper;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult;
import org.eclipse.tycho.p2.resolver.facade.P2Resolver;
import org.eclipse.tycho.p2.target.ArtifactTypeHelper;
import org.eclipse.tycho.p2.target.P2TargetPlatform;
import org.eclipse.tycho.p2.target.TargetPlatformFactoryImpl;
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub;
import org.eclipse.tycho.p2.util.resolution.AbstractResolutionStrategy;
import org.eclipse.tycho.p2.util.resolution.DependencyCollector;
import org.eclipse.tycho.p2.util.resolution.ProjectorResolutionStrategy;
import org.eclipse.tycho.p2.util.resolution.ResolutionDataImpl;
import org.eclipse.tycho.p2.util.resolution.ResolverException;
import org.eclipse.tycho.repository.p2base.metadata.QueryableCollection;
import org.eclipse.tycho.repository.util.LoggingProgressMonitor;

@SuppressWarnings("restriction")
public class P2ResolverImpl implements P2Resolver {

    private final MavenLogger logger;

    private final IProgressMonitor monitor;

    private List<TargetEnvironment> environments;
    private Map<String, String> additionalFilterProperties = new HashMap<>();

    private final List<IRequirement> additionalRequirements = new ArrayList<>();
    private final List<String> additionalRequirementsData = new ArrayList<>();

    private TargetPlatformFactoryImpl targetPlatformFactory;

    private P2TargetPlatform context;
    private Set<IInstallableUnit> currentProjectUnits;

    private Set<IInstallableUnit> usedTargetPlatformUnits;

    public P2ResolverImpl(TargetPlatformFactoryImpl targetPlatformFactory, MavenLogger logger) {
        this.targetPlatformFactory = targetPlatformFactory;
        this.logger = logger;
        this.monitor = new LoggingProgressMonitor(logger);
        this.environments = Collections.singletonList(TargetEnvironment.getRunningEnvironment());
    }

    @SuppressWarnings("unchecked")
    private void setContext(TargetPlatform targetPlatform, ReactorProject currentProject) {
        context = (P2TargetPlatform) targetPlatform;

        if (currentProject == null) {
            currentProjectUnits = Collections.emptySet();
        } else {
            currentProjectUnits = (Set<IInstallableUnit>) currentProject.getDependencyMetadata();
        }
    }

    @Override
    public List<P2ResolutionResult> resolveDependencies(TargetPlatform targetPlatform, ReactorProject project) {
        setContext(targetPlatform, project);

        if (project != null && PackagingType.TYPE_ECLIPSE_TEST_PLUGIN.equals(project.getPackaging())) {
            addDependenciesForTests();
        }

        ArrayList<P2ResolutionResult> results = new ArrayList<>();
        usedTargetPlatformUnits = new LinkedHashSet<>();

        ResolutionProjectCache cache = ResolutionCacheConfig.isUseProjectCache()
                ? new ResolutionProjectCache(ResolutionCacheConfig.getCacheLocation(), project,
                        ProjectorResolutionStrategy.class.getName(), additionalRequirementsData)
                : null;

        long st = System.currentTimeMillis();

        for (TargetEnvironment environment : environments) {
            boolean lastEnvironment = results.size() == environments.size() - 1;
            results.add(resolveDependencies(project, new ProjectorResolutionStrategy(logger), environment, cache,
                    lastEnvironment));
        }
        long ed = System.currentTimeMillis();
        logger.info("Resolve time: " + (ed - st));

        if (cache != null) {
            cache.save();
        }

        context.reportUsedLocalIUs(usedTargetPlatformUnits);
        usedTargetPlatformUnits = null;

        return results;
    }

    @Override
    public P2ResolutionResult collectProjectDependencies(TargetPlatform targetPlatform, ReactorProject project) {
        setContext(targetPlatform, project);

        ResolutionProjectCache cache = ResolutionCacheConfig.isUseProjectCache()
                ? new ResolutionProjectCache(ResolutionCacheConfig.getCacheLocation(), project,
                        DependencyCollector.class.getName(), additionalRequirementsData)
                : null;

        return resolveDependencies(project, new DependencyCollector(logger), new TargetEnvironment(null, null, null),
                cache, true);
    }

    @Override
    public P2ResolutionResult resolveMetadata(TargetPlatformConfigurationStub tpConfiguration, String eeName) {
        P2TargetPlatform contextImpl = targetPlatformFactory.createTargetPlatform(tpConfiguration,
                new ExecutionEnvironmentConfigurationStub(eeName), null, null);

        ResolutionDataImpl data = new ResolutionDataImpl(contextImpl.getEEResolutionHints());
        data.setAvailableIUs(contextImpl.getInstallableUnits());
        data.setRootIUs(new HashSet<IInstallableUnit>());
        data.setAdditionalRequirements(additionalRequirements);

        ProjectorResolutionStrategy strategy = new ProjectorResolutionStrategy(logger);
        strategy.setData(data);

        MetadataOnlyP2ResolutionResult result = new MetadataOnlyP2ResolutionResult();
        try {
            for (IInstallableUnit iu : strategy.multiPlatformResolve(environments, monitor)) {
                result.addArtifact(ArtifactType.TYPE_INSTALLABLE_UNIT, iu.getId(), iu.getVersion().toString(), iu);
            }
        } catch (ResolverException e) {
            logger.error("Resolution failed:");
            new MultiLineLogger(logger).error(e.getDetails(), "  ");
            throw new RuntimeException(e);
        }
        return result;
    }

    // TODO 412416 make this obsolete by adding appropriate getters in TargetPlatform interface
    @Override
    public P2ResolutionResult getTargetPlatformAsResolutionResult(TargetPlatformConfigurationStub tpConfiguration,
            String eeName) {
        P2TargetPlatform targetPlatform = targetPlatformFactory.createTargetPlatform(tpConfiguration,
                new ExecutionEnvironmentConfigurationStub(eeName), null, null);

        MetadataOnlyP2ResolutionResult result = new MetadataOnlyP2ResolutionResult();
        for (IInstallableUnit iu : targetPlatform.getInstallableUnits()) {
            result.addArtifact(ArtifactType.TYPE_INSTALLABLE_UNIT, iu.getId(), iu.getVersion().toString(), iu);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    protected P2ResolutionResult resolveDependencies(ReactorProject project, AbstractResolutionStrategy strategy,
            TargetEnvironment environment, ResolutionProjectCache cache, boolean saveMavenRepository) {

        Set<IInstallableUnit> availableUnits = context.getInstallableUnits();

        Collection<IInstallableUnit> newState = null;

        ResolutionDataImpl data = new ResolutionDataImpl(context.getEEResolutionHints());
        Set<IInstallableUnit> dependencyMetadata = null;
        if (project != null) {
            dependencyMetadata = (Set<IInstallableUnit>) project.getDependencyMetadata(true);
            data.setRootIUs(dependencyMetadata);
            Collection<IInstallableUnit> projectSecondaryIUs = (Collection<IInstallableUnit>) project
                    .getDependencyMetadata(false);
            if (!projectSecondaryIUs.isEmpty()) {
                availableUnits = new LinkedHashSet<>(availableUnits);
                availableUnits.addAll(projectSecondaryIUs);
            }
        } else {
            data.setRootIUs(Collections.<IInstallableUnit> emptySet());
        }
        data.setAdditionalRequirements(additionalRequirements);
        data.setAvailableIUs(availableUnits);
        data.setAdditionalFilterProperties(additionalFilterProperties);

        strategy.setData(data);

        if (cache == null || !cache.isAvailabble(environment)) {
            try {
                newState = strategy.resolve(environment, monitor);
            } catch (ResolverException e) {
                logger.info(e.getSelectionContext());
                logger.error("Cannot resolve project dependencies:");
                new MultiLineLogger(logger).error(e.getDetails(), "  ");
                logger.error("");
                logger.error("See http://wiki.eclipse.org/Tycho/Dependency_Resolution_Troubleshooting for help.");
                throw new DependencyResolutionException("Cannot resolve dependencies of " + project, e);
            }

            cache.update(newState, environment);
        } else {
            Set<String> missing = new HashSet<String>();
            newState = cache.getState(environment, availableUnits, missing);
            if (newState == null || missing.size() > 0) {
                logger.info("no dependency found: " + missing.toString() + " -- Recomputing");
                try {
                    newState = strategy.resolve(environment, monitor);
                } catch (ResolverException e) {
                    logger.info(e.getSelectionContext());
                    logger.error("Cannot resolve project dependencies:");
                    new MultiLineLogger(logger).error(e.getDetails(), "  ");
                    logger.error("");
                    logger.error("See http://wiki.eclipse.org/Tycho/Dependency_Resolution_Troubleshooting for help.");
                    throw new DependencyResolutionException("Cannot resolve dependencies of " + project, e);
                }
            }

            if (ResolutionCacheConfig.isDoValidate()) {
                try {
                    Set<IInstallableUnit> newStateValidate = new HashSet<>(strategy.resolve(environment, monitor));
                    Set<IInstallableUnit> newStateSet = new HashSet<IInstallableUnit>(newState);
                    if (!(newStateValidate.size() == newStateSet.size() && newStateValidate.containsAll(newStateSet))) {
                        logger.error("Resolved modules are not same as cached...!!!!");
                    }
                } catch (ResolverException e) {
                    logger.info(e.getSelectionContext());
                    logger.error("Cannot resolve project dependencies:");
                    new MultiLineLogger(logger).error(e.getDetails(), "  ");
                    logger.error("");
                    logger.error("See http://wiki.eclipse.org/Tycho/Dependency_Resolution_Troubleshooting for help.");
                    throw new DependencyResolutionException("Cannot resolve dependencies of " + project, e);
                }
            }
        }

        if (usedTargetPlatformUnits != null) {
            usedTargetPlatformUnits.addAll(newState);
        }

        P2ResolutionResult result = toResolutionResult(newState, project, saveMavenRepository);

        return result;
    }

    private P2ResolutionResult toResolutionResult(Collection<IInstallableUnit> newState, ReactorProject currentProject,
            boolean saveMavenRepository) {
        DefaultP2ResolutionResult result = new DefaultP2ResolutionResult();
        Set<String> missingArtifacts = new TreeSet<>();

        synchronized (context) {
            for (IInstallableUnit iu : newState) {
                addUnit(result, iu, currentProject, missingArtifacts);
            }
            // remove entries for which there were only "additional" IUs, but none with a recognized type
            result.removeEntriesWithUnknownType();

            // local repository index needs to be saved manually
            if (saveMavenRepository) {
                context.saveLocalMavenRepository();
            }

            failIfArtifactsMissing(missingArtifacts);

            // TODO 372780 remove; no longer needed when aggregation uses frozen target platform as source
            collectNonReactorIUs(result, newState);
            return result;
        }
    }

    private void addUnit(DefaultP2ResolutionResult result, IInstallableUnit iu, ReactorProject currentProject,
            Set<String> missingArtifacts) {

        if (currentProjectUnits.contains(iu)) {
            addReactorProject(result, currentProject.getIdentities(), iu);
            return;
        }

        ReactorProjectIdentities otherProject = context.getOriginalReactorProjectMap().get(iu);
        if (otherProject != null) {
            addReactorProject(result, otherProject, iu);
            return;
        }

        IArtifactFacade mavenArtifact = context.getOriginalMavenArtifactMap().get(iu);
        if (mavenArtifact != null) {
            addExternalMavenArtifact(result, mavenArtifact, iu);
            return;
        }

        for (IArtifactKey key : iu.getArtifacts()) {
            // this downloads artifacts if necessary; TODO parallelize download?
            File artifactLocation = context.getLocalArtifactFile(key);

            if (artifactLocation == null) {
                missingArtifacts.add(key.toString());
            } else {
                addArtifactFile(result, iu, key, artifactLocation);
            }
        }
    }

    private void failIfArtifactsMissing(Set<String> missingArtifacts) {
        if (!missingArtifacts.isEmpty()) {
            logger.error("The following artifacts could not be downloaded: ");
            for (String missingArtifact : missingArtifacts) {
                logger.error("  " + missingArtifact);
            }
            // TODO throw a typed exception here, so that we can log more information depending on the offline mode further up in the call stack
            throw new RuntimeException("Some required artifacts could not be downloaded. See log output for details.");
        }
    }

    private void collectNonReactorIUs(DefaultP2ResolutionResult result, Collection<IInstallableUnit> newState) {
        Map<IInstallableUnit, ReactorProjectIdentities> reactorProjectLookup = context.getOriginalReactorProjectMap();

        for (IInstallableUnit iu : newState) {
            if (!currentProjectUnits.contains(iu) && reactorProjectLookup.get(iu) == null) {
                result.addNonReactorUnit(iu);
            }
        }
    }

    private void addArtifactFile(DefaultP2ResolutionResult result, IInstallableUnit iu, IArtifactKey key,
            File artifactLocation) {
        String id = iu.getId();
        String version = iu.getVersion().toString();
        String mavenClassifier = null;

        if (PublisherHelper.OSGI_BUNDLE_CLASSIFIER.equals(key.getClassifier())) {
            result.addArtifact(ArtifactType.TYPE_ECLIPSE_PLUGIN, id, version, artifactLocation, mavenClassifier, iu);
        } else if (PublisherHelper.ECLIPSE_FEATURE_CLASSIFIER.equals(key.getClassifier())) {
            String featureId = getFeatureId(iu);
            if (featureId != null) {
                result.addArtifact(ArtifactType.TYPE_ECLIPSE_FEATURE, featureId, version, artifactLocation,
                        mavenClassifier, iu);
            }
        }

        // ignore other/unknown artifacts, like binary blobs for now.
        // throw new IllegalArgumentException();
    }

    private void addReactorProject(DefaultP2ResolutionResult result, ReactorProjectIdentities project,
            IInstallableUnit iu) {
        String id = iu.getId();
        String version = iu.getVersion().toString();
        String mavenClassifier = iu.getProperty(RepositoryLayoutHelper.PROP_CLASSIFIER);
        File location = project.getBasedir();

        addMavenArtifact(result, iu, id, version, mavenClassifier, location);
    }

    private void addExternalMavenArtifact(DefaultP2ResolutionResult result, IArtifactFacade mavenArtifact,
            IInstallableUnit iu) {
        String id = iu.getId();
        String version = iu.getVersion().toString();
        String mavenClassifier = iu.getProperty(RepositoryLayoutHelper.PROP_CLASSIFIER);
        File location = mavenArtifact.getLocation();

        addMavenArtifact(result, iu, id, version, mavenClassifier, location);
    }

    protected void addMavenArtifact(DefaultP2ResolutionResult result, IInstallableUnit iu, String id, String version,
            String mavenClassifier, File location) {
        final String contributingArtifactType;
        final String contributingArtifactId;

        // TODO 353889 infer the type from the p2 artifact as this is done for content from p2 repositories; this would prevent bug 430728
        if (isBundleOrFragmentWithId(iu, id)) {
            contributingArtifactType = ArtifactType.TYPE_ECLIPSE_PLUGIN;
            contributingArtifactId = id;
        } else {
            String featureId = getFeatureId(iu);
            if (featureId != null) {
                contributingArtifactType = ArtifactType.TYPE_ECLIPSE_FEATURE;
                // feature can have additional IUs injected via p2.inf
                contributingArtifactId = featureId;
            } else if (isProduct(iu)) {
                contributingArtifactType = ArtifactType.TYPE_ECLIPSE_PRODUCT;
                contributingArtifactId = id;
            } else if (isPureIU(iu)) {
                contributingArtifactType = ArtifactType.TYPE_INSTALLABLE_UNIT;
                contributingArtifactId = id;
            } else {
                // additional IU of an artifact/project -> will be added to the artifact/project by its location
                contributingArtifactType = null;
                contributingArtifactId = null;
            }
        }

        result.addArtifact(contributingArtifactType, contributingArtifactId, version, location, mavenClassifier, iu);
    }

    private boolean isPureIU(IInstallableUnit iu) {
        if ("true".equals(iu.getProperty(AuthoredIUAction.IU_TYPE)))
            return true;
        return false;
    }

    private String getFeatureId(IInstallableUnit iu) {
        for (IProvidedCapability provided : iu.getProvidedCapabilities()) {
            if (PublisherHelper.CAPABILITY_NS_UPDATE_FEATURE.equals(provided.getNamespace())) {
                return provided.getName();
            }
        }
        return null;
    }

    private boolean isBundleOrFragmentWithId(IInstallableUnit iu, String id) {
        for (IProvidedCapability provided : iu.getProvidedCapabilities()) {
            if (BundlesAction.CAPABILITY_NS_OSGI_BUNDLE.equals(provided.getNamespace())) {
                return id.equals(provided.getName());
            }
        }
        return false;
    }

    private boolean isProduct(IInstallableUnit iu) {
        return Boolean.parseBoolean(iu.getProperty(InstallableUnitDescription.PROP_TYPE_PRODUCT));
    }

    @Override
    public void setEnvironments(List<TargetEnvironment> environments) {
        if (environments == null) {
            throw new NullPointerException();
        }
        this.environments = environments;
    }

    @Override
    public void setAdditionalFilterProperties(Map<String, String> additionalFilterProperties) {
        if (additionalFilterProperties == null) {
            throw new NullPointerException();
        }
        this.additionalFilterProperties = additionalFilterProperties;
    }

    @Override
    public void addDependency(String type, String id, String versionRange) throws IllegalArtifactReferenceException {
        final VersionRange parsedVersionRange;
        try {
            parsedVersionRange = new VersionRange(versionRange);
        } catch (IllegalArgumentException e) {
            throw new IllegalArtifactReferenceException(
                    "The string \"" + versionRange + "\" is not a valid OSGi version range");
        }
        additionalRequirementsData.add(type + ":" + id + ":" + versionRange);
        additionalRequirements.add(ArtifactTypeHelper.createRequirementFor(type, id, parsedVersionRange));
    }

    private void addDependenciesForTests() {
        /*
         * In case the test harness bundles (cf. TestMojo.getTestDependencies()) are part of the
         * reactor, the dependency resolution needs to identify them as a dependency of
         * eclipse-test-plugin modules. Otherwise they will be missing in the final target platform
         * of the module - they would be filtered from the external target platform, and not added
         * from the reactor - and hence the test runtime resolution would fail (see bug 443396).
         */
        additionalRequirements.add(optionalGreedyRequirementTo("org.eclipse.equinox.launcher"));
        additionalRequirements.add(optionalGreedyRequirementTo("org.eclipse.core.runtime"));
        additionalRequirements.add(optionalGreedyRequirementTo("org.eclipse.ui.ide.application"));

        additionalRequirementsData.add("" + ":" + "org.eclipse.equinox.launcher");
        additionalRequirementsData.add("" + ":" + "org.eclipse.core.runtime");
        additionalRequirementsData.add("" + ":" + "org.eclipse.ui.ide.application");
    }

    private static IRequirement optionalGreedyRequirementTo(String bundleId) {
        return MetadataFactory.createRequirement(IInstallableUnit.NAMESPACE_IU_ID, bundleId, VersionRange.emptyRange,
                null, true, true, true);
    }

    public List<IRequirement> getAdditionalRequirements() {
        return additionalRequirements;
    }

    // TODO 412416 this should be a method on the class TargetPlatform
    @Override
    public P2ResolutionResult resolveInstallableUnit(TargetPlatform targetPlatform, String id, String versionRange) {
        setContext(targetPlatform, null);

        QueryableCollection queriable = new QueryableCollection(context.getInstallableUnits());

        VersionRange range = new VersionRange(versionRange);
        IRequirement requirement = MetadataFactory.createRequirement(IInstallableUnit.NAMESPACE_IU_ID, id, range, null,
                1 /* min */, Integer.MAX_VALUE /* max */, false /* greedy */);

        IQueryResult<IInstallableUnit> result = queriable
                .query(QueryUtil.createLatestQuery(QueryUtil.createMatchQuery(requirement.getMatches())), monitor);

        Set<IInstallableUnit> newState = result.toUnmodifiableSet();

        return toResolutionResult(newState, null, true);
    }
}
