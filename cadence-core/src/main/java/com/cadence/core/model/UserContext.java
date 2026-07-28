package com.cadence.core.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the evaluation engine knows about the subject of a flag check.
 *
 * <p>The {@code userId} is the bucketing key: it is what guarantees a user sees the same variant
 * on every request for the life of the rollout. For anonymous traffic, pass a stable session or
 * device identifier — never a random value per request, or the user will flip between variants.
 */
public final class UserContext {

    private final String userId;
    private final String country;
    private final Set<String> segments;
    private final Map<String, String> attributes;

    private UserContext(Builder builder) {
        this.userId = Objects.requireNonNull(builder.userId, "userId is the bucketing key and must not be null");
        this.country = builder.country;
        this.segments = Set.copyOf(builder.segments);
        this.attributes = Map.copyOf(builder.attributes);
    }

    public static Builder builder(String userId) {
        return new Builder(userId);
    }

    /** Convenience for the common case of a bare user ID with no targeting dimensions. */
    public static UserContext of(String userId) {
        return builder(userId).build();
    }

    public String userId() {
        return userId;
    }

    /** ISO 3166-1 alpha-2, or null when unknown. */
    public String country() {
        return country;
    }

    public Set<String> segments() {
        return segments;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    @Override
    public String toString() {
        return "UserContext[userId=%s, country=%s, segments=%s, attributes=%s]"
                .formatted(userId, country, segments, attributes);
    }

    public static final class Builder {
        private final String userId;
        private String country;
        private final Set<String> segments = new HashSet<>();
        private final Map<String, String> attributes = new HashMap<>();

        private Builder(String userId) {
            this.userId = userId;
        }

        public Builder country(String isoAlpha2) {
            this.country = isoAlpha2 == null ? null : isoAlpha2.toUpperCase();
            return this;
        }

        public Builder segment(String segment) {
            this.segments.add(segment);
            return this;
        }

        public Builder segments(Set<String> segments) {
            if (segments != null) {
                this.segments.addAll(segments);
            }
            return this;
        }

        public Builder attribute(String key, String value) {
            if (key != null && value != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            if (attributes != null) {
                attributes.forEach(this::attribute);
            }
            return this;
        }

        public UserContext build() {
            return new UserContext(this);
        }
    }
}
