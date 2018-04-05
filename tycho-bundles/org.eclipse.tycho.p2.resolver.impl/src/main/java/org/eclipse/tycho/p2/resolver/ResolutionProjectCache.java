package org.eclipse.tycho.p2.resolver;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.core.shared.TargetEnvironment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 
 * Manages one bundle cached resolution information for all platforms.
 * 
 * @author Andrey Sobolev(haiodo@gmail.com)
 *
 */
public class ResolutionProjectCache {

    private static final String PLATFORM_ATTR = "platform";
    private static final String VERSION_ATTR = "version";
    private static final String NAME_ATTR = "name";
    private static final String ID_ATTR = "id";
    private static final String ARCH_ATTR = "arch";
    private static final String WS_ATTR = "ws";
    private static final String OS_ATTR = "os";
    private static final String DEPENDENCIES_ELEMENT = "dependencies";
    private static final String DEPENDENCY_ELEMENT = "dependency";

    private static final String PLATFORMS_ELEMENT = "platforms";
    private static final String PLATFORM_ELEMENT = "platform";

    private File file;

    public static class Entry {
        String name;
        String version;
        Set<TargetEnvironment> platforms = new HashSet<>();
    }

    private Map<String, Entry> entries = new HashMap<>();

    private Map<String, TargetEnvironment> envs = new HashMap<>();

    private boolean loaded;
    private String extraHash;

    public void update(Collection<IInstallableUnit> newState, TargetEnvironment environment) {

        addEnvironment(environment);

        for (IInstallableUnit unit : newState) {
            String key = getKey(unit);
            Entry ee = entries.get(key);
            if (ee != null) {
                // Same bundle, check platforms
                ee.platforms.add(environment);
            } else {
                // We need to add new version module here
                Entry newEntry = new Entry();
                newEntry.name = unit.getId();
                newEntry.version = unit.getVersion().toString();
                newEntry.platforms.add(environment);
                entries.put(key, newEntry);
            }
        }
    }

    private String getKey(IInstallableUnit unit) {
        return unit.getId() + "-" + unit.getVersion().toString();
    }

    private String getKey(Entry newEntry) {
        return newEntry.name + "-" + newEntry.version;
    }

    private void addEnvironment(TargetEnvironment environment) {
        if (!envs.containsValue(environment)) {
            int i = 1;
            String key = "" + environment.getOs();
            while (true) {
                if (envs.containsKey(key)) {
                    i++;
                    key = "" + environment.getOs() + "_" + i;
                    continue;
                }
                envs.put(key, environment);
                break;
            }
        }
    }

    public ResolutionProjectCache(File cacheLocation, ReactorProject project, String strategy,
            List<String> additionalRequirementsData) {

        File root = cacheLocation;
        root.mkdirs();

        extraHash = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("md5");
            digest.update(strategy.getBytes());

            if (additionalRequirementsData != null) {
                for (String req : additionalRequirementsData) {
                    digest.update(req.getBytes());
                }
            }

            extraHash += Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        file = project != null ? new File(root, project.getArtifactId() + ".tycho_cache") : null;

        if (file != null && file.exists()) {
            load();
        }
    }

    private void load() {
        try {
            if (!(file != null && file.exists())) {
                return;
            }
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(file);

            Element rootElement = doc.getDocumentElement();
            NodeList list = rootElement.getChildNodes();

            String hash = rootElement.getAttribute("hash");
            if (!hash.equals(extraHash)) {
                return;
            }
            loaded = true;

            Element pluginElement = null;
            for (int i = 0; i < list.getLength(); i++) {
                Node nde = list.item(i);
                if (nde instanceof Element) {
                    Element el = (Element) nde;
                    if (el.getTagName().equals(PLATFORMS_ELEMENT)) {
                        NodeList platformChilds = el.getChildNodes();
                        for (int j = 0; j < platformChilds.getLength(); j++) {
                            Node pc = platformChilds.item(j);
                            if (pc instanceof Element) {
                                Element pce = (Element) pc;
                                addEnvironment(pce);
                            }
                        }

                    } else if (el.getTagName().equals(DEPENDENCIES_ELEMENT)) {
                        pluginElement = el;
                    }
                }
            }
            if (pluginElement != null) {
                NodeList plChilds = pluginElement.getChildNodes();
                for (int j = 0; j < plChilds.getLength(); j++) {
                    Node pe = plChilds.item(j);
                    if (pe instanceof Element) {
                        Entry ee = loadElement(pe);
                        entries.put(getKey(ee), ee);
                    }
                }
            }
        } catch (Throwable e) {
        }
    }

    private void addEnvironment(Element pce) {
        String os = pce.getAttribute(OS_ATTR);
        String ws = pce.getAttribute(WS_ATTR);
        String arch = pce.getAttribute(ARCH_ATTR);

        String id = pce.getAttribute(ID_ATTR);

        TargetEnvironment pl = new TargetEnvironment(os, ws, arch);
        envs.put(id, pl);
    }

    private Entry loadElement(Node pe) {
        Element elem = (Element) pe;

        Entry entry = new Entry();
        entry.name = elem.getAttribute(NAME_ATTR);
        entry.version = elem.getAttribute(VERSION_ATTR);
        String envs = elem.getAttribute(PLATFORM_ATTR);
        if (envs != null && envs.trim().length() > 0) {
            for (String env : envs.split(",")) {
                TargetEnvironment environment = this.envs.get(env);
                if (environment != null) {
                    entry.platforms.add(environment);
                }
            }
        } else {
            entry.platforms.addAll(this.envs.values());
        }
        return entry;
    }

    public boolean isAvailabble(TargetEnvironment environment) {
        return loaded && this.envs.containsValue(environment);
    }

    public Collection<IInstallableUnit> getState(TargetEnvironment environment, Set<IInstallableUnit> availableUnits,
            Set<String> missing) {

        if (!loaded || !this.envs.containsValue(environment)) {
            return null;
        }
        List<IInstallableUnit> newState = new ArrayList<IInstallableUnit>();

        Map<String, IInstallableUnit> versionedUnits = new HashMap<>();

        for (IInstallableUnit unit : availableUnits) {
            versionedUnits.put(getKey(unit), unit);
        }

        for (Entry ee : entries.values()) {
            if (ee.platforms.contains(environment)) {
                String key = getKey(ee);
                IInstallableUnit unit = versionedUnits.get(key);
                if (unit == null) {
                    // Find with same name with different version if it is alone.
                    List<IInstallableUnit> units = new ArrayList<>();
                    for (IInstallableUnit u : availableUnits) {
                        if (u.getId().equals(ee.name)) {
                            units.add(u);
                        }
                    }
                    if (units.size() >= 1) {
                        newState.add(units.get(0));
                    } else {
                        missing.add(key);
                    }
                } else {
                    newState.add(unit);
                }
            }
        }

        return newState;
    }

    public void save() {
        if (loaded) {
            return;
        }
        //

        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document document = dBuilder.newDocument();

            Element root = document.createElement("dependencies");
            root.setAttribute("hash", extraHash);
            document.appendChild(root);

            Element platforms = document.createElement(PLATFORMS_ELEMENT);
            root.appendChild(platforms);

            Element deps = document.createElement(DEPENDENCIES_ELEMENT);
            root.appendChild(deps);

            Set<TargetEnvironment> allEnvs = new HashSet<>(this.envs.values());

            for (Map.Entry<String, TargetEnvironment> env : this.envs.entrySet()) {

                Element pl = document.createElement(PLATFORM_ELEMENT);
                platforms.appendChild(pl);

                TargetEnvironment environment = env.getValue();

                pl.setAttribute(ID_ATTR, env.getKey());
                pl.setAttribute(ARCH_ATTR, environment.getArch());
                pl.setAttribute(OS_ATTR, environment.getOs());
                pl.setAttribute(WS_ATTR, environment.getWs());

            }

            Map<TargetEnvironment, String> revMap = new HashMap<>();
            for (Map.Entry<String, TargetEnvironment> ple : this.envs.entrySet()) {
                revMap.put(ple.getValue(), ple.getKey());
            }

            for (Entry ee : entries.values()) {
                Element el = document.createElement(DEPENDENCY_ELEMENT);
                deps.appendChild(el);

                el.setAttribute(NAME_ATTR, ee.name);
                el.setAttribute(VERSION_ATTR, ee.version);

                if (!ee.platforms.containsAll(allEnvs)) {
                    // Not all environments same
                    el.setAttribute(PLATFORM_ATTR, ee.platforms.stream().map((pl) -> {
                        return revMap.get(pl);
                    }).collect(Collectors.joining(",")));
                }
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();

            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            Result output = new StreamResult(file);
            Source input = new DOMSource(document);

            transformer.transform(input, output);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
