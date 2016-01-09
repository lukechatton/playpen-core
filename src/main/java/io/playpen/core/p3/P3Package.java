package io.playpen.core.p3;

import lombok.Data;
import org.json.JSONObject;

import java.util.*;

@Data
public class P3Package {

    @Data
    public static class PackageStepConfig {
        IPackageStep step;
        JSONObject config;
    }

    @Data
    public static class P3PackageInfo {
        private String id;
        private String version;

        @Override
        public boolean equals(Object other) {
            if(other instanceof P3PackageInfo) {
                P3PackageInfo o = (P3PackageInfo)other;
                return id.equals(o.getId()) && version.equals(o.getVersion());
            }

            return false;
        }

        @Override
        public int hashCode() {
            return id.hashCode() ^ version.hashCode();
        }
    }

    private String localPath;

    private boolean resolved;

    private String id;

    private String version;

    private List<P3Package> dependencies = new ArrayList<>();

    private Map<String, Integer> resources = new HashMap<>();

    private Set<String> attributes = new HashSet<>();

    private Map<String, String> strings = new HashMap<>();

    private List<PackageStepConfig> provisionSteps = new ArrayList<>();

    private List<PackageStepConfig> executionSteps = new ArrayList<>();

    private List<PackageStepConfig> shutdownSteps = new ArrayList<>();

    /**
     * Checks to make sure required fields are filled. Does not check resolution status!
     */
    public boolean validate() {
        if(id == null || id.isEmpty() || version == null || version.isEmpty())
            return false;

        for(P3Package p3 : dependencies)
        {
            if(!p3.validate())
                return false;
        }

        return true;
    }
}