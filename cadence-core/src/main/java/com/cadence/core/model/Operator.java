package com.cadence.core.model;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Comparison applied by an {@link RuleType#ATTRIBUTE} rule. */
public enum Operator {

    IN {
        @Override public boolean test(String actual, List<String> expected) {
            return actual != null && expected.contains(actual);
        }
    },
    NOT_IN {
        @Override public boolean test(String actual, List<String> expected) {
            return actual == null || !expected.contains(actual);
        }
    },
    EQUALS {
        @Override public boolean test(String actual, List<String> expected) {
            return actual != null && !expected.isEmpty() && actual.equals(expected.get(0));
        }
    },
    NOT_EQUALS {
        @Override public boolean test(String actual, List<String> expected) {
            return !EQUALS.test(actual, expected);
        }
    },
    CONTAINS {
        @Override public boolean test(String actual, List<String> expected) {
            return actual != null && expected.stream().anyMatch(actual::contains);
        }
    },
    STARTS_WITH {
        @Override public boolean test(String actual, List<String> expected) {
            return actual != null && expected.stream().anyMatch(actual::startsWith);
        }
    },
    ENDS_WITH {
        @Override public boolean test(String actual, List<String> expected) {
            return actual != null && expected.stream().anyMatch(actual::endsWith);
        }
    },
    /**
     * Regex match. A malformed pattern never throws at evaluation time: a flag with a bad
     * regex must not take down the calling application's request path, so it simply does not match.
     */
    REGEX {
        @Override public boolean test(String actual, List<String> expected) {
            if (actual == null) {
                return false;
            }
            for (String pattern : expected) {
                try {
                    if (Pattern.compile(pattern).matcher(actual).matches()) {
                        return true;
                    }
                } catch (PatternSyntaxException ignored) {
                    // Deliberately swallowed: an operator typo degrades to "no match", never to an exception.
                }
            }
            return false;
        }
    };

    /** @param actual the value pulled from the user context (may be null); @param expected the rule's configured values */
    public abstract boolean test(String actual, List<String> expected);

    public static Operator fromString(String raw) {
        return raw == null ? IN : Operator.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
