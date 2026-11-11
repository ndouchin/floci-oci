package io.floci.oci.services.oke;

import io.floci.oci.services.identity.model.StoredCompartment;
import io.floci.oci.services.identity.model.StoredGroup;
import io.floci.oci.services.identity.model.StoredPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether IAM policies give a user {@code CLUSTER_MANAGE} on a cluster, the permission OKE
 * turns into full Kubernetes access ("Container Engine policy reference": {@code AdministerK8s}
 * needs {@code CLUSTER_MANAGE}). This reads policy statements narrowly: only
 * {@code Allow <subject> to manage <clusters | cluster-family | all-resources> in <location>},
 * where the subject is {@code group <name>[, <name>...]}, {@code group id <ocid>[, id <ocid>...]},
 * {@code group '<domain>'/'<name>'} (the {@code Default} domain only), {@code any-group} or
 * {@code any-user} ("Policy Syntax"), and the location is {@code tenancy}, {@code compartment id
 * <ocid>} or {@code compartment <name>[:<name>...]} resolved from the policy's own compartment
 * ("Policies and Compartment Hierarchies"). A statement with a {@code where} clause never grants,
 * nor does any other statement form: whatever floci-oci cannot evaluate grants nothing.
 */
final class OkeIamGrants {

    private static final Set<String> CLUSTER_MANAGE_RESOURCES = Set.of("clusters", "cluster-family", "all-resources");
    private static final String DEFAULT_DOMAIN = "default";

    private OkeIamGrants() {
    }

    /**
     * @param groups       the user's groups in the cluster's tenancy
     * @param clusterChain the cluster's compartment and those above it, nearest first, the root last
     */
    static boolean managesClusters(List<StoredPolicy> policies, List<StoredGroup> groups,
                                   List<StoredCompartment> clusterChain) {
        if (clusterChain.isEmpty()) {
            return false;
        }
        List<StoredCompartment> topDown = new ArrayList<>(clusterChain).reversed();
        for (StoredPolicy policy : policies) {
            if (policy.getStatements() == null) {
                continue;
            }
            int attachedAt = indexOf(topDown, policy.getCompartmentId());
            if (attachedAt < 0) {
                continue;
            }
            for (String statement : policy.getStatements()) {
                if (grants(statement, groups, topDown, attachedAt)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean grants(String statement, List<StoredGroup> groups, List<StoredCompartment> topDown,
                                  int attachedAt) {
        List<String> tokens = tokens(statement);
        if (tokens.size() < 6 || !"allow".equalsIgnoreCase(tokens.getFirst())) {
            return false;
        }
        int to = indexOfWord(tokens, "to", 1);
        if (to < 2 || to + 4 > tokens.size()) {
            return false;
        }
        String verb = tokens.get(to + 1).toLowerCase(Locale.ROOT);
        String resource = tokens.get(to + 2).toLowerCase(Locale.ROOT);
        if (!"manage".equals(verb) || !CLUSTER_MANAGE_RESOURCES.contains(resource)
                || !"in".equalsIgnoreCase(tokens.get(to + 3))) {
            return false;
        }
        List<String> location = tokens.subList(to + 4, tokens.size());
        if (location.isEmpty() || indexOfWord(location, "where", 0) >= 0) {
            return false;
        }
        return subjectMatches(String.join(" ", tokens.subList(1, to)), groups)
                && locationCovers(location, topDown, attachedAt);
    }

    private static boolean subjectMatches(String subject, List<StoredGroup> groups) {
        String normalized = subject.trim();
        if ("any-user".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("any-group".equalsIgnoreCase(normalized)) {
            return !groups.isEmpty();
        }
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("group ")) {
            return false;
        }
        for (String item : splitOutsideQuotes(normalized.substring("group ".length()), ',')) {
            String reference = item.trim();
            if (reference.toLowerCase(Locale.ROOT).startsWith("group ")) {
                reference = reference.substring("group ".length()).trim();
            }
            if (reference.toLowerCase(Locale.ROOT).startsWith("id ")) {
                String ocid = reference.substring("id ".length()).trim();
                if (groups.stream().anyMatch(group -> ocid.equals(group.getId()))) {
                    return true;
                }
                continue;
            }
            String name = groupName(reference);
            if (name != null && groups.stream().anyMatch(group -> name.equalsIgnoreCase(group.getName()))) {
                return true;
            }
        }
        return false;
    }

    /** The group name of {@code name}, {@code 'name'} or {@code '<domain>'/'<name>'}; null for a domain other than Default. */
    private static String groupName(String reference) {
        List<String> parts = splitOutsideQuotes(reference, '/');
        if (parts.size() == 2) {
            return DEFAULT_DOMAIN.equalsIgnoreCase(unquote(parts.get(0))) ? unquote(parts.get(1)) : null;
        }
        return parts.size() == 1 ? unquote(parts.getFirst()) : null;
    }

    private static boolean locationCovers(List<String> location, List<StoredCompartment> topDown, int attachedAt) {
        String kind = location.getFirst().toLowerCase(Locale.ROOT);
        if ("tenancy".equals(kind)) {
            return location.size() == 1 && attachedAt == 0;
        }
        if (!"compartment".equals(kind) || location.size() < 2) {
            return false;
        }
        if ("id".equalsIgnoreCase(location.get(1))) {
            return location.size() == 3 && indexOf(topDown, location.get(2)) >= attachedAt;
        }
        if (location.size() != 2) {
            return false;
        }
        List<String> path = splitOutsideQuotes(location.get(1), ':');
        if (path.size() == 1 && names(topDown.get(attachedAt), path.getFirst())) {
            return true;
        }
        int level = attachedAt;
        for (String segment : path) {
            level++;
            if (level >= topDown.size() || !names(topDown.get(level), segment)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a path segment names the compartment, by name or OCID. The root has no name. */
    private static boolean names(StoredCompartment compartment, String segment) {
        String value = unquote(segment);
        return value.equals(compartment.getId())
                || compartment.getName() != null && compartment.getName().equalsIgnoreCase(value);
    }

    private static int indexOf(List<StoredCompartment> topDown, String compartmentId) {
        for (int i = 0; i < topDown.size(); i++) {
            if (topDown.get(i).getId().equals(compartmentId)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfWord(List<String> tokens, String word, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (word.equalsIgnoreCase(tokens.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Whitespace-separated words; single-quoted text, spaces included, stays inside its word. */
    private static List<String> tokens(String statement) {
        List<String> tokens = new ArrayList<>();
        if (statement == null) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : statement.trim().toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static List<String> splitOutsideQuotes(String text, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : text.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            }
            if (c == separator && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
